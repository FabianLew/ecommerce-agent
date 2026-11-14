package com.ecommerceagent.decisions;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The JSON form of DecisionProposal v1 (knowledge/schemas/decision-proposal-v1.schema.json). Mapping is explicit
 * so the contract does not depend on serializer defaults; {@link #read} expects a document that passed the
 * schema.
 */
public final class ProposalJson {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private ProposalJson() {
    }

    public static ObjectNode write(DecisionProposal proposal) {
        ObjectNode root = envelope(proposal.schemaVersion(), proposal.proposalId(), proposal.revision(),
                proposal.subject(), proposal.proposer(), proposal.agentRunId(), proposal.contextSnapshotId(),
                proposal.createdAt(), proposal.expiresAt());
        root.set("recommendation", write(proposal.recommendation()));
        return root;
    }

    /** The envelope around a proposer's recommendation, as the backend sets it. */
    static ObjectNode envelope(String schemaVersion, UUID proposalId, int revision, DecisionSubject subject,
            Proposer proposer, UUID agentRunId, UUID contextSnapshotId, Instant createdAt, Instant expiresAt) {
        ObjectNode root = NODES.objectNode();
        root.put("schemaVersion", schemaVersion);
        root.put("proposalId", proposalId.toString());
        root.put("revision", revision);
        ObjectNode subjectNode = root.putObject("subject");
        switch (subject) {
            case OpportunitySubject opportunity -> {
                subjectNode.put("type", "OPPORTUNITY");
                subjectNode.put("opportunityId", opportunity.opportunityId().toString());
            }
        }
        ObjectNode proposerNode = root.putObject("proposer");
        proposerNode.put("kind", proposer.kind().name());
        proposerNode.put("name", proposer.name());
        proposerNode.put("version", proposer.version());
        root.put("agentRunId", agentRunId == null ? null : agentRunId.toString());
        root.put("contextSnapshotId", contextSnapshotId.toString());
        root.put("createdAt", createdAt.toString());
        root.put("expiresAt", expiresAt.toString());
        return root;
    }

    public static ObjectNode write(Recommendation recommendation) {
        ObjectNode node = NODES.objectNode();
        node.put("disposition", recommendation.disposition().name());
        switch (recommendation.action()) {
            case null -> node.putNull("action");
            case PauseAd pause -> {
                ObjectNode action = node.putObject("action");
                action.put("type", PauseAd.TYPE);
                action.put("targetType", PauseAd.TARGET_TYPE);
                action.put("targetId", pause.adId().toString());
                action.putObject("parameters").put("desiredStatus", PauseAd.DESIRED_STATUS);
            }
        }
        if (recommendation.monitorPlan() == null) {
            node.putNull("monitorPlan");
        } else {
            ObjectNode plan = node.putObject("monitorPlan");
            plan.put("nextReviewAt", recommendation.monitorPlan().nextReviewAt().toString());
            strings(plan.putArray("conditions"), recommendation.monitorPlan().conditions());
        }
        ArrayNode evidence = node.putArray("evidence");
        for (EvidenceRef ref : recommendation.evidence()) {
            evidence.addObject().put("evidenceId", ref.evidenceId().toString()).put("version", ref.version());
        }
        strings(node.putArray("missingRequirements"), recommendation.missingRequirements());
        strings(node.putArray("reasonCodes"), recommendation.reasonCodes());
        node.put("summary", recommendation.summary());
        strings(node.putArray("uncertainties"), recommendation.uncertainties());
        return node;
    }

    public static DecisionProposal read(JsonNode root) {
        JsonNode proposer = root.get("proposer");
        JsonNode agentRun = root.get("agentRunId");
        return new DecisionProposal(root.get("schemaVersion").asString(), uuid(root.get("proposalId")),
                root.get("revision").asInt(),
                new OpportunitySubject(uuid(root.get("subject").get("opportunityId"))),
                new Proposer(ProposerKind.valueOf(proposer.get("kind").asString()), proposer.get("name").asString(),
                        proposer.get("version").asString()),
                agentRun == null || agentRun.isNull() ? null : uuid(agentRun), uuid(root.get("contextSnapshotId")),
                instant(root.get("createdAt")), instant(root.get("expiresAt")),
                readRecommendation(root.get("recommendation")));
    }

    public static Recommendation readRecommendation(JsonNode node) {
        JsonNode action = node.get("action");
        ActionSpec spec = null;
        if (action != null && !action.isNull()) {
            String type = action.get("type").asString();
            if (!PauseAd.TYPE.equals(type)) {
                throw new IllegalArgumentException("Unknown action type " + type);
            }
            spec = new PauseAd(uuid(action.get("targetId")));
        }
        JsonNode plan = node.get("monitorPlan");
        MonitorPlan monitorPlan = plan == null || plan.isNull() ? null
                : new MonitorPlan(instant(plan.get("nextReviewAt")), strings(plan.get("conditions")));
        List<EvidenceRef> evidence = new ArrayList<>();
        for (JsonNode ref : node.get("evidence")) {
            evidence.add(new EvidenceRef(uuid(ref.get("evidenceId")), ref.get("version").asInt()));
        }
        return new Recommendation(DecisionDisposition.valueOf(node.get("disposition").asString()), spec,
                monitorPlan, evidence, strings(node.get("missingRequirements")), strings(node.get("reasonCodes")),
                node.get("summary").asString(), strings(node.get("uncertainties")));
    }

    private static void strings(ArrayNode target, List<String> values) {
        values.forEach(target::add);
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(item -> values.add(item.asString()));
        return values;
    }

    private static UUID uuid(JsonNode node) {
        return UUID.fromString(node.asString());
    }

    private static Instant instant(JsonNode node) {
        return Instant.parse(node.asString());
    }
}
