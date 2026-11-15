package com.ecommerceagent.automation.guardian;

/**
 * Progress of an episode, for diagnostics and tests. {@code proposerKind} is null on the pre-agent path; the execution
 * fields are null until an approved action ran (ADR-0020).
 */
public record GuardianStatus(int analyses, String opportunityStatus, String proposalId, String disposition,
        String policyOutcome, String rejectionCodes, int signals, boolean finished, String proposerKind,
        String agentFallback, boolean awaitingApproval, String executionId, String executionStatus,
        String executionCode) {
}
