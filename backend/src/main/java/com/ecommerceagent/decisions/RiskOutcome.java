package com.ecommerceagent.decisions;

public enum RiskOutcome {
    AT_RISK,
    NOT_AT_RISK,
    /** No current, complete forecast: ends a series of risk observations. */
    UNKNOWN
}
