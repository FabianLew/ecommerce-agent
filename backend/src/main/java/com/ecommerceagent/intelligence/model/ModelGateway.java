package com.ecommerceagent.intelligence.model;

/**
 * One model step (document 02 §4), implemented by a provider adapter. An implementation returns tool calls to the
 * caller and never executes them. Business code never calls an adapter: the only path to a paid model is
 * {@link GovernedModelGateway} (ADR-0027 section 10).
 */
public interface ModelGateway {

    ModelCapabilities capabilities(ModelId model);

    /** Whether a call can be attempted at all (e.g. the provider is configured); checked before any reservation. */
    default boolean available(ModelId model) {
        return true;
    }

    /** @throws ModelFailure when the provider cannot give a turn */
    ModelTurn complete(ModelRequest request);
}
