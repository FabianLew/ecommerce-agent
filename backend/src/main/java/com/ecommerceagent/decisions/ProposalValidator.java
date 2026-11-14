package com.ecommerceagent.decisions;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.ecommerceagent.commerce.AdRepository;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.metrics.Evidence;
import com.ecommerceagent.metrics.EvidenceDraft;
import com.ecommerceagent.metrics.EvidenceStore;
import com.ecommerceagent.metrics.EvidenceType;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * Validates a DecisionProposal v1 document in three layers (ADR-0012): the schema, the domain invariants of the
 * episode's process, and the evidence. Every fact a proposal relies on must be stored evidence of this store,
 * in the proposal's snapshot and about the episode's variant or the ads mapped to it (IG-10). Nothing here trusts
 * the proposer.
 */
@Service
public class ProposalValidator {

    private final Opportunities opportunities;
    private final ContextSnapshots snapshots;
    private final EvidenceStore evidence;
    private final AdRepository ads;
    private final Clock clock;

    ProposalValidator(Opportunities opportunities, ContextSnapshots snapshots, EvidenceStore evidence,
            AdRepository ads, Clock clock) {
        this.opportunities = opportunities;
        this.snapshots = snapshots;
        this.evidence = evidence;
        this.ads = ads;
        this.clock = clock;
    }

    public ProposalValidation validate(TrustedStoreScope scope, JsonNode document) {
        List<ProposalError> schemaErrors = ProposalSchema.validate(document);
        if (!schemaErrors.isEmpty()) {
            return new ProposalValidation(null, schemaErrors);
        }
        DecisionProposal proposal;
        try {
            proposal = ProposalJson.read(document);
        } catch (RuntimeException e) {
            return new ProposalValidation(null, List.of(new ProposalError(ProposalSchema.SCHEMA_VIOLATION, "$",
                    e.getClass().getSimpleName() + ": " + e.getMessage())));
        }
        List<ProposalError> errors = new ArrayList<>();
        UUID opportunityId = ((OpportunitySubject) proposal.subject()).opportunityId();
        Optional<Opportunity> opportunity = opportunities.find(scope, opportunityId);
        if (opportunity.isEmpty()) {
            errors.add(error("OPPORTUNITY_UNKNOWN", "$.subject.opportunityId", "No such episode in this store"));
            return new ProposalValidation(null, errors);
        }
        if (!opportunity.get().status().isActive()) {
            errors.add(error("OPPORTUNITY_NOT_ACTIVE", "$.subject.opportunityId",
                    "Episode is " + opportunity.get().status()));
        }
        domain(proposal, ProposalRules.forCategory(opportunity.get().category()), errors);
        evidence(scope, proposal, opportunity.get(), errors);
        return new ProposalValidation(errors.isEmpty() ? proposal : null, errors);
    }

    private void domain(DecisionProposal proposal, ProposalRules rules, List<ProposalError> errors) {
        Recommendation rec = proposal.recommendation();
        boolean needsAction = rec.disposition() == DecisionDisposition.RECOMMEND_ACTION;
        if (needsAction && rec.action() == null) {
            errors.add(error("ACTION_REQUIRED", "$.recommendation.action", "RECOMMEND_ACTION needs an action"));
        }
        if (!needsAction && rec.action() != null) {
            errors.add(error("ACTION_NOT_EXPECTED", "$.recommendation.action",
                    rec.disposition() + " must not carry an action"));
        }
        if (rec.action() != null && !rules.actionTypes().contains(rec.action().type())) {
            errors.add(error("ACTION_NOT_ALLOWED", "$.recommendation.action.type",
                    rec.action().type() + " is not allowed for this process"));
        }
        boolean monitor = rec.disposition() == DecisionDisposition.MONITOR;
        if (monitor != (rec.monitorPlan() != null)) {
            errors.add(error("MONITOR_PLAN_INVALID", "$.recommendation.monitorPlan",
                    "A monitor plan belongs to MONITOR and only to it"));
        }
        if (rec.monitorPlan() != null) {
            Instant review = rec.monitorPlan().nextReviewAt();
            if (review.isBefore(proposal.createdAt().plus(rules.minReviewDelay()))
                    || review.isAfter(proposal.createdAt().plus(rules.maxReviewDelay()))) {
                errors.add(error("MONITOR_REVIEW_OUT_OF_RANGE", "$.recommendation.monitorPlan.nextReviewAt",
                        "Review must be between " + rules.minReviewDelay() + " and " + rules.maxReviewDelay()
                                + " after creation"));
            }
            unknown(rec.monitorPlan().conditions(), rules.monitorConditions(), "MONITOR_CONDITION_UNKNOWN",
                    "$.recommendation.monitorPlan.conditions", errors);
        }
        if (rec.disposition() == DecisionDisposition.INSUFFICIENT_DATA && rec.missingRequirements().isEmpty()) {
            errors.add(error("MISSING_REQUIREMENTS_REQUIRED", "$.recommendation.missingRequirements",
                    "INSUFFICIENT_DATA must name what is missing"));
        }
        unknown(rec.missingRequirements(), rules.missingRequirements(), "MISSING_REQUIREMENT_UNKNOWN",
                "$.recommendation.missingRequirements", errors);
        unknown(rec.reasonCodes(), rules.reasonCodes(), "REASON_CODE_UNKNOWN", "$.recommendation.reasonCodes",
                errors);
        if (!clock.instant().isBefore(proposal.expiresAt())) {
            errors.add(error("PROPOSAL_EXPIRED", "$.expiresAt", "Proposal expired at " + proposal.expiresAt()));
        }
    }

