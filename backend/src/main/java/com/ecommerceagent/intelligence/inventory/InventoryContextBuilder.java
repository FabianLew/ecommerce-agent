package com.ecommerceagent.intelligence.inventory;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.ecommerceagent.commerce.AdRepository;
import com.ecommerceagent.commerce.MappedAd;
import com.ecommerceagent.decisions.ContextSnapshot;
import com.ecommerceagent.decisions.ContextSnapshots;
import com.ecommerceagent.decisions.DecisionProposals;
import com.ecommerceagent.decisions.InventoryRiskDetector;
import com.ecommerceagent.decisions.Opportunity;
import com.ecommerceagent.decisions.ProposalJson;
import com.ecommerceagent.decisions.RiskObservation;
import com.ecommerceagent.decisions.StoredProposal;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.metrics.AdEvidence;
import com.ecommerceagent.metrics.Evidence;
import com.ecommerceagent.metrics.EvidenceDraft;
import com.ecommerceagent.metrics.EvidenceStore;
import com.ecommerceagent.metrics.EvidenceType;
import com.ecommerceagent.metrics.InventoryCoverage;
import com.ecommerceagent.metrics.InventoryEvidence;
import com.ecommerceagent.metrics.InventoryMetrics;
import com.ecommerceagent.metrics.MetricValue;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds the context of one Inventory Guardian analysis (03 §4, ADR-0011/0012): it records the evidence of stock,
 * demand, replenishment and every ad mapped to the variant, and freezes it in a snapshot of the episode. Proposers
 * and the policy read only that snapshot.
 */
@Service
public class InventoryContextBuilder {

    public static final String CONTENT_SCHEMA = InventoryContext.CONTENT_SCHEMA;

    private final InventoryMetrics metrics;
    private final InventoryEvidence inventoryEvidence;
    private final AdEvidence adEvidence;
    private final EvidenceStore evidence;
    private final AdRepository ads;
    private final InventoryRiskDetector detector;
    private final ContextSnapshots snapshots;
    private final DecisionProposals proposals;
    private final EpisodeActionHistory history;
    private final Clock clock;

    InventoryContextBuilder(InventoryMetrics metrics, InventoryEvidence inventoryEvidence, AdEvidence adEvidence,
            EvidenceStore evidence, AdRepository ads, InventoryRiskDetector detector, ContextSnapshots snapshots,
            DecisionProposals proposals, EpisodeActionHistory history, Clock clock) {
        this.proposals = proposals;
        this.history = history;
        this.metrics = metrics;
        this.inventoryEvidence = inventoryEvidence;
        this.adEvidence = adEvidence;
        this.evidence = evidence;
        this.ads = ads;
        this.detector = detector;
        this.snapshots = snapshots;
        this.clock = clock;
    }

