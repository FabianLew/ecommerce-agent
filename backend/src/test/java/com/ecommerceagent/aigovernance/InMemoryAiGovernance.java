package com.ecommerceagent.aigovernance;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ecommerceagent.identity.TrustedStoreScope;

/**
 * Budget governance without a database, for runtime tests that run on a fake model: every call is admitted at a
 * fixed amount unless {@link #deny} says otherwise, and every step is recorded so a test can assert the reservation
 * life of each call. The real governance is tested on PostgreSQL ({@link AiBudgetGovernorTest},
 * {@link AiUsageLedgerTest}).
 */
public final class InMemoryAiGovernance implements AiBudgetGovernor, AiUsageLedger, AiAdmission {

    public static final BigDecimal AMOUNT = new BigDecimal("0.01");

    private final Map<String, Reservation> byKey = new LinkedHashMap<>();
    private final Map<UUID, Reservation> byId = new LinkedHashMap<>();
    private final List<String> events = new ArrayList<>();
    private String denyReason;
    private boolean stopped;
    private String broken;

    /** Makes the named step fail as a broken database would ({@code admit}, {@code reserve} or {@code markSent}). */
    public synchronized void breakStep(String step) {
        this.broken = step;
    }

    private void failIfBroken(String step) {
        if (step.equals(broken)) {
            throw new IllegalStateException("governance unavailable: " + step);
        }
    }
    private Admission admission = Admission.GRANTED;
    private final java.util.Set<UUID> leases = new java.util.LinkedHashSet<>();

    /** Turns the platform AI stop on or off: no run is admitted and no reserved call is sent while it is on. */
    public synchronized void stop(boolean on) {
        this.stopped = on;
    }

    /** The answer for the next admissions (GRANTED by default). */
    public synchronized void admission(Admission answer) {
        this.admission = answer;
    }

    /** Runs that hold a lease now. */
    public synchronized java.util.Set<UUID> leases() {
        return java.util.Set.copyOf(leases);
    }

    @Override
    public synchronized Admission admit(TrustedStoreScope scope, UUID agentRunId, String automationType,
            int maxStoreRuns, java.time.Duration ttl) {
        failIfBroken("admit");
        Admission answer = stopped ? Admission.AI_STOPPED : admission;
        events.add("ADMISSION:" + answer + ":" + maxStoreRuns);
        if (answer.granted()) {
            leases.add(agentRunId);
        }
        return answer;
    }

    @Override
    public synchronized void release(TrustedStoreScope scope, UUID agentRunId) {
        if (leases.remove(agentRunId)) {
            events.add("LEASE_RELEASED");
        }
    }

    @Override
    public synchronized boolean stopped() {
        return stopped;
    }

    /** One reservation as a test sees it. */
    public static final class Reservation {
        public final UUID id = UUID.randomUUID();
        public final String key;
        public final String automationType;
        public final UUID agentRunId;
        public final AiCallEstimate estimate;
        public String state = "RESERVED";
        public String reason;
        public ProviderUsage usage;

        Reservation(ReservationRequest request) {
            this.key = request.idempotencyKey();
            this.automationType = request.automationType();
            this.agentRunId = request.agentRunId();
            this.estimate = request.estimate();
        }
    }

    public synchronized InMemoryAiGovernance deny(String reason) {
        this.denyReason = reason;
        return this;
    }

    public synchronized List<Reservation> reservations() {
        return List.copyOf(byKey.values());
    }

    public synchronized List<String> events() {
        return List.copyOf(events);
    }

    public synchronized void reset() {
        byKey.clear();
        byId.clear();
        events.clear();
        denyReason = null;
    }

    @Override
    public synchronized ReservationDecision reserve(TrustedStoreScope scope, ReservationRequest request) {
        failIfBroken("reserve");
        if (denyReason != null) {
            events.add("DENIED:" + denyReason);
            return new ReservationDecision.Denied(denyReason);
        }
        Reservation existing = byKey.get(request.idempotencyKey());
        if (existing != null) {
            events.add("DUPLICATE:" + existing.key);
            return reserved(existing, true);
        }
        Reservation reservation = new Reservation(request);
        byKey.put(reservation.key, reservation);
        byId.put(reservation.id, reservation);
        events.add("RESERVED:" + reservation.key);
        return reserved(reservation, false);
    }

    private static ReservationDecision.Reserved reserved(Reservation reservation, boolean repeated) {
        return new ReservationDecision.Reserved(reservation.id, AMOUNT, "USD", UUID.randomUUID(), "in-memory",
                reservation.estimate.estimatedInputTokens(), reservation.estimate.maxOutputTokens(), repeated,
                reservation.state);
    }

    @Override
    public synchronized void release(TrustedStoreScope scope, UUID reservationId, String reason) {
        Reservation reservation = byId.get(reservationId);
        if (!"RESERVED".equals(reservation.state) && !"RELEASED".equals(reservation.state)) {
            throw new AiGovernanceException("AI_RESERVATION_NOT_RELEASABLE: " + reservation.state);
        }
        reservation.state = "RELEASED";
        events.add("RELEASED:" + reservation.key);
    }

    @Override
    public synchronized SendDecision markSent(TrustedStoreScope scope, UUID reservationId) {
        failIfBroken("markSent");
        Reservation reservation = byId.get(reservationId);
        if (!"RESERVED".equals(reservation.state)) {
            return "RELEASED".equals(reservation.state) ? SendDecision.RELEASED : SendDecision.ALREADY_ATTEMPTED;
        }
        if (stopped) {
            reservation.state = "RELEASED";
            events.add("STOPPED:" + reservation.key);
            return SendDecision.STOPPED;
        }
        reservation.state = "IN_PROGRESS";
        events.add("SENT:" + reservation.key);
        return SendDecision.SEND;
    }

    @Override
    public synchronized Settlement settle(TrustedStoreScope scope, UUID reservationId, ProviderUsage usage) {
        Reservation reservation = byId.get(reservationId);
        reservation.state = "SETTLED";
        reservation.usage = usage;
        events.add("SETTLED:" + reservation.key + ":" + usage.inputTokens() + "/" + usage.outputTokens());
        BigDecimal cost = BigDecimal.valueOf(usage.inputTokens() + usage.outputTokens(), 6);
        return new Settlement("SETTLED", cost, cost.subtract(AMOUNT).max(BigDecimal.ZERO), false);
    }

    @Override
    public synchronized void markUnknown(TrustedStoreScope scope, UUID reservationId, String reason) {
        Reservation reservation = byId.get(reservationId);
        if ("IN_PROGRESS".equals(reservation.state)) {
            reservation.state = "UNKNOWN";
            reservation.reason = reason;
            events.add("UNKNOWN:" + reservation.key + ":" + reason);
        }
    }

    @Override
    public Settlement reconcile(TrustedStoreScope scope, UUID reservationId, ReconciliationOutcome outcome,
            BigDecimal cost, String reason) {
        throw new UnsupportedOperationException("Reconciliation is tested on PostgreSQL");
    }
}