    private void evidence(TrustedStoreScope scope, DecisionProposal proposal, Opportunity opportunity,
            List<ProposalError> errors) {
        Optional<ContextSnapshot> snapshot = snapshots.find(scope, proposal.contextSnapshotId());
        if (snapshot.isEmpty()) {
            errors.add(error("SNAPSHOT_UNKNOWN", "$.contextSnapshotId", "No such snapshot in this store"));
            return;
        }
        if (!opportunity.id().equals(snapshot.get().opportunityId())) {
            errors.add(error("SNAPSHOT_OTHER_OPPORTUNITY", "$.contextSnapshotId",
                    "Snapshot was built for another episode"));
        }
        Set<UUID> inSnapshot = new HashSet<>(snapshot.get().evidenceIds());
        Map<UUID, Evidence> snapshotEvidence = evidence.findAll(scope, inSnapshot).stream()
                .collect(Collectors.toMap(Evidence::id, Function.identity()));

        // Resources the episode concerns: its variant and the ads whose mapping in the snapshot covers it.
        String variantRef = EvidenceDraft.variantRef(opportunity.subjectId());
        Set<String> related = new HashSet<>();
        related.add(variantRef);
        Set<UUID> mappedAds = new HashSet<>();
        for (Evidence item : snapshotEvidence.values()) {
            if (item.type() == EvidenceType.AD_MAPPING && coversVariant(item, opportunity.subjectId())) {
                UUID adId = UUID.fromString(item.content().get("adId").asString());
                mappedAds.add(adId);
                related.add(item.resourceRef());
                related.add(EvidenceDraft.adRef(adId));
            }
        }

        List<EvidenceRef> refs = proposal.recommendation().evidence();
        Map<UUID, Evidence> cited = evidence.findAll(scope, refs.stream().map(EvidenceRef::evidenceId).toList())
                .stream()
                .collect(Collectors.toMap(Evidence::id, Function.identity()));
        for (int i = 0; i < refs.size(); i++) {
            EvidenceRef ref = refs.get(i);
            String path = "$.recommendation.evidence[" + i + "]";
            Evidence item = cited.get(ref.evidenceId());
            if (item == null) {
                errors.add(error("EVIDENCE_UNKNOWN", path, "No such evidence in this store"));
                continue;
            }
            if (item.version() != ref.version()) {
                errors.add(error("EVIDENCE_VERSION_MISMATCH", path,
                        "Evidence has version " + item.version() + ", not " + ref.version()));
            }
            if (!inSnapshot.contains(item.id())) {
                errors.add(error("EVIDENCE_NOT_IN_SNAPSHOT", path, "Evidence is not part of the proposal's snapshot"));
            } else if (!related.contains(item.resourceRef())) {
                errors.add(error("EVIDENCE_UNRELATED", path,
                        item.resourceRef() + " is neither the episode's variant nor an ad mapped to it"));
            }
        }

        switch (proposal.recommendation().action()) {
            case null -> {
            }
            case PauseAd pause -> {
                if (ads.ad(scope, pause.adId()).isEmpty()) {
                    errors.add(error("TARGET_UNKNOWN", "$.recommendation.action.targetId",
                            "No such ad in this store"));
                } else if (!mappedAds.contains(pause.adId())) {
                    errors.add(error("TARGET_NOT_MAPPED", "$.recommendation.action.targetId",
                            "The snapshot has no mapping of this ad to the episode's variant"));
                }
            }
        }
    }

    private static boolean coversVariant(Evidence mapping, UUID variantId) {
        JsonNode variants = mapping.content().get("variantIds");
        if (variants == null || !variants.isArray() || mapping.content().get("adId") == null) {
            return false;
        }
        for (JsonNode variant : variants) {
            if (variantId.toString().equals(variant.asString())) {
                return true;
            }
        }
        return false;
    }

    private static void unknown(List<String> values, Set<String> allowed, String code, String path,
            List<ProposalError> errors) {
        for (int i = 0; i < values.size(); i++) {
            if (!allowed.contains(values.get(i))) {
                errors.add(error(code, path + "[" + i + "]", values.get(i) + " is not in the process's list"));
            }
        }
    }

    private static ProposalError error(String code, String path, String message) {
        return new ProposalError(code, path, message);
    }
}
