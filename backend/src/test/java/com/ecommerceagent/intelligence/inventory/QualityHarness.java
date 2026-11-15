package com.ecommerceagent.intelligence.inventory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ecommerceagent.decisions.ContextSnapshot;
import com.ecommerceagent.decisions.DecisionProposals;
import com.ecommerceagent.decisions.PauseAd;
import com.ecommerceagent.decisions.Recommendation;
import com.ecommerceagent.decisions.RiskDetectorSettings;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.intelligence.agent.AgentRun;
import com.ecommerceagent.intelligence.agent.AgentRuntime;
import com.ecommerceagent.intelligence.agent.AgentSettings;
import com.ecommerceagent.intelligence.agent.InMemoryAgentRuns;
import com.ecommerceagent.intelligence.agent.ModelGatewayRouter;
import com.ecommerceagent.intelligence.agent.ToolRegistry;
import com.ecommerceagent.intelligence.knowledge.KnowledgeRegistry;
import com.ecommerceagent.metrics.CanonicalJson;
import org.mockito.Mockito;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Quality report of the Inventory Guardian (B-36, CLAUDE.md "LLM recommendation quality"): every case of the skill's
 * {@code evals/cases.jsonl} runs through the deterministic baseline and, when a model is available, through the
 * agent. The report compares both with the expert expectation; agreement is measured, never required.
 */
final class QualityHarness {

    static final RiskDetectorSettings RISK = new RiskDetectorSettings(new BigDecimal("3"), 2, Duration.ofMinutes(5),
            Duration.ofSeconds(120));

    /** One expert case. Expected reason codes and missing requirements must be contained in the answer. */
    record Case(String id, String title, String fixture, String disposition, String pauses, List<String> reasonCodes,
            List<String> missingRequirements, String notes) {
    }

    /** What a proposer answered, reduced to what the cases check. */
    record Answer(String disposition, String pausesExternalId, List<String> reasonCodes,
            List<String> missingRequirements) {
    }

    record AgentStats(String status, String stopReason, String model, int modelCalls, int toolCalls,
            int inputTokens, int outputTokens, BigDecimal costUsd, long durationMs) {
    }

    record Row(Case expected, Answer baseline, List<String> baselineMismatches, Answer agent,
            List<String> agentMismatches, AgentStats agentStats) {
    }

    record Report(String skill, String skillHash, String agentMode, List<Row> rows) {

        long baselineAgreements() {
            return rows.stream().filter(row -> row.baselineMismatches().isEmpty()).count();
        }

        long agentAgreements() {
            return rows.stream().filter(row -> row.agent() != null && row.agentMismatches().isEmpty()).count();
        }

