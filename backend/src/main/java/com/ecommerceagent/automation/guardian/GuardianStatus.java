package com.ecommerceagent.automation.guardian;

/** Progress of an episode, for diagnostics and tests. */
public record GuardianStatus(int analyses, String opportunityStatus, String proposalId, String disposition,
        String policyOutcome, String rejectionCodes, int signals, boolean finished) {
}
