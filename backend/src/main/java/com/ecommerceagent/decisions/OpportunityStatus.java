package com.ecommerceagent.decisions;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The life cycle of a problem (ADR-0011). Proposals and executions have their own state machines, so this
 * status never says whether a proposal was approved or an action applied.
 */
public enum OpportunityStatus {
    OPEN,
    ANALYZING,
    AWAITING_DECISION,
    MONITORING,
    WAITING_DATA,
    ACTING,
    RESOLVED,
    CLOSED;

    private static final Map<OpportunityStatus, Set<OpportunityStatus>> NEXT = Map.of(
            OPEN, EnumSet.of(ANALYZING, RESOLVED, CLOSED),
            ANALYZING, EnumSet.of(AWAITING_DECISION, MONITORING, WAITING_DATA, RESOLVED, CLOSED),
            AWAITING_DECISION, EnumSet.of(ANALYZING, ACTING, RESOLVED, CLOSED),
            MONITORING, EnumSet.of(ANALYZING, RESOLVED, CLOSED),
            WAITING_DATA, EnumSet.of(ANALYZING, AWAITING_DECISION, RESOLVED, CLOSED),
            ACTING, EnumSet.of(ANALYZING, AWAITING_DECISION, RESOLVED, CLOSED),
            RESOLVED, EnumSet.noneOf(OpportunityStatus.class),
            CLOSED, EnumSet.noneOf(OpportunityStatus.class));

    public boolean canMoveTo(OpportunityStatus next) {
        return NEXT.get(this).contains(next);
    }

    public boolean isActive() {
        return this != RESOLVED && this != CLOSED;
    }
}
