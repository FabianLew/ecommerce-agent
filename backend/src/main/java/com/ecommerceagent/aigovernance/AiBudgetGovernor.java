package com.ecommerceagent.aigovernance;

import java.util.UUID;

import com.ecommerceagent.identity.TrustedStoreScope;

/**
 * Admits paid model calls (ADR-0027 section 2). {@link #reserve} reserves the call's conservative maximum cost
 * against every budget that applies to the store, atomically; {@link #release} returns a reservation whose call is
 * known not to have been sent. The scope must be the one bound to the current thread (it is what the database
 * enforces); the governor never takes a store from anywhere else.
 *
 * <p>Any failure to decide throws {@link AiGovernanceException} or a data access exception: the caller sends nothing.
 */
public interface AiBudgetGovernor {

    ReservationDecision reserve(TrustedStoreScope scope, ReservationRequest request);

    /** Releases a reservation still {@code RESERVED}; a second release changes nothing. */
    void release(TrustedStoreScope scope, UUID reservationId, String reason);
}
