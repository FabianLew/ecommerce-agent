package com.ecommerceagent.intelligence.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ecommerceagent.decisions.ContextSnapshot;
import com.ecommerceagent.decisions.DecisionProposals;
import com.ecommerceagent.decisions.RiskDetectorSettings;
import com.ecommerceagent.identity.TestScopes;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.intelligence.agent.AgentHook;
import com.ecommerceagent.intelligence.agent.AgentRun;
import com.ecommerceagent.intelligence.agent.AgentRunContext;
import com.ecommerceagent.intelligence.agent.AgentRunRequest;
import com.ecommerceagent.intelligence.agent.AgentRunResult;
import com.ecommerceagent.intelligence.agent.AgentRunStatus;
import com.ecommerceagent.intelligence.agent.AgentRunStep;
import com.ecommerceagent.intelligence.agent.AgentRuntime;
import com.ecommerceagent.intelligence.agent.AgentSettings;
import com.ecommerceagent.intelligence.agent.AgentTool;
import com.ecommerceagent.intelligence.agent.FakeModelGateway;
import com.ecommerceagent.intelligence.agent.InMemoryAgentRuns;
import com.ecommerceagent.intelligence.agent.ModelGatewayRouter;
import com.ecommerceagent.intelligence.agent.MutableClock;
import com.ecommerceagent.intelligence.agent.ToolRegistry;
import com.ecommerceagent.intelligence.knowledge.KnowledgeRegistry;
import com.ecommerceagent.intelligence.model.ModelFailure;
import com.ecommerceagent.intelligence.model.ModelId;
import com.ecommerceagent.intelligence.model.ModelMessage;
import com.ecommerceagent.intelligence.model.ModelRequest;
import com.ecommerceagent.intelligence.model.ModelTurn;
import com.ecommerceagent.intelligence.model.ModelUsage;
import com.ecommerceagent.intelligence.model.ToolCall;
import com.ecommerceagent.intelligence.model.ToolSpec;
import com.ecommerceagent.metrics.CanonicalJson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Runtime safety suite of the agent (B-36, merge-blocking): it runs the real runtime, dispatcher, read tools and
 * knowledge on a scripted model. Covers IG-07, IG-11, IG-22, IG-28, every run limit, the hooks and the pinned
 * versions (document 02 §3–5, ADR-0016).
 */
class AgentRuntimeSafetyTest {

    static final ModelId PRIMARY = new ModelId("fake", "primary");
    static final ModelId FALLBACK = new ModelId("fake", "fallback");
    static final String VARIANT = InventoryContextFixture.VARIANT.toString();
    static final String AD_9001 = InventoryContextFixture.AD_9001.toString();

    final TrustedStoreScope scope = TestScopes.system(UUID.randomUUID(), UUID.randomUUID());
    final FakeModelGateway model = new FakeModelGateway();
    final InMemoryAgentRuns runs = new InMemoryAgentRuns();
    final MutableClock clock = new MutableClock(InventoryContextFixture.AS_OF);
    final KnowledgeRegistry knowledge = new KnowledgeRegistry();
    final ContextSnapshot snapshot = SnapshotFixture.serum();
    List<AgentHook> hooks = List.of();
    AgentSettings settings = settings(List.of(PRIMARY.toString(), FALLBACK.toString()));
    ToolRegistry registry;

    @BeforeEach
    void tools() {
        DecisionProposals proposals = mock(DecisionProposals.class);
        when(proposals.ofOpportunity(any(), any())).thenReturn(List.of());
        InventoryReadTools tools = new InventoryReadTools();
        RiskDetectorSettings risk = new RiskDetectorSettings(new BigDecimal("3"), 2, Duration.ofMinutes(5),
                Duration.ofSeconds(120));
        List<AgentTool> all = List.of(tools.inventoryForecastTool(risk), tools.replenishmentPlanTool(),
                tools.productAdMappingsTool(), tools.adDeliveryStateTool(), tools.recentActionsTool(proposals));
        registry = new ToolRegistry(all);
    }

    static AgentSettings settings(List<String> models) {
        return new AgentSettings(true, models, "low", 1500, new AgentSettings.PriceListSettings("test-1", Map.of(
                PRIMARY.toString(), new AgentSettings.PriceSettings(new BigDecimal("4"), new BigDecimal("20")),
                FALLBACK.toString(), new AgentSettings.PriceSettings(new BigDecimal("2"), new BigDecimal("10")))));
    }

    AgentRuntime runtime() {
        return new AgentRuntime(ModelGatewayRouter.of(model, "fake"), registry, runs, hooks, settings, clock);
    }

