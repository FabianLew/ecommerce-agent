package com.ecommerceagent.automation.execution;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The allowed ledger transitions of document 02 §9 (ADR-0020 point 4); everything else is rejected. */
class ExecutionStatusTest {

    @Test
    void forwardPathAndBranches() {
        assertThat(ExecutionStatus.PLANNED.canMoveTo(ExecutionStatus.IN_PROGRESS)).isTrue();
        assertThat(ExecutionStatus.IN_PROGRESS.canMoveTo(ExecutionStatus.APPLIED)).isTrue();
        assertThat(ExecutionStatus.APPLIED.canMoveTo(ExecutionStatus.VERIFIED)).isTrue();
        assertThat(ExecutionStatus.IN_PROGRESS.canMoveTo(ExecutionStatus.UNKNOWN)).isTrue();
        assertThat(ExecutionStatus.UNKNOWN.canMoveTo(ExecutionStatus.VERIFIED)).isTrue();
        assertThat(ExecutionStatus.PLANNED.canMoveTo(ExecutionStatus.NOOP)).isTrue();
    }

    @Test
    void noRowMovesBackOrLeavesAFinalState() {
        assertThat(ExecutionStatus.APPLIED.canMoveTo(ExecutionStatus.PLANNED)).isFalse();
        assertThat(ExecutionStatus.APPLIED.canMoveTo(ExecutionStatus.IN_PROGRESS)).isFalse();
        assertThat(ExecutionStatus.PLANNED.canMoveTo(ExecutionStatus.VERIFIED)).isFalse();
        for (ExecutionStatus last : new ExecutionStatus[] {ExecutionStatus.VERIFIED, ExecutionStatus.NOOP,
                ExecutionStatus.FAILED, ExecutionStatus.CONFLICT}) {
            assertThat(last.terminal()).isTrue();
            for (ExecutionStatus next : ExecutionStatus.values()) {
                assertThat(last.canMoveTo(next)).as(last + " -> " + next).isFalse();
            }
        }
    }
}
