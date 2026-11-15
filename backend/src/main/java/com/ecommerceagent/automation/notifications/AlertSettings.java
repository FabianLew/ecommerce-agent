package com.ecommerceagent.automation.notifications;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import com.ecommerceagent.identity.AuditEntry;
import com.ecommerceagent.identity.AuditTrail;
import com.ecommerceagent.identity.RegisteredUser;
import com.ecommerceagent.identity.StoreAccess;
import com.ecommerceagent.identity.StoreAccessDeniedException;
import com.ecommerceagent.identity.StorePermission;
import com.ecommerceagent.identity.StoreRole;
import com.ecommerceagent.identity.TrustedStoreScope;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who is told by e-mail (ADR-0026 point 5): the store's owners and its optional deputy. The deputy is one member who
 * may approve decisions, set or cleared from the version seen by a member with {@code STORE_MANAGE}. A deputy who
 * lost the permission or the membership gets nothing; the setting shows as inactive until it is changed.
 */
@Service
@NamedInterface("notifications")
public class AlertSettings {

    private static final String RESOURCE = "store_alert_setting";

    /** The store's setting; version 0 until the first change. */
    public record Setting(UUID deputyUserId, boolean deputyActive, int version, UUID changedBy, Instant changedAt) {
    }

    private final StoreAccess storeAccess;
    private final AuditTrail audit;
    private final JdbcClient jdbc;
    private final Clock clock;

    AlertSettings(StoreAccess storeAccess, AuditTrail audit, JdbcClient jdbc, Clock clock) {
        this.storeAccess = storeAccess;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Setting setting(TrustedStoreScope scope) {
        return stored(scope).map(row -> new Setting(row.deputy(), row.deputy() != null && approver(scope, row.deputy()),
                row.version(), row.changedBy(), row.changedAt()))
                .orElseGet(() -> new Setting(null, false, 0, null, null));
    }

    /** E-mail recipients of every notification: owners, then the deputy while they may approve decisions. */
    public List<RegisteredUser> recipients(TrustedStoreScope scope) {
        List<RegisteredUser> recipients = new ArrayList<>(storeAccess.membersWithRole(scope, StoreRole.OWNER));
        stored(scope).map(Row::deputy).filter(Objects::nonNull).ifPresent(deputy -> {
            if (recipients.stream().noneMatch(user -> user.id().equals(deputy))) {
                storeAccess.membersWith(scope, StorePermission.DECISION_APPROVE).stream()
                        .filter(user -> user.id().equals(deputy)).findFirst().ifPresent(recipients::add);
            }
        });
        return List.copyOf(recipients);
    }

    /** Members who could be the deputy: those who may approve decisions and are not owners (owners get e-mails). */
    public List<RegisteredUser> candidates(TrustedStoreScope scope) {
        List<UUID> owners = storeAccess.membersWithRole(scope, StoreRole.OWNER).stream().map(RegisteredUser::id)
                .toList();
        return storeAccess.membersWith(scope, StorePermission.DECISION_APPROVE).stream()
                .filter(user -> !owners.contains(user.id())).toList();
    }

    /** Sets ({@code deputy} not null) or clears the deputy; the caller must have seen {@code expectedVersion}. */
    @Transactional
    public Setting change(TrustedStoreScope scope, UUID deputy, int expectedVersion) {
        TrustedStoreScope fresh = storeAccess.reauthorize(scope, StorePermission.STORE_MANAGE);
        UUID userId = fresh.userId().orElseThrow(() -> new StoreAccessDeniedException(scope.storeId(),
                StorePermission.STORE_MANAGE));
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:lock, 0))")
                .param("lock", "alert-setting:" + fresh.storeId())
                .query(rs -> {
                });
        Setting current = setting(fresh);
        if (current.version() != expectedVersion) {
            throw new AlertSettingException(AlertSettingException.Kind.PRECONDITION_FAILED, "VERSION_CHANGED");
        }
        if (deputy != null && !approver(fresh, deputy)) {
            throw new AlertSettingException(AlertSettingException.Kind.INVALID, "DEPUTY_NOT_APPROVER");
        }
        if (Objects.equals(deputy, current.deputyUserId())) {
            return current;
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        int version = current.version() + 1;
        jdbc.sql("""
                        INSERT INTO store_alert_setting (store_id, organization_id, deputy_user_id, version, changed_by,
                                                         changed_at)
                        VALUES (:storeId, :organizationId, :deputy, :version, :userId, :now)
                        ON CONFLICT (store_id) DO UPDATE SET deputy_user_id = EXCLUDED.deputy_user_id,
                            version = EXCLUDED.version, changed_by = EXCLUDED.changed_by,
                            changed_at = EXCLUDED.changed_at
                        """)
                .param("storeId", fresh.storeId())
                .param("organizationId", fresh.organizationId())
                .param("deputy", deputy, java.sql.Types.OTHER)
                .param("version", version)
                .param("userId", userId)
                .param("now", Timestamp.from(now))
                .update();
        Map<String, String> details = new TreeMap<>();
        details.put("from", current.deputyUserId() == null ? "" : current.deputyUserId().toString());
        details.put("to", deputy == null ? "" : deputy.toString());
        details.put("version", Integer.toString(version));
        audit.record(AuditEntry.inScope(fresh, "ALERT_DEPUTY_CHANGED", RESOURCE, fresh.storeId().toString(),
                details));
        return setting(fresh);
    }

    private boolean approver(TrustedStoreScope scope, UUID userId) {
        return storeAccess.membersWith(scope, StorePermission.DECISION_APPROVE).stream()
                .anyMatch(user -> user.id().equals(userId));
    }

    private record Row(UUID deputy, int version, UUID changedBy, Instant changedAt) {
    }

    private Optional<Row> stored(TrustedStoreScope scope) {
        return jdbc.sql("""
                        SELECT deputy_user_id, version, changed_by, changed_at FROM store_alert_setting
                        WHERE organization_id = :organizationId AND store_id = :storeId
                        """)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .query((rs, n) -> new Row(rs.getObject("deputy_user_id", UUID.class), rs.getInt("version"),
                        rs.getObject("changed_by", UUID.class), rs.getTimestamp("changed_at").toInstant()))
                .optional();
    }
}