    InventoryAgentProposer proposer() {
        return new InventoryAgentProposer(runtime(), runs, settings, knowledge, registry);
    }

    AgentRunResult run(ModelId modelId, BigDecimal spentBefore) {
        var skill = knowledge.skill(InventoryAgent.SKILL).orElseThrow();
        var prompts = knowledge.prompts("inventory-agent").orElseThrow();
        return runtime().run(new AgentRunRequest(scope, SnapshotFixture.OPPORTUNITY, snapshot,
                InventoryAgent.DEFINITION, skill, prompts, "system", "Assess the episode.", modelId, "key", 1,
                spentBefore));
    }

    AgentRunResult run() {
        return run(PRIMARY, BigDecimal.ZERO);
    }

    static ToolCall call(String id, String name, String arguments) {
        return new ToolCall(id, name, arguments);
    }

    static String pause() {
        return """
                {"disposition":"RECOMMEND_ACTION",
                 "action":{"type":"PAUSE_AD","targetType":"META_AD","targetId":"%s","parameters":{"desiredStatus":"PAUSED"}},
                 "monitorPlan":null,
                 "evidence":[{"evidenceId":"%s","version":1},{"evidenceId":"%s","version":1}],
                 "missingRequirements":[],
                 "reasonCodes":["COVER_BELOW_THRESHOLD","DEDICATED_AD_ACTIVE","MULTI_PRODUCT_AD_EXCLUDED"],
                 "summary":"Stock covers 2.6 days; pause ad-9001, keep the catalog ad.",
                 "uncertainties":["No confirmed delivery."]}
                """.formatted(AD_9001, SnapshotFixture.STOCK_EVIDENCE, SnapshotFixture.AD_STATE_EVIDENCE);
    }

    List<AgentRunStep> steps(AgentRunResult result) {
        return runs.steps.get(result.runId());
    }

    JsonNode toolResult(AgentRunResult result, int index) {
        return steps(result).stream().filter(step -> step.kind() == AgentRunStep.Kind.TOOL_CALL).toList().get(index)
                .payload().get("result");
    }

