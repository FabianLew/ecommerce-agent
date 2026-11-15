package com.ecommerceagent.automation.guardian;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import com.ecommerceagent.automation.TaskQueues;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

/**
 * One episode (ADR-0012 point 6, ADR-0020 point 8): analyse, then move the episode as the outcome implies and wait
 * for a timer, a risk signal or, with execution enabled, a person's decision. An approval is authorized again,
 * executed once through the ledger and verified by reading; nothing is undone automatically. Reanalyses and data
 * retries are bounded, and the episode closes when its time limit passes.
 */
@WorkflowImpl(taskQueues = TaskQueues.MAIN)
public class InventoryGuardianWorkflowImpl implements InventoryGuardianWorkflow {

    /**
     * Episodes started before ADR-0016 replay with the baseline-only steps; version 1 asks the agent once, version 2
     * also waits for an AI slot (ADR-0027 section 6.2).
     */
    static final String AGENT_PROPOSER_CHANGE = "agent-proposer";
    /** Episodes started before ADR-0020 never wait for an approval. */
    static final String APPROVAL_EXECUTION_CHANGE = "approval-execution";
    /** How often a waiting episode looks for a stored decision whose signal was lost. */
    static final Duration APPROVAL_LOOKUP = Duration.ofMinutes(10);
    /** Verification reads after the write (document 03 §8): at most two minutes in the first cycle. */
    static final List<Duration> VERIFY_DELAYS = List.of(Duration.ofSeconds(5), Duration.ofSeconds(15),
            Duration.ofSeconds(30), Duration.ofSeconds(60));
    private static final Logger log = Workflow.getLogger(InventoryGuardianWorkflowImpl.class);

