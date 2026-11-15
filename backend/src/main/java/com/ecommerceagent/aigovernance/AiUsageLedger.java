package com.ecommerceagent.aigovernance;

import java.math.BigDecimal;
import java.util.UUID;

import com.ecommerceagent.identity.TrustedStoreScope;

/**
 * The life of a reservation after it admits a call (ADR-0027 sections 4.4 and 4.5), recorded in the usage ledger.
 * The caller does, in this order:
 * <ol>
 *   <li>{@link #markSent} and commit, and send only on {@link SendDecision#SEND};</li>
 *   <li>{@link #settle} with the provider's usage when the answer is read, or {@link #markUnknown} when the call may
 *       have reached the provider but its result is unknown (timeout, dropped connection, unreadable answer).</li>
 * </ol>
 * A worker that dies between the steps leaves the reservation to the sweep: {@code RESERVED} is released,
 * {@code IN_PROGRESS} becomes {@code UNKNOWN}. Nothing is assumed to happen exactly once: every step can be repeated
 * and changes nothing the second time. Costs are computed in the database from the reservation's own price.
 */
public interface AiUsageLedger {

    SendDecision markSent(TrustedStoreScope scope, UUID reservationId);

    /** Records the actual cost in full; the same usage again returns the first settlement and charges nothing. */
    Settlement settle(TrustedStoreScope scope, UUID reservationId, ProviderUsage usage);

    /** The call was sent and its result is unknown; the reservation keeps counting until it is reconciled. */
    void markUnknown(TrustedStoreScope scope, UUID reservationId, String reason);

    /**
     * Closes an {@code UNKNOWN} reservation, recorded with the scope's actor. {@code cost} is required for
     * {@link ReconciliationOutcome#CHARGED} and must be null otherwise. Not exposed to store users: deciding that a
     * call was not charged returns platform money, so only platform operators may reach it (ADR-0027 section 15).
     */
    Settlement reconcile(TrustedStoreScope scope, UUID reservationId, ReconciliationOutcome outcome, BigDecimal cost,
            String reason);
}
