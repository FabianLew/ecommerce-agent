package com.ecommerceagent.automation.execution;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.springframework.modulith.NamedInterface;

/**
 * States of one action execution (document 02 §9, ADR-0020 point 4). {@code APPLIED}: the write was accepted or its
 * effect observed; {@code VERIFIED}: a read confirmed the requested state; {@code NOOP}: the state already existed;
 * {@code UNKNOWN}: the effect could not be determined and needs reconciliation.
 */
@NamedInterface("execution")
public enum ExecutionStatus {
    PLANNED,
    IN_PROGRESS,
    APPLIED,
    VERIFIED,
    NOOP,
    UNKNOWN,
    FAILED,
    CONFLICT;

    private static final Map<ExecutionStatus, Set<ExecutionStatus>> NEXT = Map.of(
            PLANNED, EnumSet.of(IN_PROGRESS, NOOP, FAILED, CONFLICT),
            IN_PROGRESS, EnumSet.of(APPLIED, NOOP, UNKNOWN, FAILED, CONFLICT),
            APPLIED, EnumSet.of(VERIFIED, UNKNOWN, CONFLICT),
            UNKNOWN, EnumSet.of(APPLIED, VERIFIED, FAILED, CONFLICT, IN_PROGRESS),
            VERIFIED, EnumSet.noneOf(ExecutionStatus.class),
            NOOP, EnumSet.noneOf(ExecutionStatus.class),
            FAILED, EnumSet.noneOf(ExecutionStatus.class),
            CONFLICT, EnumSet.noneOf(ExecutionStatus.class));

    public boolean canMoveTo(ExecutionStatus next) {
        return NEXT.get(this).contains(next);
    }

    /** No further attempt changes the row. */
    public boolean terminal() {
        return NEXT.get(this).isEmpty();
    }
}