    @Test
    void scenarioReadsThroughTheToolsAndReturnsTheJsonAsGiven() {
        model.thenTools(call("t1", InventoryReadTools.FORECAST, "{\"productVariantId\":\"" + VARIANT + "\"}"),
                        call("t2", InventoryReadTools.MAPPINGS, "{\"productVariantId\":\"" + VARIANT + "\"}"))
                .thenTools(call("t3", InventoryReadTools.DELIVERY, "{\"adId\":\"" + AD_9001 + "\"}"))
                .thenText("```json\n" + pause() + "\n```");

        AgentRunResult result = run();

        assertThat(result.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(result.output()).isEqualTo(CanonicalJson.read(pause()));
        assertThat(toolResult(result, 0).get("stock").get("availableToSell").asInt()).isEqualTo(18);
        assertThat(toolResult(result, 0).get("stockCurrent").asBoolean()).isTrue();
        assertThat(toolResult(result, 1).get("ads")).hasSize(2);
        assertThat(toolResult(result, 2).get("ownStatus").asString()).isEqualTo("ACTIVE");
        // Only the five READ tools are offered, and the results of one turn go back in one message by call id.
        assertThat(model.requests().getFirst().tools()).extracting(ToolSpec::name).containsExactly(
                "get_ad_delivery_state", "get_inventory_forecast", "get_product_ad_mappings", "get_recent_actions",
                "get_replenishment_plan");
        ModelMessage.ToolResults results = (ModelMessage.ToolResults) model.requests().get(1).messages().get(2);
        assertThat(results.results()).extracting(ModelMessage.ToolResult::toolCallId).containsExactly("t1", "t2");
        // Versions are pinned and counters settled.
        AgentRun run = runs.only();
        assertThat(run.skillRef()).isEqualTo("inventory-guardian@1.0.0");
        assertThat(run.knowledgeHash()).isEqualTo(knowledge.skill(InventoryAgent.SKILL).orElseThrow().hash());
        assertThat(run.promptHash()).hasSize(64);
        assertThat(run.toolsHash()).hasSize(64);
        assertThat(run.priceList()).isEqualTo("test-1");
        assertThat(run.modelCalls()).isEqualTo(3);
        assertThat(run.toolCalls()).isEqualTo(3);
        assertThat(run.inputTokens()).isEqualTo(1500);
        assertThat(run.outputTokens()).isEqualTo(300);
        assertThat(run.usageComplete()).isTrue();
        // 1500 × 4 / 1M + 300 × 20 / 1M
        assertThat(run.estimatedCostUsd()).isEqualByComparingTo("0.012");
    }

    @Test
    void ig11MutatingToolIsNeverOfferedNorRun() {
        model.thenTools(call("t1", "pause_ad", "{\"adId\":\"" + AD_9001 + "\"}"))
                .thenText(pause());

        AgentRunResult result = run();

        assertThat(model.requests().getFirst().tools()).extracting(ToolSpec::name).doesNotContain("pause_ad");
        assertThat(toolResult(result, 0).get("error").asString()).isEqualTo("TOOL_NOT_ALLOWED");
        assertThat(steps(result).get(1).errorCode()).isEqualTo("TOOL_NOT_ALLOWED");
        // The model still only proposes; the runtime returns its JSON and changes nothing.
        assertThat(result.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
    }

    @Test
    void ig07ForeignIdsAreNotFoundWhateverExistsElsewhere() {
        String foreignAd = UUID.randomUUID().toString();
        String foreignVariant = UUID.randomUUID().toString();
        model.thenTools(call("t1", InventoryReadTools.DELIVERY, "{\"adId\":\"" + foreignAd + "\"}"),
                        call("t2", InventoryReadTools.FORECAST, "{\"productVariantId\":\"" + foreignVariant + "\"}"),
                        call("t3", InventoryReadTools.RECENT_ACTIONS, "{\"resourceId\":\"" + foreignAd + "\"}"))
                .thenText(pause());

        AgentRunResult result = run();

        assertThat(toolResult(result, 0).get("error").asString()).isEqualTo("NOT_FOUND");
        assertThat(toolResult(result, 1).get("error").asString()).isEqualTo("NOT_FOUND");
        assertThat(toolResult(result, 2).get("error").asString()).isEqualTo("NOT_FOUND");
    }

    @Test
    void argumentsOutsideTheClosedSchemaAreRejectedBeforeTheToolRuns() {
        model.thenTools(call("t1", InventoryReadTools.FORECAST,
                        "{\"productVariantId\":\"" + VARIANT + "\",\"storeId\":\"" + UUID.randomUUID() + "\"}"),
                        call("t2", InventoryReadTools.FORECAST, "not json"),
                        call("t3", InventoryReadTools.FORECAST, "{}"))
                .thenText(pause());

        AgentRunResult result = run();

        for (int i = 0; i < 3; i++) {
            assertThat(toolResult(result, i).get("error").asString()).isEqualTo("INVALID_ARGUMENTS");
        }
    }

    @Test
    void identicalReadIsServedFromTheRunCache() {
        String args = "{\"productVariantId\":\"" + VARIANT + "\"}";
        model.thenTools(call("t1", InventoryReadTools.FORECAST, args))
                .thenTools(call("t2", InventoryReadTools.FORECAST, args))
                .thenText(pause());

        AgentRunResult result = run();

        List<AgentRunStep> tools = steps(result).stream()
                .filter(step -> step.kind() == AgentRunStep.Kind.TOOL_CALL).toList();
        assertThat(tools.get(0).payload().get("cached").asBoolean()).isFalse();
        assertThat(tools.get(1).payload().get("cached").asBoolean()).isTrue();
        assertThat(tools.get(1).payload().get("result")).isEqualTo(tools.get(0).payload().get("result"));
    }

    @Test
    void ig22TruncatedAnswerGetsOneRepairWithoutTools() {
        model.thenTruncated("{\"disposition\":\"RECOMMEND_")
                .thenText(pause());

        AgentRunResult result = run();

        assertThat(result.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(runs.only().repairCalls()).isEqualTo(1);
        ModelRequest repair = model.requests().get(1);
        assertThat(repair.tools()).isEmpty();
        assertThat(((ModelMessage.User) repair.messages().getLast()).text()).contains("one complete JSON object");
    }

    @Test
    void ig22SecondBadAnswerEndsTheRun() {
        model.thenTruncated("{\"disposition\":").thenTruncated("{\"disposition\":");
        assertThat(run().stopReason()).isEqualTo("TRUNCATED");
        assertThat(runs.only().status()).isEqualTo(AgentRunStatus.OUTPUT_INVALID);

        runs.started.clear();
        model.reset();
        model.thenText("I would pause the ad.").thenText("Pause ad-9001, please.");
        AgentRunResult notJson = run();
        assertThat(notJson.status()).isEqualTo(AgentRunStatus.OUTPUT_INVALID);
        assertThat(notJson.stopReason()).isEqualTo("NOT_JSON");
        assertThat(notJson.output()).isNull();
    }

    @Test
    void toolCallsDuringRepairAreNotRun() {
        model.thenText("not json")
                .thenTools(call("t1", InventoryReadTools.FORECAST, "{\"productVariantId\":\"" + VARIANT + "\"}"));

        AgentRunResult result = run();

        assertThat(result.status()).isEqualTo(AgentRunStatus.OUTPUT_INVALID);
        assertThat(steps(result)).noneMatch(step -> step.kind() == AgentRunStep.Kind.TOOL_CALL);
    }

    @Test
    void modelCallLimitEndsTheRun() {
        for (int i = 0; i < 6; i++) {
            model.thenTools(call("t" + i, InventoryReadTools.REPLENISHMENT,
                    "{\"productVariantId\":\"" + VARIANT + "\"}"));
        }
        AgentRunResult result = run();
        assertThat(result.status()).isEqualTo(AgentRunStatus.LIMIT_EXCEEDED);
        assertThat(result.stopReason()).isEqualTo("MAX_MODEL_CALLS");
        assertThat(model.requests()).hasSize(5);
    }

    @Test
    void toolCallLimitStopsBeforeAnyCallOverTheLimitRuns() {
        ToolCall[] calls = new ToolCall[9];
        for (int i = 0; i < calls.length; i++) {
            calls[i] = call("t" + i, InventoryReadTools.REPLENISHMENT, "{\"productVariantId\":\"" + VARIANT + "\"}");
        }
        model.thenTools(calls);

        AgentRunResult result = run();

        assertThat(result.stopReason()).isEqualTo("MAX_TOOL_CALLS");
        assertThat(steps(result)).noneMatch(step -> step.kind() == AgentRunStep.Kind.TOOL_CALL);
    }

    @Test
    void wallTimeLimitEndsTheRun() {
        model.then(request -> {
            clock.advance(Duration.ofSeconds(61));
            return new ModelTurn("", List.of(call("t1", InventoryReadTools.REPLENISHMENT,
                    "{\"productVariantId\":\"" + VARIANT + "\"}")), ModelTurn.FinishReason.TOOL_USE,
                    FakeModelGateway.usage(), "r", null);
        });
        assertThat(run().stopReason()).isEqualTo("MAX_WALL_TIME");
    }

    @Test
    void costLimitIsSharedWithEarlierAttemptsAndReservedBeforeTheCall() {
        AgentRunResult result = run(PRIMARY, new BigDecimal("0.09"));
        assertThat(result.stopReason()).isEqualTo("MAX_COST");
        assertThat(model.requests()).isEmpty();
    }

    @Test
    void oversizedRequestIsNotSent() {
        var skill = knowledge.skill(InventoryAgent.SKILL).orElseThrow();
        var prompts = knowledge.prompts("inventory-agent").orElseThrow();
        AgentRunResult result = runtime().run(new AgentRunRequest(scope, SnapshotFixture.OPPORTUNITY, snapshot,
                InventoryAgent.DEFINITION, skill, prompts, "x".repeat(40_000), "task", PRIMARY, "key", 1,
                BigDecimal.ZERO));
        assertThat(result.stopReason()).isEqualTo("MAX_INPUT_TOKENS");
        assertThat(model.requests()).isEmpty();
    }

    @Test
    void unknownUsageIsChargedConservativelyAndMarkedIncomplete() {
        model.then(request -> new ModelTurn(pause(), List.of(), ModelTurn.FinishReason.COMPLETE, ModelUsage.UNKNOWN,
                null, null));
        AgentRunResult result = run();
        assertThat(result.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(runs.only().usageComplete()).isFalse();
        // The whole output limit of the call is charged.
        assertThat(runs.only().outputTokens()).isEqualTo(1500);
    }

    @Test
    void modelFailureAndRefusalAreExplicitStates() {
        model.thenFailure(ModelFailure.Type.RATE_LIMITED);
        AgentRunResult failed = run();
        assertThat(failed.status()).isEqualTo(AgentRunStatus.MODEL_FAILED);
        assertThat(failed.stopReason()).isEqualTo("RATE_LIMITED");

        model.then(request -> new ModelTurn("", List.of(), ModelTurn.FinishReason.REFUSAL, FakeModelGateway.usage(),
                "r", null));
        assertThat(run().status()).isEqualTo(AgentRunStatus.REFUSED);
    }

    @Test
    void unpricedModelIsNotCalled() {
        AgentRunResult result = run(new ModelId("fake", "unpriced"), BigDecimal.ZERO);
        assertThat(result.stopReason()).isEqualTo("MODEL_NOT_PRICED");
        assertThat(model.requests()).isEmpty();
    }

    @Test
    void hooksCanStopACallOrDenyATool() {
        hooks = List.of(new AgentHook() {
            @Override
            public void beforeToolCall(AgentRunContext run, ToolCall call) {
                if (call.name().equals(InventoryReadTools.RECENT_ACTIONS)) {
                    throw new HookVeto("DENIED", "Recent actions are not available now.");
                }
            }
        });
        model.thenTools(call("t1", InventoryReadTools.RECENT_ACTIONS, "{\"resourceId\":\"" + VARIANT + "\"}"))
                .thenText(pause());
        AgentRunResult denied = run();
        assertThat(toolResult(denied, 0).get("error").asString()).isEqualTo("FORBIDDEN");

        hooks = List.of(new AgentHook() {
            @Override
            public void beforeModelCall(AgentRunContext run, ModelRequest request) {
                throw new HookVeto("BUDGET_FROZEN", "No model calls now.");
            }
        });
        model.reset();
        AgentRunResult stopped = run();
        assertThat(stopped.status()).isEqualTo(AgentRunStatus.STOPPED);
        assertThat(stopped.stopReason()).isEqualTo("BUDGET_FROZEN");
        assertThat(model.requests()).isEmpty();
    }

    @Test
    void ig28FallbackModelIsANewAttemptOnTheSameSnapshot() {
        model.thenFailure(ModelFailure.Type.UNAVAILABLE).thenText(pause());

        var proposal = proposer().propose(scope, SnapshotFixture.OPPORTUNITY, snapshot);

        assertThat(proposal).isPresent();
        List<AgentRun> attempts = runs.attempts(scope,
                InventoryAgentProposer.analysisKey(SnapshotFixture.OPPORTUNITY, SnapshotFixture.SNAPSHOT));
        assertThat(attempts).extracting(AgentRun::attempt, AgentRun::model, AgentRun::status).containsExactly(
                org.assertj.core.groups.Tuple.tuple(1, "primary", AgentRunStatus.MODEL_FAILED),
                org.assertj.core.groups.Tuple.tuple(2, "fallback", AgentRunStatus.SUCCEEDED));
        assertThat(attempts).extracting(AgentRun::contextSnapshotId).containsOnly(SnapshotFixture.SNAPSHOT);
        assertThat(proposal.get().agentRunId()).isEqualTo(attempts.get(1).id());
        assertThat(model.requests()).extracting(ModelRequest::model).containsExactly(PRIMARY, FALLBACK);
        // The task message carries the trusted identifiers; the model does not supply them.
        assertThat(((ModelMessage.User) model.requests().getFirst().messages().getFirst()).text())
                .contains(SnapshotFixture.OPPORTUNITY.toString(), SnapshotFixture.SNAPSHOT.toString(),
                        "COVER_BELOW_THRESHOLD");
        assertThat(model.requests().getFirst().system()).contains("Inventory Guardian").doesNotContain("{{");
    }

    @Test
    void aSucceededAnalysisIsReusedWithoutCallingTheModelAgain() {
        model.thenText(pause());
        var first = proposer().propose(scope, SnapshotFixture.OPPORTUNITY, snapshot).orElseThrow();
        var again = proposer().propose(scope, SnapshotFixture.OPPORTUNITY, snapshot).orElseThrow();

        assertThat(again.agentRunId()).isEqualTo(first.agentRunId());
        assertThat(model.requests()).hasSize(1);
    }

    @Test
    void aRejectedAnalysisFallsBackWithoutAnotherAttempt() {
        model.thenText(pause());
        var first = proposer().propose(scope, SnapshotFixture.OPPORTUNITY, snapshot).orElseThrow();
        proposer().rejected(scope, first.agentRunId(), List.of("EVIDENCE_UNKNOWN"));

        assertThat(proposer().propose(scope, SnapshotFixture.OPPORTUNITY, snapshot)).isEmpty();
        assertThat(model.requests()).hasSize(1);
    }

    @Test
    void allAttemptsFailingLeavesTheBaselineToTheCaller() {
        model.thenFailure(ModelFailure.Type.UNAVAILABLE).thenFailure(ModelFailure.Type.TIMEOUT);
        assertThat(proposer().propose(scope, SnapshotFixture.OPPORTUNITY, snapshot)).isEmpty();
        assertThat(runs.started).hasSize(2);
    }

    @Test
    void switchedOffAgentNeverCallsAModel() {
        settings = new AgentSettings(false, List.of(PRIMARY.toString()), "low", 1500,
                new AgentSettings.PriceListSettings("test-1", Map.of()));
        assertThat(proposer().propose(scope, SnapshotFixture.OPPORTUNITY, snapshot)).isEmpty();
        assertThat(model.requests()).isEmpty();
        assertThat(runs.started).isEmpty();
    }
}
