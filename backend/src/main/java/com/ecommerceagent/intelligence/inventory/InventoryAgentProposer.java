package com.ecommerceagent.intelligence.inventory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

import com.ecommerceagent.decisions.ContextSnapshot;
import com.ecommerceagent.decisions.ProposalRules;
import com.ecommerceagent.decisions.ProposalSchema;
import com.ecommerceagent.decisions.Proposer;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.intelligence.agent.AgentRun;
import com.ecommerceagent.intelligence.agent.AgentRunRequest;
import com.ecommerceagent.intelligence.agent.AgentRunResult;
import com.ecommerceagent.intelligence.agent.AgentRunStatus;
import com.ecommerceagent.intelligence.agent.AgentRuns;
import com.ecommerceagent.intelligence.agent.AgentRuntime;
import com.ecommerceagent.intelligence.agent.AgentSettings;
import com.ecommerceagent.intelligence.agent.ToolRegistry;
import com.ecommerceagent.intelligence.knowledge.KnowledgeRegistry;
import com.ecommerceagent.intelligence.knowledge.PromptPackage;
import com.ecommerceagent.intelligence.knowledge.SkillPackage;
import com.ecommerceagent.intelligence.model.ModelId;
import com.ecommerceagent.metrics.CanonicalJson;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The agent proposer of the Inventory Guardian (ADR-0016 point 8). One analysis is one snapshot; its attempts follow
 * the model policy and share the cost limit. A succeeded run of the analysis is reused without a new model call.
 * Returns nothing when the agent is off or no attempt produced an answer: the caller then uses the baseline.
 */
@Service
public class InventoryAgentProposer {

    private final AgentRuntime runtime;
    private final AgentRuns runs;
    private final AgentSettings settings;
    private final SkillPackage skill;
    private final PromptPackage prompts;
    private final String outputSchema;

    InventoryAgentProposer(AgentRuntime runtime, AgentRuns runs, AgentSettings settings, KnowledgeRegistry knowledge,
            ToolRegistry tools) {
        this.runtime = runtime;
        this.runs = runs;
        this.settings = settings;
        this.skill = knowledge.skill(InventoryAgent.SKILL)
                .orElseThrow(() -> new IllegalStateException("Missing skill " + InventoryAgent.SKILL));
        this.prompts = knowledge.prompts(InventoryAgent.DEFINITION.systemPromptRef())
                .orElseThrow(() -> new IllegalStateException("Missing prompts of " + InventoryAgent.DEFINITION.name()));
        for (String tool : skill.requiredReadTools()) {
            if (!InventoryAgent.DEFINITION.allowedReadTools().contains(tool) || !tools.has(tool)) {
                throw new IllegalStateException("Skill " + skill.reference() + " needs tool " + tool
                        + ", which the agent does not have");
            }
        }
        if (!InventoryAgent.DEFINITION.outputSchemaVersion().equals(skill.outputSchemaVersion())) {
            throw new IllegalStateException("Skill " + skill.reference() + " targets schema "
                    + skill.outputSchemaVersion());
        }
        this.outputSchema = recommendationSchema();
    }

    public boolean enabled() {
        return settings.enabled() && !settings.modelIds().isEmpty();
    }

    /** The agent's recommendation for the analysis, exactly as the model returned it. */
    public record AgentProposal(UUID agentRunId, Proposer proposer, JsonNode recommendation) {
    }

    public static String analysisKey(UUID opportunityId, UUID snapshotId) {
        return opportunityId + ":" + snapshotId;
    }

    public Optional<AgentProposal> propose(TrustedStoreScope scope, UUID opportunityId, ContextSnapshot snapshot) {
        if (!enabled()) {
            return Optional.empty();
        }
        String key = analysisKey(opportunityId, snapshot.id());
        List<AgentRun> earlier = runs.attempts(scope, key);
        for (AgentRun run : earlier) {
            if (run.status() == AgentRunStatus.SUCCEEDED) {
                return Optional.of(new AgentProposal(run.id(), InventoryAgent.PROPOSER, run.output()));
            }
            if (run.status() == AgentRunStatus.OUTPUT_REJECTED) {
                return Optional.empty();
            }
        }
        BigDecimal spent = earlier.stream().map(AgentRun::estimatedCostUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<ModelId> models = settings.modelIds();
        InventoryContext context = InventoryContext.of(snapshot);
        String system = PromptPackage.fill(prompts.system(), Map.of("skill", skill.instructions()));
        String task = task(context);
        // A run left RUNNING by a lost worker counts as a used attempt; its cost stays in the shared limit.
        for (int attempt = earlier.size() + 1; attempt <= models.size(); attempt++) {
            AgentRunResult result = runtime.run(new AgentRunRequest(scope, opportunityId, snapshot,
                    InventoryAgent.DEFINITION, skill, prompts, system, task, models.get(attempt - 1), key, attempt,
                    spent));
            if (result.succeeded()) {
                return Optional.of(new AgentProposal(result.runId(), InventoryAgent.PROPOSER, result.output()));
            }
            spent = spent.add(result.estimatedCostUsd());
            if ("MAX_COST".equals(result.stopReason())) {
                break;
            }
        }
        return Optional.empty();
    }

    /** The validator rejected the run's proposal: the run is marked, and the analysis falls back to the baseline. */
    public void rejected(TrustedStoreScope scope, UUID agentRunId, List<String> codes) {
        runs.markRejected(scope, agentRunId, String.join(",", new TreeSet<>(codes)));
    }

    private String task(InventoryContext context) {
        ProposalRules rules = ProposalRules.INVENTORY_RISK;
        Map<String, String> values = new java.util.HashMap<>();
        values.put("opportunityId", context.opportunityId().toString());
        values.put("variantId", context.variantId().toString());
        values.put("snapshotId", context.snapshotId().toString());
        values.put("asOf", context.asOf().toString());
        values.put("thresholdDays", context.thresholdDays().toPlainString());
        values.put("schema", outputSchema);
        values.put("reasonCodes", String.join(", ", new TreeSet<>(rules.reasonCodes())));
        values.put("missingRequirements", String.join(", ", new TreeSet<>(rules.missingRequirements())));
        values.put("monitorConditions", String.join(", ", new TreeSet<>(rules.monitorConditions())));
        values.put("minReview", rules.minReviewDelay().toString());
        values.put("maxReview", rules.maxReviewDelay().toString());
        return PromptPackage.fill(prompts.task(), values);
    }

    /** The {@code recommendation} part of DecisionProposal v1 with the definitions it refers to. */
    private static String recommendationSchema() {
        try (InputStream in = ProposalSchema.class.getResourceAsStream(ProposalSchema.RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing " + ProposalSchema.RESOURCE);
            }
            JsonNode full = CanonicalJson.read(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            ObjectNode schema = (ObjectNode) full.get("properties").get("recommendation").deepCopy();
            schema.set("$defs", full.get("$defs").deepCopy());
            return CanonicalJson.write(schema);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
