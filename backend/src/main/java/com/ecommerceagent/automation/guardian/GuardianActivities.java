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

    /** Whether the proposal waits for a person: policy REQUIRE_APPROVAL in mode EXECUTE_ON_APPROVAL (ADR-0020). */
    boolean awaitsApproval(String proposalId);

    /** The decision of a stored approval of this proposal, APPROVED or REJECTED; null when it is another one's. */
    String approvalDecision(String approvalId, String proposalId);

    /** The id of a stored decision on the proposal, so a lost signal is caught up; null when there is none. */
    String storedApproval(String proposalId);

    /** Authorizes the approval after a recheck and runs the executor; idempotent by the action's key. */
    ExecutionRef authorizeAndExecute(String approvalId);

    /** One verification read of the execution; the last one turns an unconfirmed pause into UNKNOWN. */
    ExecutionRef verifyExecution(String executionId, boolean last);

    /** Records the technical outcome of a final execution and its observation plan; idempotent per execution. */
    void recordOutcome(String executionId);

    /** Tells the approvers that the proposal waits for them (ADR-0021); idempotent per proposal revision. */
    void notifyDecisionNeeded(String proposalId);

    /** Tells the approvers how the execution ended (ADR-0021); idempotent per execution and status. */
    void notifyOutcome(String executionId);

    /** Marks the active proposal superseded and the episode resolved. */
    void resolve(String opportunityId, String proposalId);
}
