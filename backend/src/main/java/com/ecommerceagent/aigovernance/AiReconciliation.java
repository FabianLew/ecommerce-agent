package com.ecommerceagent.aigovernance;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
 * Platform operators close uncertain AI calls (ADR-0027 sections 4.5 and 15): reconcile an {@code UNKNOWN}
 * reservation with what the provider shows, or correct a conservative reconciliation once the provider's data
 * arrives. Every decision re-checks the operator, is recorded with who made it, and is audited in the store it
 * concerns. Store users never reach this: returning a reservation to the budgets is a decision about platform money.
 */
@Service
public class AiReconciliation {

    public static final String RECONCILED = "AI_RESERVATION_RECONCILED";
    public static final String CORRECTED = "AI_USAGE_CORRECTED";
    static final String RESOURCE = "ai_budget_reservation";

    /** How far back a conservative reconciliation is still listed as correctable. */
    static final Duration CORRECTION_WINDOW = Duration.ofDays(90);

    private final JdbcClient jdbc;
    private final PlatformOperators operators;
    private final Recorder recorder;
    private final Clock clock;

    AiReconciliation(JdbcClient jdbc, PlatformOperators operators, Recorder recorder, Clock clock) {
        this.jdbc = jdbc;
        this.operators = operators;
        this.recorder = recorder;
        this.clock = clock;
    }

    /** The uncertain calls of every store, oldest first. */
    public List<UncertainReservation> uncertain(PlatformOperator operator, int limit) {
        operators.require(operator.userId());
        return uncertain(limit);
    }

    List<UncertainReservation> uncertain(int limit) {
        return jdbc.sql("SELECT * FROM ai_uncertain_reservations(?, ?)")
                .params(Timestamp.from(clock.instant().minus(CORRECTION_WINDOW)), limit)
                .query((rs, n) -> new UncertainReservation(rs.getObject("reservation_id", UUID.class),
                        rs.getObject("organization_id", UUID.class), rs.getObject("store_id", UUID.class),
                        rs.getString("automation_type"), rs.getString("provider"), rs.getString("model"),
                        rs.getString("provider_request_id"), rs.getString("state"), rs.getString("state_reason"),
                        rs.getBigDecimal("reserved_amount"), rs.getBigDecimal("recorded_cost"),
                        rs.getString("currency"), instant(rs.getTimestamp("sent_at")),
                        instant(rs.getTimestamp("unknown_at")), instant(rs.getTimestamp("settled_at"))))
                .list();
    }

    /** Closes an {@code UNKNOWN} reservation with the operator's decision. */
    public Settlement reconcile(PlatformOperator operator, UUID reservationId, ReconciliationOutcome outcome,
            BigDecimal cost, String reason) {
        TrustedStoreScope scope = scopeOf(operator, reservationId);
        return translated(() -> recorder.reconcile(scope, operator, reservationId, outcome, cost, reason));
    }

    /** Corrects a conservative reconciliation with what the provider confirmed. */
    public Adjustment correct(PlatformOperator operator, UUID reservationId, ReconciliationOutcome outcome,
            BigDecimal cost, String reason) {
        if (outcome == ReconciliationOutcome.CONSERVATIVE) {
            throw new AiReconciliationException(AiReconciliationException.Kind.INVALID,
                    "A correction is NOT_CHARGED or CHARGED", null);
        }
        TrustedStoreScope scope = scopeOf(operator, reservationId);
        return translated(() -> recorder.correct(scope, operator, reservationId, outcome, cost, reason));
    }

    private TrustedStoreScope scopeOf(PlatformOperator operator, UUID reservationId) {
        operators.require(operator.userId());
        record Location(UUID organizationId, UUID storeId) {
        }
        Location location = jdbc.sql("SELECT organization_id, store_id FROM ai_reservation_location(?::uuid)")
                .param(reservationId)
                .query((rs, n) -> new Location(rs.getObject("organization_id", UUID.class),
                        rs.getObject("store_id", UUID.class)))
                .optional()
                .orElseThrow(() -> new AiReconciliationException(AiReconciliationException.Kind.NOT_FOUND,
                        "AI_RESERVATION_NOT_FOUND", null));
        return operators.operationsScope(operator, location.organizationId(), location.storeId())
                .orElseThrow(() -> new AiReconciliationException(AiReconciliationException.Kind.NOT_FOUND,
                        "AI_RESERVATION_NOT_FOUND", null));
    }

    static <T> T translated(java.util.function.Supplier<T> work) {
        try {
            return work.get();
        } catch (IllegalArgumentException e) {
            throw new AiReconciliationException(AiReconciliationException.Kind.INVALID, e.getMessage(), e);
        } catch (DataAccessException e) {
            String message = String.valueOf(e.getMostSpecificCause().getMessage());
            AiReconciliationException.Kind kind = message.contains("AI_RESERVATION_NOT_FOUND")
                    ? AiReconciliationException.Kind.NOT_FOUND
                    : message.contains("AI_RECONCILIATION_INVALID") ? AiReconciliationException.Kind.INVALID
                    : message.contains("AI_SETTLEMENT_CONFLICT") || message.contains("NOT_RECONCILABLE")
                            || message.contains("NOT_ADJUSTABLE") ? AiReconciliationException.Kind.CONFLICT
                    : null;
            if (kind == null) {
                throw e;
            }
            throw new AiReconciliationException(kind, firstLine(message), e);
        }
    }

    private static String firstLine(String message) {
        String line = message.lines().findFirst().orElse(message);
        return line.startsWith("ERROR: ") ? line.substring(7) : line;
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    /** The write and its audit entry in one transaction, on the store's bound connection. */
    @Service
    static class Recorder {

        private final AiUsageLedger ledger;
        private final AuditTrail audit;

        Recorder(AiUsageLedger ledger, AuditTrail audit) {
            this.ledger = ledger;
            this.audit = audit;
        }

        @Transactional
        public Settlement reconcile(TrustedStoreScope scope, PlatformOperator operator, UUID reservationId,
                ReconciliationOutcome outcome, BigDecimal cost, String reason) {
            Settlement settlement = ledger.reconcile(scope, operator, reservationId, outcome, cost, reason);
            if (!settlement.repeated()) {
                Map<String, String> details = new LinkedHashMap<>();
                details.put("outcome", settlement.outcome());
                details.put("cost", settlement.cost().toPlainString());
                details.put("recordedBy", operator.recordedAs());
                audit.record(entry(scope, operator, RECONCILED, reservationId, details));
            }
            return settlement;
        }

        @Transactional
        public Adjustment correct(TrustedStoreScope scope, PlatformOperator operator, UUID reservationId,
                ReconciliationOutcome outcome, BigDecimal cost, String reason) {
            Adjustment adjustment = ledger.adjust(scope, operator, reservationId, outcome, cost, reason);
            if (!adjustment.repeated()) {
                Map<String, String> details = new LinkedHashMap<>();
                details.put("outcome", adjustment.outcome());
                details.put("cost", adjustment.cost().toPlainString());
                details.put("delta", adjustment.delta().toPlainString());
                details.put("recordedBy", operator.recordedAs());
                audit.record(entry(scope, operator, CORRECTED, reservationId, details));
            }
            return adjustment;
        }

        private static AuditEntry entry(TrustedStoreScope scope, PlatformOperator operator, String action,
                UUID reservationId, Map<String, String> details) {
            return new AuditEntry(action, scope.organizationId(), scope.storeId(), operator.userId(), null, RESOURCE,
                    reservationId.toString(), details);
        }
    }
}