        BigDecimal agentCostUsd() {
            return rows.stream().filter(row -> row.agentStats() != null).map(row -> row.agentStats().costUsd())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    /** Runs the agent on one case; empty when it gave no answer. Built per case so each one has its own runs. */
    interface Agent {
        String mode();

        Optional<InventoryAgentProposer.AgentProposal> propose(TrustedStoreScope scope, ContextSnapshot snapshot,
                InMemoryAgentRuns runs);
    }

    /** The real runtime, dispatcher, read tools and knowledge on the given model; runs are kept in memory. */
    static Agent agent(String mode, ModelGatewayRouter gateways, AgentSettings settings, KnowledgeRegistry knowledge,
            Clock clock) {
        DecisionProposals proposals = Mockito.mock(DecisionProposals.class);
        Mockito.when(proposals.ofOpportunity(Mockito.any(), Mockito.any())).thenReturn(List.of());
        InventoryReadTools tools = new InventoryReadTools();
        ToolRegistry registry = new ToolRegistry(List.of(tools.inventoryForecastTool(RISK),
                tools.replenishmentPlanTool(), tools.productAdMappingsTool(), tools.adDeliveryStateTool(),
                tools.recentActionsTool(proposals)));
        return new Agent() {
            @Override
            public String mode() {
                return mode;
            }

            @Override
            public Optional<InventoryAgentProposer.AgentProposal> propose(TrustedStoreScope scope,
                    ContextSnapshot snapshot, InMemoryAgentRuns runs) {
                AgentRuntime runtime = new AgentRuntime(gateways, registry, runs, List.of(), settings, clock);
                return new InventoryAgentProposer(runtime, runs, settings, knowledge, registry)
                        .propose(scope, snapshot.opportunityId(), snapshot);
            }
        };
    }

    private final KnowledgeRegistry knowledge;
    private final BaselineProposer baseline = new BaselineProposer(RISK);
    private final Clock clock;

    QualityHarness(KnowledgeRegistry knowledge, Clock clock) {
        this.knowledge = knowledge;
        this.clock = clock;
    }

    List<Case> cases() {
        return knowledge.skill(InventoryAgent.SKILL).orElseThrow().evaluationCases().stream()
                .filter(line -> !line.isBlank()).map(QualityHarness::parse).toList();
    }

    Report run(TrustedStoreScope scope, Agent agent) {
        List<Row> rows = new ArrayList<>();
        for (Case expected : cases()) {
            ContextSnapshot snapshot = QualityFixtures.snapshot(expected.fixture());
            InventoryContext context = InventoryContext.of(snapshot);
            Answer base = answer(baseline.propose(context), context);
            Answer agentAnswer = null;
            AgentStats stats = null;
            if (agent != null) {
                InMemoryAgentRuns runs = new InMemoryAgentRuns();
                long started = clock.millis();
                Optional<InventoryAgentProposer.AgentProposal> proposal = agent.propose(scope, snapshot, runs);
                long duration = clock.millis() - started;
                agentAnswer = proposal.map(p -> answer(p.recommendation(), context))
                        .orElse(new Answer("NO_ANSWER", null, List.of(), List.of()));
                stats = stats(runs, duration);
            }
            rows.add(new Row(expected, base, mismatches(expected, base), agentAnswer,
                    agentAnswer == null ? List.of() : mismatches(expected, agentAnswer), stats));
        }
        var skill = knowledge.skill(InventoryAgent.SKILL).orElseThrow();
        return new Report(skill.reference(), skill.hash(), agent == null ? "SKIPPED_NO_MODEL" : agent.mode(),
                List.copyOf(rows));
    }

    static List<String> mismatches(Case expected, Answer answer) {
        List<String> problems = new ArrayList<>();
        if (!expected.disposition().equals(answer.disposition())) {
            problems.add("disposition " + answer.disposition() + " instead of " + expected.disposition());
        }
        if (expected.pauses() != null && !expected.pauses().equals(answer.pausesExternalId())) {
            problems.add("pauses " + answer.pausesExternalId() + " instead of " + expected.pauses());
        }
        if (expected.pauses() == null && answer.pausesExternalId() != null) {
            problems.add("pauses " + answer.pausesExternalId() + " although no action is expected");
        }
        for (String code : expected.reasonCodes()) {
            if (!answer.reasonCodes().contains(code)) {
                problems.add("reason code " + code + " missing");
            }
        }
        for (String requirement : expected.missingRequirements()) {
            if (!answer.missingRequirements().contains(requirement)) {
                problems.add("missing requirement " + requirement + " not reported");
            }
        }
        return List.copyOf(problems);
    }

    private static Answer answer(Recommendation recommendation, InventoryContext context) {
        String target = recommendation.action() instanceof PauseAd pause ? externalId(context, pause.adId()) : null;
        return new Answer(recommendation.disposition().name(), target, recommendation.reasonCodes(),
                recommendation.missingRequirements());
    }

    /** The agent's JSON as it came back; a target outside the snapshot shows as {@code UNKNOWN:<id>}. */
    private static Answer answer(JsonNode recommendation, InventoryContext context) {
        String target = null;
        JsonNode action = recommendation.get("action");
        if (action != null && !action.isNull() && action.hasNonNull("targetId")) {
            String id = action.get("targetId").asString();
            try {
                target = externalId(context, UUID.fromString(id));
            } catch (IllegalArgumentException e) {
                target = "UNKNOWN:" + id;
            }
        }
        return new Answer(recommendation.path("disposition").asString("MISSING"), target,
                strings(recommendation.get("reasonCodes")), strings(recommendation.get("missingRequirements")));
    }

    private static String externalId(InventoryContext context, UUID adId) {
        return context.ads().stream().filter(ad -> ad.adId().equals(adId)).map(InventoryContext.AdFact::externalId)
                .findFirst().orElse("UNKNOWN:" + adId);
    }

    private static AgentStats stats(InMemoryAgentRuns runs, long durationMs) {
        List<AgentRun> all = runs.started.values().stream().map(runs::run)
                .sorted(java.util.Comparator.comparingInt(AgentRun::attempt)).toList();
        if (all.isEmpty()) {
            return new AgentStats("NOT_RUN", null, null, 0, 0, 0, 0, BigDecimal.ZERO, durationMs);
        }
        AgentRun last = all.getLast();
        return new AgentStats(last.status().name(), last.stopReason(), last.provider() + "/" + last.model(),
                all.stream().mapToInt(AgentRun::modelCalls).sum(), all.stream().mapToInt(AgentRun::toolCalls).sum(),
                all.stream().mapToInt(AgentRun::inputTokens).sum(), all.stream().mapToInt(AgentRun::outputTokens).sum(),
                all.stream().map(AgentRun::estimatedCostUsd).reduce(BigDecimal.ZERO, BigDecimal::add), durationMs);
    }

    private static Case parse(String line) {
        JsonNode node = CanonicalJson.read(line);
        JsonNode expected = node.get("expected");
        return new Case(node.get("id").asString(), node.get("title").asString(), node.get("fixture").asString(),
                expected.get("disposition").asString(),
                expected.hasNonNull("pauses") ? expected.get("pauses").asString() : null,
                strings(expected.get("reasonCodes")), strings(expected.get("missingRequirements")),
                expected.hasNonNull("notes") ? expected.get("notes").asString() : null);
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(item -> values.add(item.asString()));
        }
        return List.copyOf(values);
    }

