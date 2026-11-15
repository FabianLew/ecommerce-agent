package com.ecommerceagent.automation.execution;

import org.springframework.modulith.NamedInterface;

/** How far an action type may go in a store (ADR-0025 point 2), lowest first. There is no automatic level. */
@NamedInterface("execution")
public enum ActionLevel {
    /** No proposals of the type are shown for decision. */
    OFF,
    /** Proposals are created and evaluated, never approved. */
    PROPOSE_ONLY,
    /** L1: a person may approve and the backend executes after a recheck. */
    APPROVAL_REQUIRED;

    public static ActionLevel min(ActionLevel a, ActionLevel b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
