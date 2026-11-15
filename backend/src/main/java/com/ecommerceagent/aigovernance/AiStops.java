package com.ecommerceagent.aigovernance;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ecommerceagent.identity.AuditEntry;
import com.ecommerceagent.identity.AuditTrail;
import com.ecommerceagent.identity.PlatformOperator;
import com.ecommerceagent.identity.PlatformOperators;
import com.ecommerceagent.identity.TrustedStoreScope;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scoped AI stops (ADR-0027 section 12): platform operators stop and resume AI for one store, for one automation on
 * every store, or for one automation on one store. Each stop is independent of the others and of the platform stop
 * (which only the administration role changes); none is switched on automatically. Every decision is appended to
 * {@code ai_stop_change}, recorded with the operator and audited. The database enforces the stops at run admission
 * and before every paid call.
 */
@Service
public class AiStops {

    public static final String STOPPED = "AI_STOPPED_SCOPED";
    public static final String RESUMED = "AI_RESUMED_SCOPED";
    static final String RESOURCE = "ai_stop";

    private final JdbcClient jdbc;
    private final PlatformOperators operators;
    private final Recorder recorder;

    AiStops(JdbcClient jdbc, PlatformOperators operators, Recorder recorder) {
        this.jdbc = jdbc;
        this.operators = operators;
        this.recorder = recorder;
    }

    /** The targets stopped now, newest decision first. */
    public List<AiStopChange> active(PlatformOperator operator) {
        operators.require(operator.userId());
        return changes(true, 1000);
    }

    /** Every decision, newest first. */
    public List<AiStopChange> history(PlatformOperator operator, int limit) {
        operators.require(operator.userId());
        return changes(false, limit);
    }

    /**
     * Stops or resumes AI for a target: a store, an automation ({@code storeId} null), or both. Returns whether the
     * state changed; a decision that matches the current state writes nothing.
     */
    public boolean change(PlatformOperator operator, UUID storeId, String automationType, boolean stopped,
            String reason) {
        String note = reason == null ? null : reason.strip();
        if ((storeId == null && automationType == null) || note == null || note.isEmpty() || note.length() > 500
                || (automationType != null && !automationType.matches("[a-z0-9-]{1,64}"))) {
            throw new AiReconciliationException(AiReconciliationException.Kind.INVALID, "AI_STOP_INVALID", null);
        }
        if (storeId == null) {
            operators.require(operator.userId());
            return translated(() -> recorder.changePlatformWide(operator, automationType, stopped, note));
        }
        TrustedStoreScope scope = operators.operationsScope(operator, storeId)
                .orElseThrow(() -> new AiReconciliationException(AiReconciliationException.Kind.NOT_FOUND,
                        "AI_STOP_TARGET_NOT_FOUND", null));
        return translated(() -> recorder.changeInStore(scope, operator, automationType, stopped, note));
    }

    /** How many targets are stopped now, for the gauge. */
    int activeCount() {
        return changes(true, 1000).size();
    }

    private List<AiStopChange> changes(boolean activeOnly, int limit) {
        return jdbc.sql("SELECT * FROM ai_stop_changes(?, ?)")
                .params(activeOnly, limit)
                .query((rs, n) -> new AiStopChange(rs.getLong("id"), rs.getObject("organization_id", UUID.class),
                        rs.getObject("store_id", UUID.class), rs.getString("automation_type"),
                        rs.getBoolean("stopped"), rs.getString("reason"), rs.getString("changed_by"),
                        rs.getTimestamp("changed_at").toInstant()))
                .list();
    }

    private static boolean translated(java.util.function.Supplier<Boolean> work) {
        try {
            return work.get();
        } catch (DataAccessException e) {
            String message = String.valueOf(e.getMostSpecificCause().getMessage());
            if (message.contains("AI_STOP_TARGET_NOT_FOUND") || message.contains("ai_stop_change_automation_type_fkey")
                    || message.contains("foreign key")) {
                throw new AiReconciliationException(AiReconciliationException.Kind.NOT_FOUND,
                        "AI_STOP_TARGET_NOT_FOUND", e);
            }
            if (message.contains("AI_STOP_INVALID")) {
                throw new AiReconciliationException(AiReconciliationException.Kind.INVALID, "AI_STOP_INVALID", e);
            }
            throw e;
        }
    }

    /** The decision and its audit entry in one transaction. */
    @Service
    static class Recorder {

        private final JdbcClient jdbc;
        private final AuditTrail audit;

        Recorder(JdbcClient jdbc, AuditTrail audit) {
            this.jdbc = jdbc;
            this.audit = audit;
        }

        @Transactional
        public boolean changeInStore(TrustedStoreScope scope, PlatformOperator operator, String automationType,
                boolean stopped, String reason) {
            boolean changed = write(scope.organizationId(), scope.storeId(), automationType, stopped, reason,
                    operator);
            if (changed) {
                audit.record(new AuditEntry(stopped ? STOPPED : RESUMED, scope.organizationId(), scope.storeId(),
                        operator.userId(), null, RESOURCE, scope.storeId() + ":" + target(automationType),
                        details(automationType, reason, operator)));
            }
            return changed;
        }

        @Transactional
        public boolean changePlatformWide(PlatformOperator operator, String automationType, boolean stopped,
                String reason) {
            boolean changed = write(null, null, automationType, stopped, reason, operator);
            if (changed) {
                audit.record(new AuditEntry(stopped ? STOPPED : RESUMED, null, null, operator.userId(), null,
                        RESOURCE, "*:" + automationType, details(automationType, reason, operator)));
            }
            return changed;
        }

        private boolean write(UUID organizationId, UUID storeId, String automationType, boolean stopped,
                String reason, PlatformOperator operator) {
            return "CHANGED".equals(jdbc.sql("SELECT ai_change_stop(?::uuid, ?::uuid, ?, ?, ?, ?)")
                    .params(organizationId, storeId, automationType, stopped, reason, operator.recordedAs())
                    .query(String.class).single());
        }

        private static String target(String automationType) {
            return automationType == null ? "*" : automationType;
        }

        /** The reason stays in {@code ai_stop_change}; the audit entry carries identifiers only. */
        private static Map<String, String> details(String automationType, String reason, PlatformOperator operator) {
            Map<String, String> details = new LinkedHashMap<>();
            details.put("automationType", target(automationType));
            details.put("changedBy", operator.recordedAs());
            return details;
        }
    }
}
