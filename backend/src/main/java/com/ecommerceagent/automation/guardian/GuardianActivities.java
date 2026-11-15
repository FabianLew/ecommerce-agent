package com.ecommerceagent.automation.guardian;

import io.temporal.activity.ActivityInterface;

/** Steps of an episode; arguments and results carry only ids and small values (ADR-0012). */
@ActivityInterface
public interface GuardianActivities {

    /** Moves the episode to ANALYZING and freezes a new context snapshot. */
    ContextRef buildContext(String opportunityId);

    /** The baseline's recommendation for the snapshot, as contract JSON. */
    String propose(String snapshotId);

    ProposalOutcome validateAndPersist(String opportunityId, String snapshotId, String recommendation);

    /**
     * The agent's proposal for the snapshot, validated and stored; the baseline's when the agent is off, fails, or
     * its proposal is rejected (ADR-0016 point 8). Idempotent per snapshot.
     */
    ProposalOutcome proposeAndPersist(String opportunityId, String snapshotId);

    /** Evaluates and records the policy for the proposal's action; returns the outcome. */
    String evaluatePolicy(String proposalId);

    /** Moves the episode to {@code status}; a no-op when it is already there. */
    String moveTo(String opportunityId, String status, String closeReason);

    void expireProposal(String proposalId);

    /** Marks the active proposal superseded and the episode resolved. */
    void resolve(String opportunityId, String proposalId);
}
