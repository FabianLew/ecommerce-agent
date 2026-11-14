package com.ecommerceagent.decisions;

/** An action a proposal recommends. Each type has its own typed parameters and its own policy (ADR-0012). */
public sealed interface ActionSpec permits PauseAd {

    /** The contract name of the action type, e.g. {@code PAUSE_AD}. */
    String type();
}
