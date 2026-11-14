package com.ecommerceagent.automation.guardian;

/**
 * Result of validating and storing a recommendation. {@code proposalId} is null when it was rejected, and
 * then {@code rejectionCodes} lists why; {@code reviewInSeconds} is set for a monitor plan.
 */
public record ProposalOutcome(String proposalId, String disposition, String rejectionCodes, Long reviewInSeconds) {
}