    /** Writes {@code inventory-guardian.json} and {@code .md} into the directory and returns the Markdown path. */
    static Path write(Report report, Path directory) {
        JsonNodeFactory nodes = JsonNodeFactory.instance;
        ObjectNode root = nodes.objectNode();
        root.put("skill", report.skill());
        root.put("skillHash", report.skillHash());
        root.put("agentMode", report.agentMode());
        root.put("cases", report.rows().size());
        root.put("baselineAgreements", report.baselineAgreements());
        root.put("agentAgreements", report.agentAgreements());
        root.put("agentCostUsd", report.agentCostUsd().toPlainString());
        ArrayNode rows = root.putArray("rows");
        StringBuilder md = new StringBuilder();
        md.append("# Inventory Guardian quality report\n\n")
                .append("Skill `").append(report.skill()).append("` (").append(report.skillHash(), 0, 12)
                .append("), agent: ").append(report.agentMode()).append(".\n\n")
                .append("Agreement with the expert expectation is measured, not required (CLAUDE.md).\n\n")
                .append("- Baseline: ").append(report.baselineAgreements()).append(" / ")
                .append(report.rows().size()).append('\n');
        if (!"SKIPPED_NO_MODEL".equals(report.agentMode())) {
            md.append("- Agent: ").append(report.agentAgreements()).append(" / ").append(report.rows().size())
                    .append(", estimated cost USD ").append(report.agentCostUsd().toPlainString()).append('\n');
        }
        md.append("\n| Case | Expected | Baseline | Agent | Agent run |\n|---|---|---|---|---|\n");
        for (Row row : report.rows()) {
            ObjectNode item = rows.addObject();
            item.put("id", row.expected().id());
            item.put("title", row.expected().title());
            item.put("expectedDisposition", row.expected().disposition());
            item.set("baseline", answerJson(row.baseline(), row.baselineMismatches()));
            if (row.agent() != null) {
                item.set("agent", answerJson(row.agent(), row.agentMismatches()));
                AgentStats stats = row.agentStats();
                ObjectNode run = item.putObject("agentRun");
                run.put("status", stats.status());
                run.put("stopReason", stats.stopReason());
                run.put("model", stats.model());
                run.put("modelCalls", stats.modelCalls());
                run.put("toolCalls", stats.toolCalls());
                run.put("inputTokens", stats.inputTokens());
                run.put("outputTokens", stats.outputTokens());
                run.put("costUsd", stats.costUsd().toPlainString());
                run.put("durationMs", stats.durationMs());
            }
            md.append("| ").append(row.expected().id()).append(' ').append(row.expected().title())
                    .append(" | ").append(row.expected().disposition())
                    .append(row.expected().pauses() == null ? "" : " " + row.expected().pauses())
                    .append(" | ").append(cell(row.baseline(), row.baselineMismatches()))
                    .append(" | ").append(row.agent() == null ? "skipped" : cell(row.agent(), row.agentMismatches()))
                    .append(" | ").append(row.agentStats() == null ? "" : row.agentStats().status() + ", "
                            + row.agentStats().modelCalls() + " calls, USD " + row.agentStats().costUsd()
                            .toPlainString())
                    .append(" |\n");
        }
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("inventory-guardian.json"), CanonicalJson.write(root),
                    StandardCharsets.UTF_8);
            Path markdown = directory.resolve("inventory-guardian.md");
            Files.writeString(markdown, md.toString(), StandardCharsets.UTF_8);
            return markdown;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ObjectNode answerJson(Answer answer, List<String> mismatches) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("disposition", answer.disposition());
        node.put("pauses", answer.pausesExternalId());
        ArrayNode reasons = node.putArray("reasonCodes");
        answer.reasonCodes().forEach(reasons::add);
        ArrayNode missing = node.putArray("missingRequirements");
        answer.missingRequirements().forEach(missing::add);
        node.put("agrees", mismatches.isEmpty());
        ArrayNode problems = node.putArray("mismatches");
        mismatches.forEach(problems::add);
        return node;
    }

    private static String cell(Answer answer, List<String> mismatches) {
        String text = answer.disposition() + (answer.pausesExternalId() == null ? "" : " " + answer.pausesExternalId());
        return mismatches.isEmpty() ? "✓ " + text : "✗ " + text + " (" + String.join("; ", mismatches) + ")";
    }
}
