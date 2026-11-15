package com.ecommerceagent.automation.guardian;

/**
 * Result of validating and storing a recommendation. {@code proposalId} is null when it was rejected, and
 * then {@code rejectionCodes} lists why; {@code reviewInSeconds} is set for a monitor plan. {@code proposerKind} is
 * set by {@code proposeAndPersist} (BASELINE or AGENT); {@code agentFallback} says why the agent's answer was not used.
 */
public record ProposalOutcome(String proposalId, String disposition, String rejectionCodes, Long reviewInSeconds,
        String proposerKind, String agentFallback) {

    public ProposalOutcome(String proposalId, String disposition, String rejectionCodes, Long reviewInSeconds) {
        this(proposalId, disposition, rejectionCodes, reviewInSeconds, null, null);
    }
}
