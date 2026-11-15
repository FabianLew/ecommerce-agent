package com.ecommerceagent.automation.guardian;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import com.ecommerceagent.automation.TaskQueues;
import com.ecommerceagent.automation.temporal.ActivityStoreScope;
import com.ecommerceagent.decisions.ContextSnapshot;
import com.ecommerceagent.decisions.ContextSnapshots;
import com.ecommerceagent.decisions.DecisionProposals;
import com.ecommerceagent.decisions.Opportunities;
import com.ecommerceagent.decisions.Opportunity;
import com.ecommerceagent.decisions.OpportunityStatus;
import com.ecommerceagent.decisions.ProposalJson;
import com.ecommerceagent.decisions.ProposalStatus;
import com.ecommerceagent.decisions.ProposalSubmission;
import com.ecommerceagent.decisions.Recommendation;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.decisions.Proposer;
import com.ecommerceagent.decisions.StoredProposal;
import com.ecommerceagent.intelligence.inventory.BaselineProposer;
import com.ecommerceagent.intelligence.inventory.InventoryAgentProposer;
import com.ecommerceagent.intelligence.inventory.InventoryContext;
import com.ecommerceagent.intelligence.inventory.InventoryContextBuilder;
import com.ecommerceagent.metrics.CanonicalJson;
import io.temporal.spring.boot.ActivityImpl;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ActivityImpl(taskQueues = TaskQueues.MAIN)
public class GuardianActivitiesImpl implements GuardianActivities {

    private final Opportunities opportunities;
    private final InventoryContextBuilder contexts;
    private final ContextSnapshots snapshots;
    private final BaselineProposer baseline;
    private final InventoryAgentProposer agent;
    private final DecisionProposals proposals;
    private final PolicyEvaluations policies;
    private final GuardianSettings settings;
    private final TransactionTemplate transactions;
    private final Clock clock;

