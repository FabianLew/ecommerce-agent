package com.ecommerceagent.decisions;

/** Life cycle of a stored proposal; content never changes, only the status (ADR-0012). */
public enum ProposalStatus {
    PROPOSED,
    APPROVED,
    REJECTED,
    EXPIRED,
    SUPERSEDED
}
