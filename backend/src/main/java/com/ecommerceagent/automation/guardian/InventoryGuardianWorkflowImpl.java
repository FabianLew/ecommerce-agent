package com.ecommerceagent.automation.guardian;

import java.math.BigDecimal;
import java.time.Duration;

import com.ecommerceagent.automation.TaskQueues;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

/**
 * Shadow-mode episode (ADR-0012 point 6): analyse, then move the episode as the outcome implies and wait for a
 * timer or a risk signal. Approval is never awaited; nothing is executed. Reanalyses and data retries are bounded,
 * and the episode closes when its time limit passes.
 */
@WorkflowImpl(taskQueues = TaskQueues.MAIN)
public class InventoryGuardianWorkflowImpl implements InventoryGuardianWorkflow {

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

    private int analyses;
    private int dataRetries;
    private int signals;
    private String opportunityStatus;
    private String proposalId;
    private String disposition;
    private String policyOutcome;
    private String rejectionCodes;
    private String analyzedCover;
    private boolean resolved;
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
                Workflow.await(Duration.ofMillis(wait), () -> resolved || reanalyze);
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
            } else if (reanalyze || (wakeAt != null && now >= wakeAt)) {
                reanalyze = false;
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
        String recommendation = activities.propose(context.snapshotId());
        ProposalOutcome outcome = activities.validateAndPersist(input.opportunityId(), context.snapshotId(),
                recommendation);
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
    public GuardianStatus status() {
        return new GuardianStatus(analyses, opportunityStatus, proposalId, disposition, policyOutcome, rejectionCodes,
                signals, finished);
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