    GuardianActivitiesImpl(Opportunities opportunities, InventoryContextBuilder contexts, ContextSnapshots snapshots,
            BaselineProposer baseline, InventoryAgentProposer agent, DecisionProposals proposals,
            PolicyEvaluations policies, GuardianSettings settings, TransactionTemplate transactions, Clock clock) {
        this.opportunities = opportunities;
        this.contexts = contexts;
        this.snapshots = snapshots;
        this.baseline = baseline;
        this.agent = agent;
        this.proposals = proposals;
        this.policies = policies;
        this.settings = settings;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public ContextRef buildContext(String opportunityId) {
        TrustedStoreScope scope = ActivityStoreScope.current();
        return transactions.execute(status -> {
            Opportunity opportunity = move(scope, UUID.fromString(opportunityId), OpportunityStatus.ANALYZING, null);
            ContextSnapshot snapshot = contexts.build(scope, opportunity);
            InventoryContext context = InventoryContext.of(snapshot);
            return new ContextRef(snapshot.id().toString(), context.daysOfCover().value() == null ? null
                    : context.daysOfCover().value().toPlainString());
        });
    }

    @Override
    public String propose(String snapshotId) {
        ContextSnapshot snapshot = snapshots.find(ActivityStoreScope.current(), UUID.fromString(snapshotId))
                .orElseThrow();
        return CanonicalJson.write(ProposalJson.write(baseline.propose(InventoryContext.of(snapshot))));
    }

    @Override
    public ProposalOutcome validateAndPersist(String opportunityId, String snapshotId, String recommendation) {
        TrustedStoreScope scope = ActivityStoreScope.current();
        ProposalSubmission submission = proposals.submit(scope, UUID.fromString(opportunityId),
                UUID.fromString(snapshotId), BaselineProposer.PROPOSER, null, CanonicalJson.read(recommendation),
                settings.proposalTtl());
        if (!submission.accepted()) {
            return new ProposalOutcome(null, null, String.join(",", submission.codes()), null);
        }
        return outcome(submission.proposal(), null);
    }

    @Override
    public ProposalOutcome proposeAndPersist(String opportunityId, String snapshotId) {
        TrustedStoreScope scope = ActivityStoreScope.current();
        UUID opportunity = UUID.fromString(opportunityId);
        UUID snapshotKey = UUID.fromString(snapshotId);
        // A retried step finds the proposal it already stored for this snapshot.
        Optional<StoredProposal> stored = proposals.ofOpportunity(scope, opportunity).stream()
                .filter(proposal -> proposal.proposal().contextSnapshotId().equals(snapshotKey))
                .reduce((first, second) -> second);
        if (stored.isPresent()) {
            return outcome(stored.get(), null);
        }
        ContextSnapshot snapshot = snapshots.find(scope, snapshotKey).orElseThrow();
        String fallback = agent.enabled() ? "AGENT_FAILED" : "AGENT_DISABLED";
        Optional<InventoryAgentProposer.AgentProposal> proposal = agent.propose(scope, opportunity, snapshot);
        if (proposal.isPresent()) {
            ProposalSubmission submission = proposals.submit(scope, opportunity, snapshotKey,
                    proposal.get().proposer(), proposal.get().agentRunId(), proposal.get().recommendation(),
                    settings.proposalTtl());
            if (submission.accepted()) {
                return outcome(submission.proposal(), null);
            }
            agent.rejected(scope, proposal.get().agentRunId(), submission.codes());
            fallback = "AGENT_PROPOSAL_REJECTED:" + String.join(",", submission.codes());
        }
        Proposer proposer = BaselineProposer.PROPOSER;
        ProposalSubmission submission = proposals.submit(scope, opportunity, snapshotKey, proposer, null,
                ProposalJson.write(baseline.propose(InventoryContext.of(snapshot))), settings.proposalTtl());
        if (!submission.accepted()) {
            return new ProposalOutcome(null, null, String.join(",", submission.codes()), null,
                    proposer.kind().name(), fallback);
        }
        return outcome(submission.proposal(), fallback);
    }

    private ProposalOutcome outcome(StoredProposal stored, String fallback) {
        Recommendation accepted = stored.proposal().recommendation();
        Long review = accepted.monitorPlan() == null ? null
                : Math.max(1, Duration.between(clock.instant(), accepted.monitorPlan().nextReviewAt()).toSeconds());
        return new ProposalOutcome(stored.proposal().proposalId().toString(), accepted.disposition().name(), null,
                review, stored.proposal().proposer().kind().name(), fallback);
    }

    @Override
    public String evaluatePolicy(String proposalId) {
        return policies.evaluate(ActivityStoreScope.current(), UUID.fromString(proposalId)).outcome().name();
    }

    @Override
    public String moveTo(String opportunityId, String status, String closeReason) {
        return move(ActivityStoreScope.current(), UUID.fromString(opportunityId), OpportunityStatus.valueOf(status),
                closeReason).status().name();
    }

    @Override
    public void expireProposal(String proposalId) {
        proposals.close(ActivityStoreScope.current(), UUID.fromString(proposalId), ProposalStatus.EXPIRED);
    }

    @Override
    public void resolve(String opportunityId, String proposalId) {
        TrustedStoreScope scope = ActivityStoreScope.current();
        transactions.executeWithoutResult(status -> {
            if (proposalId != null) {
                proposals.close(scope, UUID.fromString(proposalId), ProposalStatus.SUPERSEDED);
            }
            move(scope, UUID.fromString(opportunityId), OpportunityStatus.RESOLVED, "RISK_RESOLVED");
        });
    }

    /** Idempotent: a retried step finds the episode already moved. A closed episode stays closed. */
    private Opportunity move(TrustedStoreScope scope, UUID opportunityId, OpportunityStatus next, String reason) {
        Opportunity current = opportunities.find(scope, opportunityId)
                .orElseThrow(() -> new IllegalStateException("Unknown opportunity " + opportunityId));
        if (current.status() == next || !current.status().isActive()) {
            return current;
        }
        return opportunities.transition(scope, opportunityId, next, reason);
    }
}
