package com.ecommerceagent.automation.guardian;

/**
 * Result of validating and storing a recommendation. {@code proposalId} is null when it was rejected, and
 * then {@code rejectionCodes} lists why; {@code reviewInSeconds} is set for a monitor plan. {@code proposerKind} is
 * set by {@code proposeAndPersist} (BASELINE or AGENT); {@code agentFallback} says why the agent's answer was not used.
 * {@code waitSeconds} is set by {@code proposeAndPersistOrWait} when nothing was stored because the agent waits for a
 * slot (ADR-0027 section 6.2): the workflow sleeps that long and asks again; {@code agentFallback} then says why.
 */
public record ProposalOutcome(String proposalId, String disposition, String rejectionCodes, Long reviewInSeconds,
        String proposerKind, String agentFallback, Long waitSeconds) {

    public ProposalOutcome(String proposalId, String disposition, String rejectionCodes, Long reviewInSeconds) {
        this(proposalId, disposition, rejectionCodes, reviewInSeconds, null, null, null);
    }

    public ProposalOutcome(String proposalId, String disposition, String rejectionCodes, Long reviewInSeconds,
            String proposerKind, String agentFallback) {
        this(proposalId, disposition, rejectionCodes, reviewInSeconds, proposerKind, agentFallback, null);
    }

    static ProposalOutcome waiting(long seconds, String reason) {
        return new ProposalOutcome(null, null, null, null, null, reason, seconds);
    }

    public boolean waiting() {
        return waitSeconds != null;
    }
}