    @Transactional
    public ContextSnapshot build(TrustedStoreScope scope, Opportunity opportunity) {
        Instant now = clock.instant();
        UUID variantId = opportunity.subjectId();
        List<UUID> ids = new ArrayList<>();
        ObjectNode content = JsonNodeFactory.instance.objectNode();
        content.put("opportunityId", opportunity.id().toString());
        content.put("variantId", variantId.toString());
        content.put("asOf", now.toString());

        InventoryCoverage coverage = metrics.coverage(scope, variantId, now);
        InventoryEvidence.CoverageEvidence recorded = inventoryEvidence.record(scope, coverage);
        recorded.all().forEach(item -> ids.add(item.id()));
        List<RiskObservation> latest = detector.observations(scope, variantId, 1);
        Instant confirmedAt = latest.isEmpty() ? null : latest.getFirst().stockConfirmedAt();

        ObjectNode inventory = content.putObject("inventory");
        if (coverage.stock() == null) {
            inventory.putNull("evidence");
            inventory.putNull("availableToSell");
            inventory.putNull("stockStatus");
            inventory.putNull("observedAt");
        } else {
            inventory.set("evidence", ref(recorded.stock()));
            inventory.put("availableToSell", coverage.stock().reading().availableToSell());
            inventory.put("stockStatus", coverage.stock().reading().stockStatus().name());
            inventory.put("observedAt", coverage.stock().reading().observedAt().toString());
        }
        inventory.put("confirmedAt", confirmedAt == null ? null : confirmedAt.toString());

        ObjectNode demand = content.putObject("demand");
        metric(demand.putObject("unitsSold"), coverage.unitsSold(), recorded.unitsSold());
        metric(demand.putObject("velocityPerDay"), coverage.velocityPerDay(), recorded.velocity());
        metric(demand.putObject("daysOfCover"), coverage.daysOfCover(), recorded.daysOfCover());

        // No replenishment source yet (F5): the context states explicitly that none is known.
        ObjectNode none = JsonNodeFactory.instance.objectNode();
        none.put("known", false);
        none.put("source", "NONE");
        Evidence replenishment = evidence.record(scope, new EvidenceDraft(EvidenceType.REPLENISHMENT,
                EvidenceDraft.variantRef(variantId), null, none, List.of(), now, now, now));
        ids.add(replenishment.id());
        ObjectNode replenishmentNode = content.putObject("replenishment");
        replenishmentNode.set("evidence", ref(replenishment));
        replenishmentNode.put("known", false);

        ObjectNode risk = content.putObject("risk");
        risk.put("thresholdDays", detector.settings().thresholdDays().toPlainString());
        risk.put("detectorVersion", detector.settings().version());
        risk.put("lastOutcome", latest.isEmpty() ? null : latest.getFirst().outcome().name());

        ArrayNode adNodes = content.putArray("ads");
        for (MappedAd mapped : ads.adsForVariant(scope, variantId)) {
            AdEvidence.MappedAdEvidence adRecorded = adEvidence.record(scope, mapped);
            ids.add(adRecorded.state().id());
            ids.add(adRecorded.mapping().id());
            ObjectNode ad = adNodes.addObject();
            ad.put("adId", mapped.ad().id().toString());
            ad.put("externalId", mapped.ad().externalId());
            ad.put("name", mapped.ad().name());
            ad.put("ownStatus", mapped.ad().ownStatus().name());
            ad.put("effectiveStatus", mapped.ad().effectiveStatus());
            ad.put("observedAt", mapped.ad().observedAt().toString());
            ad.put("mappingScope", mapped.mapping().scope().name());
            ad.put("mappingVersion", mapped.mapping().version());
            ad.put("verification", mapped.mapping().verification().name());
            ad.put("verifiedAt", mapped.mapping().verifiedAt() == null ? null
                    : mapped.mapping().verifiedAt().toString());
            ad.put("currentForCreative", mapped.mapping().currentFor(mapped.ad()));
            ad.put("variantCount", mapped.mapping().variantIds().size());
            ad.set("stateEvidence", ref(adRecorded.state()));
            ad.set("mappingEvidence", ref(adRecorded.mapping()));
        }
        recentActions(scope, opportunity, content.putObject("recentActions"), risk.get("lastOutcome"));
        return snapshots.create(scope, opportunity.id(), CONTENT_SCHEMA, now, content, ids);
    }

    /**
     * The episode's earlier proposals and executions as they stand now, at the snapshot time (ADR-0016 point 4).
     * {@code get_recent_actions} reads only this section, so a later decision or execution never changes what a run
     * or its replay saw.
     */
    private void recentActions(TrustedStoreScope scope, Opportunity opportunity, ObjectNode section,
            tools.jackson.databind.JsonNode lastOutcome) {
        List<StoredProposal> earlier = proposals.ofOpportunity(scope, opportunity.id());
        ArrayNode proposalNodes = section.putArray("proposals");
        for (StoredProposal stored : earlier) {
            ObjectNode item = proposalNodes.addObject();
            item.put("proposalId", stored.proposal().proposalId().toString());
            item.put("revision", stored.proposal().revision());
            item.put("disposition", stored.proposal().recommendation().disposition().name());
            item.put("status", stored.status().name());
            item.put("createdAt", stored.proposal().createdAt().toString());
            item.set("action", stored.proposal().recommendation().action() == null
                    ? JsonNodeFactory.instance.nullNode()
                    : ProposalJson.write(stored.proposal().recommendation()).get("action"));
        }
        ArrayNode executed = section.putArray("executedActions");
        for (EpisodeActionHistory.ExecutedAction action : history.executed(scope,
                earlier.stream().map(stored -> stored.proposal().proposalId()).toList())) {
            ObjectNode item = executed.addObject();
            item.put("proposalId", action.proposalId().toString());
            item.put("revision", action.revision());
            item.put("actionType", action.actionType());
            item.put("targetExternalId", action.targetExternalId());
            item.put("status", action.status());
            item.put("updatedAt", action.updatedAt().toString());
        }
        section.set("lastRiskOutcome", lastOutcome.deepCopy());
    }

    private static void metric(ObjectNode node, MetricValue value, Evidence recorded) {
        node.set("evidence", ref(recorded));
        node.put("value", value.value() == null ? null : value.value().toPlainString());
        node.put("quality", value.quality().name());
        node.put("unavailableReason", value.unavailableReason() == null ? null : value.unavailableReason().name());
    }

    private static ObjectNode ref(Evidence item) {
        return JsonNodeFactory.instance.objectNode().put("evidenceId", item.id().toString())
                .put("version", item.version());
    }
}
