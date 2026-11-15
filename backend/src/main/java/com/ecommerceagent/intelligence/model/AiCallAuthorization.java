package com.ecommerceagent.intelligence.model;

import com.ecommerceagent.identity.TrustedStoreScope;

/**
 * Re-checks, right before each paid model call, that the actor of a run may still read the store (ADR-0027 section
 * 5). A membership revoked or a store removed during a run stops the run before its next call.
 */
@FunctionalInterface
public interface AiCallAuthorization {

    /** @throws com.ecommerceagent.identity.StoreAccessDeniedException when the actor no longer has access */
    void check(TrustedStoreScope scope);
}
