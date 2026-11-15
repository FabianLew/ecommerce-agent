package com.ecommerceagent.automation.execution;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import com.ecommerceagent.automation.Onboarding;
import com.ecommerceagent.automation.guardian.InventoryProtectionPolicy;
import com.ecommerceagent.identity.AuditEntry;
import com.ecommerceagent.identity.AuditTrail;
import com.ecommerceagent.identity.StoreAccess;
import com.ecommerceagent.identity.StoreAccessDeniedException;
import com.ecommerceagent.identity.StorePermission;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.integration.MetaConnection;
import com.ecommerceagent.integration.MetaConnectionStatus;
import com.ecommerceagent.integration.MetaConnections;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The per-store, per-action permission and the effective level (ADR-0025 points 2 to 4). The store's own level is
 * stored; the effective level is the lowest of it, the deployment ceiling, the Meta connection, the mutation stop and
 * the monitoring start, and is always computed, never stored.
 */
@Service
@NamedInterface("execution")
public class ActionPermissions {

    public static final String PAUSE_AD = AuthorizedAction.PAUSE_AD;
    public static final Set<String> ACTION_TYPES = Set.of(PAUSE_AD);
    public static final ActionLevel DEFAULT_LEVEL = ActionLevel.PROPOSE_ONLY;

    public static final String NOT_PERMITTED = "ACTION_NOT_PERMITTED";
    public static final String SHADOW_MODE = "SHADOW_MODE";
    public static final String META_NOT_CONNECTED = "META_NOT_CONNECTED";
    public static final String META_WRITE_PERMISSION_MISSING = "META_WRITE_PERMISSION_MISSING";

    static final int MAX_REASON = 500;
    private static final String RESOURCE = "store_action_permission";

    /** Where a cap comes from, in the order its refusal code is reported (ADR-0025 point 4). */
    public enum Source {
        MONITORING,
        STORE,
        DEPLOYMENT,
        META,
        MUTATION_STOP
    }

    /** The sources that decide whether a proposal is put to a person at all; they block a rejection too. */
    public static final Set<Source> DECIDABLE = EnumSet.of(Source.STORE, Source.DEPLOYMENT);

    /** The store's own level as stored; version 0 while nobody has changed it (the default applies). */
    public record Permission(String actionType, ActionLevel level, String reason, int version, UUID changedBy,
            Instant changedAt) {
    }

    /** One source that keeps the effective level below {@code APPROVAL_REQUIRED}, with its refusal code. */
    public record Cap(Source source, ActionLevel level, String code) {
    }

    /** The effective level and every cap below {@code APPROVAL_REQUIRED}, in reporting order. */
    public record Effective(String actionType, ActionLevel level, List<Cap> caps) {

        public boolean executable() {
            return level == ActionLevel.APPROVAL_REQUIRED;
        }

        /** The code of the first cap from one of the sources, or empty when none of them caps the level. */
        public Optional<String> refusal(Set<Source> sources) {
            return caps.stream().filter(cap -> sources.contains(cap.source())).map(Cap::code).findFirst();
        }

        public Optional<String> refusal() {
            return refusal(EnumSet.allOf(Source.class));
        }
    }

    private final InventoryProtectionPolicy policy;
    private final MetaConnections meta;
    private final MutationStops stops;
    private final Onboarding onboarding;
    private final StoreAccess storeAccess;
    private final AuditTrail audit;
    private final JdbcClient jdbc;
    private final Clock clock;

