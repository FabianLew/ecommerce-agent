package com.ecommerceagent.automation.execution;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import com.ecommerceagent.automation.guardian.DecisionApprovals;
import com.ecommerceagent.commerce.AdMapping;
import com.ecommerceagent.commerce.AdRepository;
import com.ecommerceagent.identity.AuditEntry;
import com.ecommerceagent.identity.AuditTrail;
import com.ecommerceagent.identity.StoreAccess;
import com.ecommerceagent.identity.StorePermission;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.integration.MetaAdActions;
import com.ecommerceagent.metrics.CanonicalJson;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Pauses one Meta ad for an {@link AuthorizedAction} (ADR-0020 point 6, document 03 §9). Every attempt starts from
 * the ledger: a finished row is returned as it is, an uncertain one is reconciled by reading the ad, and a write is
 * sent at most once per admitted attempt, under the target's lease, after a fresh read matched the authorization.
 */
@Service
@NamedInterface("execution")
public class AdPauseExecutor {

    /** The ledger state after an attempt; {@code retryable} when a later attempt may succeed (busy, unreachable). */
    public record Outcome(UUID executionId, ExecutionStatus status, String code, boolean retryable) {
    }

    static final String PAUSED = "PAUSED";
    static final String ACTIVE = "ACTIVE";
    private static final String RESOURCE = "action_execution";

    private final ExecutionAuthority authority;
    private final ExecutionLedger ledger;
    private final MetaAdActions meta;
    private final AdRepository ads;
    private final DecisionApprovals approvals;
    private final StoreAccess storeAccess;
    private final MutationStops stops;
    private final AuditTrail audit;
    private final Clock clock;

    AdPauseExecutor(ExecutionAuthority authority, ExecutionLedger ledger, MetaAdActions meta, AdRepository ads,
            DecisionApprovals approvals, StoreAccess storeAccess, MutationStops stops, AuditTrail audit,
            Clock clock) {
        this.authority = authority;
        this.ledger = ledger;
        this.meta = meta;
        this.ads = ads;
        this.approvals = approvals;
        this.storeAccess = storeAccess;
        this.stops = stops;
        this.audit = audit;
        this.clock = clock;
    }

