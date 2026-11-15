package com.ecommerceagent.decisions;

import java.util.EnumSet;
import java.util.Set;

/**
 * Decision Inbox tabs (04 §5). A projection of the episode status, not a state of its own (ADR-0014 point 3).
 */
public enum InboxGroup {
    TO_APPROVE(EnumSet.of(OpportunityStatus.AWAITING_DECISION)),
    MONITORING(EnumSet.of(OpportunityStatus.OPEN, OpportunityStatus.ANALYZING, OpportunityStatus.MONITORING,
            OpportunityStatus.WAITING_DATA, OpportunityStatus.ACTING)),
    COMPLETED(EnumSet.of(OpportunityStatus.RESOLVED, OpportunityStatus.CLOSED));

    private final Set<OpportunityStatus> statuses;

    InboxGroup(Set<OpportunityStatus> statuses) {
        this.statuses = statuses;
    }

    public Set<OpportunityStatus> statuses() {
        return statuses;
    }

    public static InboxGroup of(OpportunityStatus status) {
        for (InboxGroup group : values()) {
            if (group.statuses.contains(status)) {
                return group;
            }
        }
        throw new IllegalArgumentException("No inbox group for " + status);
    }
}
