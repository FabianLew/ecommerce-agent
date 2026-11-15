package com.ecommerceagent.intelligence.model;

/**
 * One model step (document 02 §4). An implementation returns tool calls to the caller and never executes them.
 */
public interface ModelGateway {

    ModelCapabilities capabilities(ModelId model);

    /** @throws ModelFailure when the provider cannot give a turn */
    ModelTurn complete(ModelRequest request);
}
