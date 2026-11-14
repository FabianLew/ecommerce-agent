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

    /** Evaluates and records the policy for the proposal's action; returns the outcome. */
    String evaluatePolicy(String proposalId);

    /** Moves the episode to {@code status}; a no-op when it is already there. */
    String moveTo(String opportunityId, String status, String closeReason);

    void expireProposal(String proposalId);

    /** Marks the active proposal superseded and the episode resolved. */
    void resolve(String opportunityId, String proposalId);
}