    private final GuardianActivities activities = Workflow.newActivityStub(GuardianActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(60))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(5))
                            .setBackoffCoefficient(2)
                            .setMaximumAttempts(5)
                            .build())
                    .build());

    /** RevalidateAndExecute (document 03 §8): every attempt starts from the ledger, so retries are safe. */
    private final GuardianActivities executing = Workflow.newActivityStub(GuardianActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setScheduleToCloseTimeout(Duration.ofSeconds(120))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(2))
                            .setBackoffCoefficient(2)
                            .setMaximumAttempts(3)
                            .build())
                    .build());

    /** Notification (document 03 §8): short, a few attempts; its failure never undoes or repeats the action. */
    private final GuardianActivities notifying = Workflow.newActivityStub(GuardianActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(10))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(1))
                            .setBackoffCoefficient(2)
                            .setMaximumAttempts(3)
                            .build())
                    .build());

    /** The agent path (ADR-0016): model attempts take longer than the other steps; the step itself is idempotent. */
    private final GuardianActivities proposing = Workflow.newActivityStub(GuardianActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(5))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(10))
                            .setBackoffCoefficient(2)
                            .setMaximumAttempts(3)
                            .build())
                    .build());

    private int analyses;
    private int dataRetries;
    private int signals;
    private String opportunityStatus;
    private String proposalId;
    private String disposition;
    private String policyOutcome;
    private String rejectionCodes;
    private String analyzedCover;
    private String proposerKind;
    private String agentFallback;
    private boolean resolved;
    private boolean awaitingApproval;
    private String executionId;
    private String executionStatus;
    private String executionCode;
    private final java.util.Set<String> handledApprovals = new java.util.HashSet<>();
    private final java.util.List<String> approvals = new java.util.ArrayList<>();
    private boolean reanalyze;
    private boolean finished;
    private BigDecimal significantChange = BigDecimal.ZERO;

    @Override
    public void run(GuardianInput input) {
        long deadline = Workflow.currentTimeMillis() + input.episodeTimeoutSeconds() * 1000;
        significantChange = new BigDecimal(input.significantCoverChange());
        Long wakeAt = analyse(input);
        Long expiresAt = expiry(input);
        while (!finished) {
            long next = Math.min(deadline, Math.min(wakeAt == null ? Long.MAX_VALUE : wakeAt,
                    expiresAt == null ? Long.MAX_VALUE : expiresAt));
            long wait = next - Workflow.currentTimeMillis();
            if (wait > 0) {
                Workflow.await(Duration.ofMillis(wait),
                        () -> resolved || reanalyze || (awaitingApproval && !approvals.isEmpty()));
            }
            long now = Workflow.currentTimeMillis();
            if (resolved) {
                activities.resolve(input.opportunityId(), proposalId);
                opportunityStatus = "RESOLVED";
                finished = true;
            } else if (now >= deadline) {
                if (proposalId != null) {
                    activities.expireProposal(proposalId);
                }
                opportunityStatus = activities.moveTo(input.opportunityId(), "CLOSED", "EPISODE_TIMEOUT");
                finished = true;
            } else if (awaitingApproval && (!approvals.isEmpty() || (wakeAt != null && now >= wakeAt))) {
                String approvalId = !approvals.isEmpty() ? approvals.remove(0) : activities.storedApproval(proposalId);
                if (approvalId == null || !handledApprovals.add(approvalId)) {
                    wakeAt = now + APPROVAL_LOOKUP.toMillis();
                } else {
                    wakeAt = decided(input, approvalId, now);
                    if (!awaitingApproval) {
                        expiresAt = null;
                    }
                }
            } else if (reanalyze || (wakeAt != null && now >= wakeAt)) {
                reanalyze = false;
                awaitingApproval = false;
                if (analyses > input.maxReanalyses()) {
                    // Out of reanalyses: a person decides with what is there.
                    opportunityStatus = activities.moveTo(input.opportunityId(), "AWAITING_DECISION", null);
                    wakeAt = null;
                } else {
                    wakeAt = analyse(input);
                    expiresAt = expiry(input);
                }
            } else if (expiresAt != null && now >= expiresAt) {
                activities.expireProposal(proposalId);
                expiresAt = null;
                awaitingApproval = false;
                wakeAt = null;
            }
        }
    }

    private Long expiry(GuardianInput input) {
        return proposalId == null ? null : Workflow.currentTimeMillis() + input.proposalTtlSeconds() * 1000;
    }

    /** One analysis; returns when to look again (workflow time), or null to wait for signals only. */
    private Long analyse(GuardianInput input) {
        analyses++;
        ContextRef context = activities.buildContext(input.opportunityId());
        analyzedCover = context.daysOfCover();
        ProposalOutcome outcome;
        int proposer = Workflow.getVersion(AGENT_PROPOSER_CHANGE, Workflow.DEFAULT_VERSION, 2);
        if (proposer == Workflow.DEFAULT_VERSION) {
            String recommendation = activities.propose(context.snapshotId());
            outcome = activities.validateAndPersist(input.opportunityId(), context.snapshotId(), recommendation);
        } else if (proposer == 1) {
            outcome = proposing.proposeAndPersist(input.opportunityId(), context.snapshotId());
            proposerKind = outcome.proposerKind();
            agentFallback = outcome.agentFallback();
        } else {
            // Version 2 (ADR-0027 section 6.2): an agent refused a slot waits on a durable timer, bounded by the
            // activity, and asks again; the activity answers with a stored proposal once it stops waiting.
            long waited = 0;
            outcome = proposing.proposeAndPersistOrWait(input.opportunityId(), context.snapshotId(), waited);
            while (outcome.waiting()) {
                agentFallback = outcome.agentFallback();
                Workflow.sleep(Duration.ofSeconds(outcome.waitSeconds()));
                waited += outcome.waitSeconds();
                outcome = proposing.proposeAndPersistOrWait(input.opportunityId(), context.snapshotId(), waited);
            }
            proposerKind = outcome.proposerKind();
            agentFallback = outcome.agentFallback();
        }
        proposalId = outcome.proposalId();
        disposition = outcome.disposition();
        rejectionCodes = outcome.rejectionCodes();
        policyOutcome = null;
        if (proposalId == null) {
            log.warn("Proposal for episode {} rejected: {}", input.opportunityId(), rejectionCodes);
            opportunityStatus = activities.moveTo(input.opportunityId(), "AWAITING_DECISION", null);
            return null;
        }
        long now = Workflow.currentTimeMillis();
        switch (disposition) {
            case "RECOMMEND_ACTION" -> {
                policyOutcome = activities.evaluatePolicy(proposalId);
                if ("DEFER".equals(policyOutcome)) {
                    return waitForData(input, now);
                }
                opportunityStatus = activities.moveTo(input.opportunityId(), "AWAITING_DECISION", null);
                if (Workflow.getVersion(APPROVAL_EXECUTION_CHANGE, Workflow.DEFAULT_VERSION, 1) == 1
                        && activities.awaitsApproval(proposalId)) {
                    awaitingApproval = true;
                    notifySafely(() -> notifying.notifyDecisionNeeded(proposalId));
                    return now + APPROVAL_LOOKUP.toMillis();
                }
                return null;
            }
            case "ESCALATE" -> {
                opportunityStatus = activities.moveTo(input.opportunityId(), "AWAITING_DECISION", null);
                return null;
            }
            case "NO_ACTION" -> {
                opportunityStatus = activities.moveTo(input.opportunityId(), "CLOSED", "NO_ACTION");
                finished = true;
                return null;
            }
            case "MONITOR" -> {
                opportunityStatus = activities.moveTo(input.opportunityId(), "MONITORING", null);
                return now + outcome.reviewInSeconds() * 1000;
            }
            case "INSUFFICIENT_DATA" -> {
                return waitForData(input, now);
            }
            default -> throw new IllegalStateException("Unknown disposition " + disposition);
        }
    }

    /** A person decided (ADR-0020 point 8): close on a rejection, otherwise authorize, execute and verify. */
    private Long decided(GuardianInput input, String approvalId, long now) {
        String decision = activities.approvalDecision(approvalId, proposalId);
        if (decision == null) {
            return now + APPROVAL_LOOKUP.toMillis();
        }
        awaitingApproval = false;
        if ("REJECTED".equals(decision)) {
            opportunityStatus = activities.moveTo(input.opportunityId(), "CLOSED", "REJECTED");
            finished = true;
            return null;
        }
        opportunityStatus = activities.moveTo(input.opportunityId(), "ACTING", null);
        ExecutionRef execution = executing.authorizeAndExecute(approvalId);
        executionId = execution.executionId();
        executionStatus = execution.status();
        executionCode = execution.code();
        if (executionId == null) {
            // Conditions changed since the approval: a new analysis decides again, with a new revision.
            reanalyze = true;
            return null;
        }
        if ("APPLIED".equals(executionStatus) || "UNKNOWN".equals(executionStatus)) {
            for (int i = 0; i < VERIFY_DELAYS.size() && !"VERIFIED".equals(executionStatus); i++) {
                Workflow.sleep(VERIFY_DELAYS.get(i));
                ExecutionRef read = activities.verifyExecution(executionId, i == VERIFY_DELAYS.size() - 1);
                executionStatus = read.status();
                executionCode = read.code();
            }
        }
        // The technical outcome and its observation plan (ADR-0021), then the episode moves, then the approvers are
        // told; the episode counts as finished only after all of it.
        activities.recordOutcome(executionId);
        boolean close = true;
        switch (executionStatus) {
            case "VERIFIED" ->
                    opportunityStatus = activities.moveTo(input.opportunityId(), "CLOSED", "ACTION_VERIFIED");
            case "NOOP" ->
                    opportunityStatus = activities.moveTo(input.opportunityId(), "CLOSED", "ALREADY_IN_STATE");
            // UNKNOWN, FAILED, CONFLICT: nothing is undone automatically; a person decides (escalation).
            default -> {
                opportunityStatus = activities.moveTo(input.opportunityId(), "AWAITING_DECISION", null);
                close = false;
            }
        }
        String reported = executionId;
        notifySafely(() -> notifying.notifyOutcome(reported));
        finished = close;
        return null;
    }

    /** A notification failure never changes the episode or the action (IG-23); the sweep retries the e-mail. */
    private static void notifySafely(Runnable step) {
        try {
            step.run();
        } catch (io.temporal.failure.ActivityFailure e) {
            log.warn("Notification step failed, the episode continues: {}", e.getMessage());
        }
    }

    private Long waitForData(GuardianInput input, long now) {
        if (dataRetries >= input.maxDataRetries()) {
            opportunityStatus = activities.moveTo(input.opportunityId(), "AWAITING_DECISION", null);
            return null;
        }
        dataRetries++;
        opportunityStatus = activities.moveTo(input.opportunityId(), "WAITING_DATA", null);
        return now + input.dataRetryDelaySeconds() * 1000;
    }

    @Override
    public void riskUpdated(RiskSignal signal) {
        signals++;
        if (analyses > 0 && !finished && changed(signal.daysOfCover())) {
            reanalyze = true;
        }
    }

    @Override
    public void riskResolved(RiskSignal signal) {
        signals++;
        resolved = true;
    }

    @Override
    public void approvalDecided(String approvalId) {
        signals++;
        if (!approvals.contains(approvalId)) {
            approvals.add(approvalId);
        }
    }

    @Override
    public GuardianStatus status() {
        return new GuardianStatus(analyses, opportunityStatus, proposalId, disposition, policyOutcome, rejectionCodes,
                signals, finished, proposerKind, agentFallback, awaitingApproval, executionId, executionStatus,
                executionCode);
    }

    /** A change of days of cover by at least the significant amount, or cover becoming known. */
    private boolean changed(String cover) {
        if (cover == null) {
            return false;
        }
        if (analyzedCover == null) {
            return true;
        }
        return new BigDecimal(cover).subtract(new BigDecimal(analyzedCover)).abs().compareTo(significantChange) >= 0;
    }
}
