package com.ecommerceagent.intelligence.agent;

/** How a run ended (ADR-0016 point 5). Only SUCCEEDED carries an output the backend may submit. */
public enum AgentRunStatus {
    RUNNING,
    SUCCEEDED,
    LIMIT_EXCEEDED,
    OUTPUT_INVALID,
    MODEL_FAILED,
    REFUSED,
    /** A hook stopped the run. */
    STOPPED,
    /** The run succeeded, but the backend's validation rejected its proposal. */
    OUTPUT_REJECTED
}
