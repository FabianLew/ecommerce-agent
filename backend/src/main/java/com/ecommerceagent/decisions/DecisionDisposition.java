package com.ecommerceagent.decisions;

/** What a proposal recommends (DecisionProposal v1, semantics 1.2; ADR-0012). */
public enum DecisionDisposition {
    RECOMMEND_ACTION,
    NO_ACTION,
    MONITOR,
    INSUFFICIENT_DATA,
    ESCALATE
}