    ActionPermissions(InventoryProtectionPolicy policy, MetaConnections meta, MutationStops stops,
            Onboarding onboarding, StoreAccess storeAccess, AuditTrail audit, JdbcClient jdbc, Clock clock) {
        this.policy = policy;
        this.meta = meta;
        this.stops = stops;
        this.onboarding = onboarding;
        this.storeAccess = storeAccess;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Permission permission(TrustedStoreScope scope, String actionType) {
        String type = requireType(actionType);
        return jdbc.sql("""
                        SELECT level, reason, version, changed_by, changed_at FROM store_action_permission
                        WHERE organization_id = :organizationId AND store_id = :storeId AND action_type = :type
                        """)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("type", type)
                .query((rs, n) -> new Permission(type, ActionLevel.valueOf(rs.getString("level")),
                        rs.getString("reason"), rs.getInt("version"), rs.getObject("changed_by", UUID.class),
                        rs.getTimestamp("changed_at").toInstant()))
                .optional()
                .orElse(new Permission(type, DEFAULT_LEVEL, null, 0, null, null));
    }

    public Effective effective(TrustedStoreScope scope, String actionType) {
        return effective(scope, permission(scope, actionType));
    }

    private Effective effective(TrustedStoreScope scope, Permission permission) {
        List<Cap> caps = new ArrayList<>();
        if (!onboarding.monitoringStarted(scope)) {
            caps.add(new Cap(Source.MONITORING, ActionLevel.PROPOSE_ONLY, Onboarding.MONITORING_NOT_STARTED));
        }
        if (permission.level() != ActionLevel.APPROVAL_REQUIRED) {
            caps.add(new Cap(Source.STORE, permission.level(), NOT_PERMITTED));
        }
        if (policy.mode() != InventoryProtectionPolicy.Mode.EXECUTE_ON_APPROVAL) {
            caps.add(new Cap(Source.DEPLOYMENT, ActionLevel.PROPOSE_ONLY, SHADOW_MODE));
        }
        metaCap(meta.find(scope)).ifPresent(caps::add);
        stops.blocker(scope).ifPresent(code -> caps.add(new Cap(Source.MUTATION_STOP, ActionLevel.PROPOSE_ONLY,
                code)));
        ActionLevel level = caps.stream().map(Cap::level).reduce(ActionLevel.APPROVAL_REQUIRED, ActionLevel::min);
        return new Effective(permission.actionType(), level, List.copyOf(caps));
    }

    private static Optional<Cap> metaCap(Optional<MetaConnection> connection) {
        if (connection.isEmpty() || connection.get().status() != MetaConnectionStatus.CONNECTED) {
            return Optional.of(new Cap(Source.META, ActionLevel.OFF, META_NOT_CONNECTED));
        }
        return connection.get().canWrite() ? Optional.empty()
                : Optional.of(new Cap(Source.META, ActionLevel.PROPOSE_ONLY, META_WRITE_PERMISSION_MISSING));
    }

    /** Who may lower a level: an approver or a store manager, like stopping mutations. */
    public static boolean mayLower(TrustedStoreScope scope) {
        return MutationStops.mayStop(scope);
    }

    /** Who may raise a level: a store manager only. */
    public static boolean mayRaise(TrustedStoreScope scope) {
        return scope.has(StorePermission.STORE_MANAGE);
    }

    /**
     * Changes the store's level for an action type, only from the version the person saw. Raising needs
     * {@code STORE_MANAGE}, a reason and a store that is ready for the level; lowering is open to approvers too so
     * that they can always step back. Lowering takes effect for every authorization not yet issued.
     */
    @Transactional
    public Permission change(TrustedStoreScope scope, String actionType, ActionLevel level, String reason,
            int expectedVersion) {
        String type = requireType(actionType);
        if (level == null) {
            throw new ActionPermissionException(ActionPermissionException.Kind.INVALID, "LEVEL_REQUIRED");
        }
        String note = reason == null || reason.isBlank() ? null : reason.strip();
        if (note != null && note.length() > MAX_REASON) {
            throw new ActionPermissionException(ActionPermissionException.Kind.INVALID, "REASON_TOO_LONG");
        }
        Permission seen = permission(scope, type);
        boolean raising = level.compareTo(seen.level()) > 0;
        if (raising && note == null) {
            throw new ActionPermissionException(ActionPermissionException.Kind.INVALID, "REASON_REQUIRED");
        }
        // Both directions re-check the membership; raising is the riskier one and needs STORE_MANAGE (ADR-0004).
        TrustedStoreScope fresh = raising ? storeAccess.reauthorize(scope, StorePermission.STORE_MANAGE)
                : reauthorizeLower(scope);
        UUID userId = fresh.userId().orElseThrow(() -> new StoreAccessDeniedException(scope.storeId(),
                StorePermission.STORE_MANAGE));
        // Serialize changes per store and action so that two people cannot both pass the version check.
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:lock, 0))")
                .param("lock", "action-permission:" + fresh.storeId() + ":" + type)
                .query(rs -> {
                });
        Permission current = permission(fresh, type);
        if (current.version() != expectedVersion) {
            throw new ActionPermissionException(ActionPermissionException.Kind.PRECONDITION_FAILED,
                    "VERSION_CHANGED");
        }
        if (level == current.level()) {
            return current;
        }
        if (level.compareTo(current.level()) > 0) {
            requireReady(fresh, level);
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        int version = current.version() + 1;
        jdbc.sql("""
                        INSERT INTO store_action_permission (store_id, organization_id, action_type, level, reason,
                                                             version, changed_by, changed_at)
                        VALUES (:storeId, :organizationId, :type, :level, :reason, :version, :userId, :now)
                        ON CONFLICT (store_id, action_type) DO UPDATE SET level = EXCLUDED.level,
                            reason = EXCLUDED.reason, version = EXCLUDED.version, changed_by = EXCLUDED.changed_by,
                            changed_at = EXCLUDED.changed_at
                        """)
                .param("storeId", fresh.storeId())
                .param("organizationId", fresh.organizationId())
                .param("type", type)
                .param("level", level.name())
                .param("reason", note, java.sql.Types.VARCHAR)
                .param("version", version)
                .param("userId", userId)
                .param("now", Timestamp.from(now))
                .update();
        Map<String, String> details = new TreeMap<>(Map.of("actionType", type, "from", current.level().name(),
                "to", level.name(), "version", Integer.toString(version)));
        details.put("reason", note == null ? "" : note);
        audit.record(AuditEntry.inScope(fresh, "ACTION_PERMISSION_CHANGED", RESOURCE,
                fresh.storeId() + ":" + type, details));
        return permission(fresh, type);
    }

    /** Raising is refused while the store could not reach the level anyway (ADR-0025 point 2). */
    private void requireReady(TrustedStoreScope scope, ActionLevel level) {
        if (!onboarding.monitoringStarted(scope)) {
            throw new ActionPermissionException(ActionPermissionException.Kind.CONFLICT,
                    Onboarding.MONITORING_NOT_STARTED);
        }
        if (level == ActionLevel.APPROVAL_REQUIRED) {
            metaCap(meta.find(scope)).ifPresent(cap -> {
                throw new ActionPermissionException(ActionPermissionException.Kind.CONFLICT, cap.code());
            });
        }
    }

    private TrustedStoreScope reauthorizeLower(TrustedStoreScope scope) {
        try {
            return storeAccess.reauthorize(scope, StorePermission.DECISION_APPROVE);
        } catch (StoreAccessDeniedException e) {
            return storeAccess.reauthorize(scope, StorePermission.STORE_MANAGE);
        }
    }

    private static String requireType(String actionType) {
        if (actionType == null || !ACTION_TYPES.contains(actionType)) {
            throw new ActionPermissionException(ActionPermissionException.Kind.NOT_FOUND, "ACTION_TYPE_UNKNOWN");
        }
        return actionType;
    }
}
