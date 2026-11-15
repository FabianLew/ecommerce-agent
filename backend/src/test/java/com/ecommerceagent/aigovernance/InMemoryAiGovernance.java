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
public final class InMemoryAiGovernance implements AiBudgetGovernor, AiUsageLedger, AiAdmission, AiProviderLimiter {


    /** USD per million input and output tokens, by model name; a model without an entry uses {@link #DEFAULT_PRICE}. */
    public record Price(BigDecimal inputPerMillion, BigDecimal outputPerMillion) {

        BigDecimal cost(long inputTokens, long outputTokens) {
            return inputPerMillion.multiply(BigDecimal.valueOf(inputTokens))
                    .add(outputPerMillion.multiply(BigDecimal.valueOf(outputTokens)))
                    .divide(BigDecimal.valueOf(1_000_000), 6, java.math.RoundingMode.UP);
        }
    }

    public static final Price DEFAULT_PRICE = new Price(new BigDecimal("4"), new BigDecimal("20"));

    private final java.util.Map<String, Price> prices = new java.util.HashMap<>();
    private final java.util.Map<String, String> unpriced = new java.util.HashMap<>();

    /** Sets the catalog price of a model (by its name, without the provider). */
    public synchronized InMemoryAiGovernance price(String model, String inputPerMillion, String outputPerMillion) {
        prices.put(model, new Price(new BigDecimal(inputPerMillion), new BigDecimal(outputPerMillion)));
        return this;
    }

    /** The catalog cannot price this model; quotes and reservations are refused with {@code reason}. */
    public synchronized InMemoryAiGovernance unpriced(String model, String reason) {
        unpriced.put(model, reason);
        return this;
    }

    private Price priceOf(String model) {
        return prices.getOrDefault(model, DEFAULT_PRICE);
    }

    @Override
    public synchronized CostQuote quote(AiCallEstimate estimate) {
        failIfBroken("quote");
        String reason = unpriced.get(estimate.model());
        if (reason != null) {
            return new CostQuote.Unpriced(reason);
        }
        return new CostQuote.Priced(priceOf(estimate.model()).cost(estimate.estimatedInputTokens(),
                estimate.maxOutputTokens()), "USD", UUID.randomUUID(), "in-memory");
    }

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
        prices.clear();
        unpriced.clear();
    }

    @Override
    public synchronized ReservationDecision reserve(TrustedStoreScope scope, ReservationRequest request) {
        failIfBroken("reserve");
        if (denyReason != null) {
            events.add("DENIED:" + denyReason);
            return new ReservationDecision.Denied(denyReason);
        }
        String unpricedReason = unpriced.get(request.estimate().model());
        if (unpricedReason != null) {
            events.add("DENIED:" + unpricedReason);
            return new ReservationDecision.Denied(unpricedReason);
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

    private ReservationDecision.Reserved reserved(Reservation reservation, boolean repeated) {
        BigDecimal amount = priceOf(reservation.estimate.model()).cost(reservation.estimate.estimatedInputTokens(),
                reservation.estimate.maxOutputTokens());
        return new ReservationDecision.Reserved(reservation.id, amount, "USD", UUID.randomUUID(), "in-memory",
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
        BigDecimal cost = priceOf(reservation.estimate.model()).cost(usage.inputTokens(), usage.outputTokens());
        BigDecimal reserved = priceOf(reservation.estimate.model()).cost(reservation.estimate.estimatedInputTokens(),
                reservation.estimate.maxOutputTokens());
        return new Settlement("SETTLED", cost, cost.subtract(reserved).max(BigDecimal.ZERO), false);
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

    private Slot slotAnswer = Slot.GRANTED;
    private final java.util.Set<UUID> openSlots = new java.util.HashSet<>();
    private final List<String> pauses = new ArrayList<>();

    /** The answer of the next slot requests. */
    public synchronized void slot(Slot answer) {
        slotAnswer = answer;
    }

    public synchronized java.util.Set<UUID> openSlots() {
        return java.util.Set.copyOf(openSlots);
    }

    public synchronized List<String> pauses() {
        return List.copyOf(pauses);
    }

    @Override
    public synchronized Slot acquire(UUID callId, String provider, String model, long inputTokens, long outputTokens,
            java.time.Duration ttl) {
        failIfBroken("slot");
        events.add("SLOT:" + slotAnswer);
        if (slotAnswer.granted()) {
            openSlots.add(callId);
        }
        return slotAnswer;
    }

    @Override
    public synchronized void release(UUID callId) {
        openSlots.remove(callId);
    }

    @Override
    public synchronized void pause(String provider, String model, java.time.Duration retryAfter, String reason) {
        pauses.add(provider + "/" + model + ":" + retryAfter + ":" + reason);
    }
}