    public Outcome execute(TrustedStoreScope scope, UUID actionId) {
        AuthorizedAction action = authority.find(scope, actionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown authorized action " + actionId));
        ActionExecution execution = ledger.plan(scope, action);
        if (execution.status().terminal() || execution.status() == ExecutionStatus.APPLIED) {
            return outcome(execution, false);
        }
        String owner = "executor:" + UUID.randomUUID();
        if (!ledger.takeLease(scope, action.targetType(), action.targetId(), owner)) {
            return new Outcome(execution.id(), execution.status(), "TARGET_BUSY", true);
        }
        try {
            return attempt(scope, action, execution, owner);
        } finally {
            ledger.releaseLease(scope, action.targetType(), action.targetId(), owner);
        }
    }

    /**
     * One verification read: {@code VERIFIED} when the ad's own status is {@code PAUSED}; on the last read anything
     * else becomes {@code UNKNOWN} for a person to resolve. Never writes.
     */
    public Outcome verify(TrustedStoreScope scope, UUID executionId, boolean last) {
        ActionExecution execution = ledger.find(scope, executionId).orElseThrow();
        if (execution.status() != ExecutionStatus.APPLIED && execution.status() != ExecutionStatus.UNKNOWN) {
            return outcome(execution, false);
        }
        AuthorizedAction action = authority.find(scope, execution.authorizedActionId()).orElseThrow();
        MetaAdActions.Read read = meta.read(scope, action.targetExternalId());
        if (read.ok() && PAUSED.equals(read.state().status())) {
            ActionExecution verified = ledger.move(scope, executionId, ExecutionStatus.VERIFIED,
                    execution.resultCode(), null);
            record(scope, verified, action, Map.of("effectiveStatus", read.state().effectiveStatus()));
            return outcome(verified, false);
        }
        if (!last) {
            return new Outcome(executionId, execution.status(), read.ok() ? "NOT_YET_PAUSED" : read.errorCode(),
                    false);
        }
        ActionExecution unknown = ledger.move(scope, executionId, ExecutionStatus.UNKNOWN,
                read.ok() ? "PAUSE_NOT_CONFIRMED" : read.errorCode(), null);
        record(scope, unknown, action, Map.of());
        return outcome(unknown, false);
    }

    private Outcome attempt(TrustedStoreScope scope, AuthorizedAction action, ActionExecution execution,
            String owner) {
        Instant now = clock.instant();
        boolean uncertain = execution.status() == ExecutionStatus.UNKNOWN
                || (execution.status() == ExecutionStatus.IN_PROGRESS && execution.writeSent());
        MetaAdActions.Read read = meta.read(scope, action.targetExternalId());
        if (!read.ok()) {
            boolean transientError = "META_UNREACHABLE".equals(read.errorCode())
                    || "META_RATE_LIMITED".equals(read.errorCode());
            if (transientError || uncertain) {
                return new Outcome(execution.id(), execution.status(), read.errorCode(), transientError);
            }
            return finish(scope, action, execution, ExecutionStatus.FAILED, read.errorCode(), null, false);
        }
        MetaAdActions.AdState state = read.state();
        if (uncertain && PAUSED.equals(state.status())) {
            // The earlier write reached Meta before the failure: confirm the state, do not write again.
            return finish(scope, action, execution, ExecutionStatus.APPLIED, "EFFECT_FOUND_ON_RECONCILIATION", null,
                    false);
        }
        // From here on a new write would be sent: everything that permits it is checked again.
        Optional<String> blocked = writeBlocker(scope, action, now);
        if (blocked.isPresent()) {
            ExecutionStatus next = uncertain ? ExecutionStatus.UNKNOWN : ExecutionStatus.FAILED;
            return finish(scope, action, execution, next, blocked.get(), null, false);
        }
        if (!uncertain && PAUSED.equals(state.status())) {
            // Already in the requested state: no change of ours, nothing attributed to the operator.
            return finish(scope, action, execution, ExecutionStatus.NOOP, "ALREADY_PAUSED", null, false);
        }
        Optional<String> conflict = conflict(scope, action, state);
        if (conflict.isPresent()) {
            ExecutionStatus next = uncertain ? ExecutionStatus.UNKNOWN : ExecutionStatus.CONFLICT;
            return finish(scope, action, execution, next, conflict.get(), null, false);
        }
        ObjectNode preState = JsonNodeFactory.instance.objectNode().put("status", state.status())
                .put("effectiveStatus", state.effectiveStatus()).put("creative", state.creativeFingerprint());
        String requestHash = CanonicalJson.hash(JsonNodeFactory.instance.objectNode()
                .put("ad", action.targetExternalId()).put("status", PAUSED));
        ExecutionLedger.Admission admission = ledger.admit(scope, execution.id(), preState, requestHash);
        if (admission != ExecutionLedger.Admission.ADMITTED) {
            ExecutionStatus next = admission == ExecutionLedger.Admission.COOLDOWN ? ExecutionStatus.CONFLICT
                    : ExecutionStatus.FAILED;
            return finish(scope, action, ledger.find(scope, execution.id()).orElseThrow(), next, admission.name(),
                    null, false);
        }
        if (!ledger.holdsLease(scope, action.targetType(), action.targetId(), owner)) {
            return new Outcome(execution.id(), ExecutionStatus.IN_PROGRESS, "LEASE_LOST", true);
        }
        ledger.markWriteSent(scope, execution.id());
        MetaAdActions.Write write = meta.pause(scope, action.targetExternalId());
        ActionExecution current = ledger.find(scope, execution.id()).orElseThrow();
        return switch (write.outcome()) {
            case ACCEPTED -> finish(scope, action, current, ExecutionStatus.APPLIED, null, null, false);
            case UNCERTAIN -> finish(scope, action, current, ExecutionStatus.UNKNOWN, write.code(), null, false);
            case REJECTED -> finish(scope, action, current, ExecutionStatus.FAILED, write.code(), null, true);
        };
    }

    /** What forbids sending a write now: an expired authorization, the mutation stop, or the approver's access. */
    private Optional<String> writeBlocker(TrustedStoreScope scope, AuthorizedAction action, Instant now) {
        if (!action.validAt(now)) {
            return Optional.of("AUTHORIZATION_EXPIRED");
        }
        Optional<String> stopped = stops.blocker(scope);
        if (stopped.isPresent()) {
            return stopped;
        }
        UUID approver = approvals.find(scope, action.approvalId()).orElseThrow().decidedBy();
        boolean allowed = storeAccess.scopeFor(approver, scope.storeId())
                .filter(person -> person.has(StorePermission.DECISION_APPROVE)).isPresent();
        return allowed ? Optional.empty() : Optional.of("APPROVER_ACCESS_REVOKED");
    }

    /** A material change since the authorization: another creative, another mapping, or the ad not running. */
    private Optional<String> conflict(TrustedStoreScope scope, AuthorizedAction action,
            MetaAdActions.AdState state) {
        if (!ACTIVE.equals(state.status())) {
            return Optional.of("TARGET_STATE_CHANGED");
        }
        if (action.creativeFingerprint() != null && !action.creativeFingerprint().equals(state.creativeFingerprint())) {
            return Optional.of("CREATIVE_CHANGED");
        }
        Optional<AdMapping> mapping = ads.mappingOfAd(scope, action.targetId());
        if (mapping.isEmpty() || mapping.get().version() != action.mappingVersion()) {
            return Optional.of("MAPPING_CHANGED");
        }
        return Optional.empty();
    }

    private Outcome finish(TrustedStoreScope scope, AuthorizedAction action, ActionExecution execution,
            ExecutionStatus next, String code, String providerRequestId, boolean nothingChanged) {
        ActionExecution moved = ledger.move(scope, execution.id(), next, code, providerRequestId, nothingChanged);
        record(scope, moved, action, Map.of());
        return outcome(moved, false);
    }

    private void record(TrustedStoreScope scope, ActionExecution execution, AuthorizedAction action,
            Map<String, String> extra) {
        Map<String, String> details = new TreeMap<>(extra);
        details.put("status", execution.status().name());
        details.put("target", action.targetExternalId());
        details.put("proposalId", action.proposalId().toString());
        if (execution.resultCode() != null) {
            details.put("code", execution.resultCode());
        }
        audit.record(AuditEntry.inScope(scope, execution.status() == ExecutionStatus.VERIFIED ? "ACTION_VERIFIED"
                : "ACTION_EXECUTED", RESOURCE, execution.id().toString(), details));
    }

    private static Outcome outcome(ActionExecution execution, boolean retryable) {
        return new Outcome(execution.id(), execution.status(), execution.resultCode(), retryable);
    }
}
