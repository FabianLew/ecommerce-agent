package com.ecommerceagent.intelligence.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ecommerceagent.aigovernance.InMemoryAiGovernance;
import com.ecommerceagent.aigovernance.SendDecision;
import com.ecommerceagent.decisions.ContextSnapshot;
import com.ecommerceagent.decisions.RiskDetectorSettings;
import com.ecommerceagent.identity.TestScopes;
import com.ecommerceagent.identity.StoreAccessDeniedException;
import com.ecommerceagent.identity.StorePermission;
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
import com.ecommerceagent.intelligence.agent.MutableClock;
import com.ecommerceagent.intelligence.agent.ToolExecutionContext;
import com.ecommerceagent.intelligence.agent.ToolRegistry;
import com.ecommerceagent.intelligence.knowledge.KnowledgeRegistry;
import com.ecommerceagent.intelligence.model.GovernedModelGateway;
import com.ecommerceagent.intelligence.model.ModelFailure;
import com.ecommerceagent.intelligence.model.provider.ProviderGateways;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Runtime safety suite of the agent (B-36, merge-blocking): it runs the real runtime, dispatcher, read tools and
 * knowledge on a scripted model. Covers IG-07, IG-11, IG-22, IG-28, every run limit, the hooks and the pinned
 * versions (document 02 §3–5, ADR-0016).
 */
class AgentRuntimeSafetyTest {

    static final InventoryAgentRunPolicy POLICY = InventoryAgentRunPolicy.defaults();
    static final ModelId PRIMARY = new ModelId("fake", "primary");
    static final ModelId FALLBACK = new ModelId("fake", "fallback");
    static final String VARIANT = InventoryContextFixture.VARIANT.toString();
    static final String AD_9001 = InventoryContextFixture.AD_9001.toString();

    final TrustedStoreScope scope = TestScopes.system(UUID.randomUUID(), UUID.randomUUID());
    final FakeModelGateway model = new FakeModelGateway();
    final InMemoryAiGovernance governance = new InMemoryAiGovernance().price(PRIMARY.name(), "4", "20")
            .price(FALLBACK.name(), "2", "10").unpriced("unpriced", "NOT_PRICED");
    final InMemoryAgentRuns runs = new InMemoryAgentRuns();
    final MutableClock clock = new MutableClock(InventoryContextFixture.AS_OF);
    final KnowledgeRegistry knowledge = new KnowledgeRegistry();
    final ContextSnapshot snapshot = SnapshotFixture.serum();
    List<AgentHook> hooks = List.of();
    volatile boolean accessRevoked;
    AgentSettings settings = settings(List.of(PRIMARY.toString(), FALLBACK.toString()));
    ToolRegistry registry;

    @BeforeEach
    void tools() {
        InventoryReadTools tools = new InventoryReadTools();
        RiskDetectorSettings risk = new RiskDetectorSettings(
            RiskDetectorSettings.DEFAULT_VERSION, new BigDecimal("3"), new BigDecimal("5"), 2, Duration.ofMinutes(5),
                Duration.ofSeconds(120));
        List<AgentTool> all = List.of(tools.inventoryForecastTool(risk), tools.replenishmentPlanTool(),
                tools.productAdMappingsTool(), tools.adDeliveryStateTool(), tools.recentActionsTool());
        registry = new ToolRegistry(all);
    }

    static AgentSettings settings(List<String> models) {
        return new AgentSettings(true, models, "low");
    }

    AgentRuntime runtime() {
        return new AgentRuntime(new GovernedModelGateway(ProviderGateways.of(model, "fake"), governance, governance,
                governance, checked -> {
                    if (accessRevoked) {
                        throw new StoreAccessDeniedException(checked.storeId(), StorePermission.STORE_READ);
                    }
                }, governance, clock), registry, runs, hooks, settings, clock);
    }

    InventoryAgentProposer proposer() {
        return proposer(POLICY);
    }

    InventoryAgentProposer proposer(InventoryAgentRunPolicy policy) {
        return new InventoryAgentProposer(runtime(), runs, settings, policy, knowledge, registry, clock);
    }

    static InventoryAgentRunPolicy policy(String version, int maxModelCalls, int maxOutputTokensPerCall,
            int maxAttempts) {
        return new InventoryAgentRunPolicy(version, maxModelCalls, 8, Duration.ofSeconds(60), 12_000,
                maxOutputTokensPerCall, 4_000, new BigDecimal("0.10"), 1, maxAttempts, 1, Duration.ofMinutes(60));
    }

    AgentRunResult run(ModelId modelId, BigDecimal spentBefore) {
        var skill = knowledge.skill(InventoryAgent.SKILL).orElseThrow();
        var prompts = knowledge.prompts("inventory-agent").orElseThrow();
        return runtime().run(new AgentRunRequest(scope, SnapshotFixture.OPPORTUNITY, snapshot,
                InventoryAgent.DEFINITION, POLICY.limits(), skill, prompts, "system", "Assess the episode.", modelId,
                "key", 1, spentBefore, "test-situation"));
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
        assertThat(run.priceList()).as("every cost comes from the catalog").isEqualTo("ai_price");
        assertThat(run.runPolicy()).isEqualTo("inventory-agent-run/2");
        assertThat(runs.started.get(run.id()).limits()).isEqualTo(POLICY.limits());
        // The output limit of one call comes from the run policy.
        assertThat(model.requests()).extracting(ModelRequest::maxOutputTokens).containsOnly(1500);
        assertThat(run.modelCalls()).isEqualTo(3);
        assertThat(run.toolCalls()).isEqualTo(3);
        assertThat(run.inputTokens()).isEqualTo(1500);
        assertThat(run.outputTokens()).isEqualTo(300);
        assertThat(run.usageComplete()).isTrue();
        // 1500 × 4 / 1M + 300 × 20 / 1M
        assertThat(run.estimatedCostUsd()).isEqualByComparingTo("0.012");
    }

    /** ADR-0016 point 4: the history comes from the snapshot only, so a later decision cannot change a replay. */
    @Test
    void recentActionsAreReadFromTheSnapshotAndALegacySnapshotSaysSo() {
        AgentTool tool = new InventoryReadTools().recentActionsTool();
        JsonNode args = CanonicalJson.read("{\"resourceId\":\"" + VARIANT + "\"}");

        JsonNode legacy = tool.execute(new ToolExecutionContext(scope, UUID.randomUUID(), snapshot), args)
                .body();
        assertThat(legacy.get("proposals")).isEmpty();
        assertThat(legacy.get("limitations").get(0).asString()).isEqualTo("RECENT_ACTIONS_NOT_IN_SNAPSHOT");

        tools.jackson.databind.node.ObjectNode content = (tools.jackson.databind.node.ObjectNode) snapshot.content()
                .deepCopy();
        tools.jackson.databind.node.ObjectNode section = content.putObject("recentActions");
        section.putArray("proposals").addObject().put("revision", 1).put("status", "REJECTED");
        section.putArray("executedActions").addObject().put("status", "VERIFIED");
        ContextSnapshot frozen = new ContextSnapshot(snapshot.id(), snapshot.opportunityId(), snapshot.contentSchema(),
                snapshot.asOf(), content, CanonicalJson.hash(content), snapshot.evidenceIds(), snapshot.createdAt());
        JsonNode result = tool.execute(new ToolExecutionContext(scope, UUID.randomUUID(), frozen), args)
                .body();
        assertThat(result.get("proposals").get(0).get("status").asString()).isEqualTo("REJECTED");
        assertThat(result.get("executedActions").get(0).get("status").asString()).isEqualTo("VERIFIED");
        assertThat(result.get("limitations")).isEmpty();
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

    /** ADR-0027 section 9 (E-54.2): the run's own cost limit is checked with the catalog price, nothing else. */
    @Test
    void theRunCostLimitUsesTheCatalogPrice() {
        AgentRunResult cheap = run(PRIMARY, new BigDecimal("0.09"));
        assertThat(cheap.stopReason()).isEqualTo("MAX_COST");

        governance.price(PRIMARY.name(), "0.40", "2.00");
        model.thenText(pause());
        AgentRunResult priced = run(PRIMARY, new BigDecimal("0.09"));
        assertThat(priced.stopReason()).as("a lower catalog price fits the same limit").isNotEqualTo("MAX_COST");
        assertThat(model.requests()).hasSize(1);
    }

    @Test
    void oversizedRequestIsNotSent() {
        var skill = knowledge.skill(InventoryAgent.SKILL).orElseThrow();
        var prompts = knowledge.prompts("inventory-agent").orElseThrow();
        AgentRunResult result = runtime().run(new AgentRunRequest(scope, SnapshotFixture.OPPORTUNITY, snapshot,
                InventoryAgent.DEFINITION, POLICY.limits(), skill, prompts, "x".repeat(40_000), "task", PRIMARY,
                "key", 1, BigDecimal.ZERO, "test-situation"));
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
    void everyModelCallIsReservedAndMarkedSentBeforeTheProviderSeesItAndSettledAfter() {
        model.then(request -> {
            assertThat(governance.events()).as("admitted, reserved and marked sent before the request")
                    .containsExactly("ADMISSION:GRANTED:1", "RESERVED:" + governance.reservations().getFirst().key,
                            "SLOT:GRANTED", "SENT:" + governance.reservations().getFirst().key);
            return new ModelTurn("", List.of(call("t1", InventoryReadTools.FORECAST,
                    "{\"productVariantId\":\"" + VARIANT + "\"}")), ModelTurn.FinishReason.TOOL_USE,
                    FakeModelGateway.usage(), "r1", null);
        }).thenText(pause());

        AgentRunResult result = run();

        assertThat(result.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(governance.reservations()).hasSize(2).allSatisfy(reservation -> {
            assertThat(reservation.state).isEqualTo("SETTLED");
            assertThat(reservation.agentRunId).isEqualTo(result.runId());
            assertThat(reservation.automationType).isEqualTo("inventory-agent");
            assertThat(reservation.estimate.provider()).isEqualTo("fake");
            assertThat(reservation.estimate.maxOutputTokens()).isEqualTo(1500);
            assertThat(reservation.usage.inputTokens()).isEqualTo(500);
        });
        assertThat(governance.reservations()).extracting(reservation -> reservation.key).containsExactly(
                "agent-run:" + result.runId() + ":call:1", "agent-run:" + result.runId() + ":call:2");
    }

    @Test
    void aCallGovernanceDoesNotAdmitIsNeverSent() {
        governance.deny("BUDGET_EXHAUSTED:PLATFORM:MONTHLY");
        AgentRunResult exhausted = run();
        assertThat(exhausted.status()).isEqualTo(AgentRunStatus.LIMIT_EXCEEDED);
        assertThat(exhausted.stopReason()).isEqualTo("AI_GOVERNANCE:BUDGET_EXHAUSTED:PLATFORM:MONTHLY");

        governance.deny("PRICE_UNVERIFIED");
        AgentRunResult unpriced = run();
        assertThat(unpriced.status()).isEqualTo(AgentRunStatus.MODEL_FAILED);
        assertThat(unpriced.stopReason()).isEqualTo("AI_GOVERNANCE:PRICE_UNVERIFIED");
        assertThat(model.requests()).isEmpty();
    }

    /** ADR-0027 criterion 32: when governance itself fails, no paid call is made. */
    @Test
    void aFailingGovernanceFailsClosed() {
        for (String step : List.of("admit", "reserve", "markSent")) {
            governance.breakStep(step);
            model.thenText(pause());
            AgentRunResult result = run();
            assertThat(result.status()).as(step).isEqualTo(AgentRunStatus.MODEL_FAILED);
            assertThat(result.succeeded()).isFalse();
        }
        assertThat(model.requests()).as("nothing reached the provider").isEmpty();
        assertThat(governance.leases()).isEmpty();
    }

    @Test
    void aTimeoutIsPossiblyChargedAndCountsAgainstTheRun() {
        model.thenFailure(ModelFailure.Type.TIMEOUT);

        AgentRunResult result = run();

        assertThat(result.status()).isEqualTo(AgentRunStatus.MODEL_FAILED);
        assertThat(governance.reservations()).singleElement().satisfies(reservation -> {
            assertThat(reservation.state).isEqualTo("UNKNOWN");
            assertThat(reservation.reason).isEqualTo("MODEL_FAILURE:TIMEOUT");
        });
        // E-54.6: the provider's request id stays with the uncertain call, to match it with the provider later.
        assertThat(governance.events()).anyMatch(event -> event.startsWith("UNKNOWN:")
                && event.endsWith(":MODEL_FAILURE:TIMEOUT:req-failed"));
        // Not zero any more (ADR-0027 section 22, gap 1): the next attempt starts with this cost spent.
        assertThat(result.estimatedCostUsd()).isPositive();
        assertThat(runs.only().usageComplete()).isFalse();
        assertThat(runs.only().outputTokens()).isEqualTo(1500);
    }

    @Test
    void anErrorResponseTheProviderDoesNotBillIsSettledAtZero() {
        model.thenFailure(ModelFailure.Type.RATE_LIMITED, true);

        AgentRunResult result = run();

        assertThat(result.stopReason()).isEqualTo("RATE_LIMITED");
        assertThat(governance.reservations()).singleElement().satisfies(reservation -> {
            assertThat(reservation.state).isEqualTo("SETTLED");
            assertThat(reservation.usage.inputTokens()).isZero();
        });
        assertThat(result.estimatedCostUsd()).isZero();
    }

    @Test
    void aCallThatCannotBeAttemptedReservesNothing() {
        model.then(request -> {
            throw new AssertionError("must not be called");
        });
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            AgentRunResult inTransaction = run();
            assertThat(inTransaction.stopReason()).isEqualTo("AI_GOVERNANCE:CALL_INSIDE_TRANSACTION");
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        assertThat(governance.reservations()).isEmpty();
        assertThat(model.requests()).isEmpty();
    }

    @Test
    void unpricedModelIsNotCalled() {
        AgentRunResult result = run(new ModelId("fake", "unpriced"), BigDecimal.ZERO);
        assertThat(result.stopReason()).isEqualTo("AI_GOVERNANCE:NOT_PRICED");
        assertThat(result.status()).isEqualTo(AgentRunStatus.MODEL_FAILED);
        assertThat(model.requests()).isEmpty();
        assertThat(governance.reservations()).as("nothing was reserved").isEmpty();
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

    /** ADR-0027 section 5: the run applies the limits of the policy it was given and records that policy. */
    @Test
    void aStricterRunPolicyIsAppliedAndRecordedOnEveryAttempt() {
        model.thenTools(call("t1", InventoryReadTools.FORECAST, "{\"productVariantId\":\"" + VARIANT + "\"}"))
                .thenTools(call("t2", InventoryReadTools.FORECAST, "{\"productVariantId\":\"" + VARIANT + "\"}"))
                .thenText(pause());
        InventoryAgentRunPolicy strict = policy("inventory-agent-run/9", 1, 600, 2);

        var proposal = proposer(strict).propose(scope, SnapshotFixture.OPPORTUNITY, snapshot);

        // One model call per run: both attempts stop after their first turn, and the answer is never requested.
        assertThat(proposal).isEmpty();
        List<AgentRun> attempts = runs.attempts(scope,
                InventoryAgentProposer.analysisKey(SnapshotFixture.OPPORTUNITY, SnapshotFixture.SNAPSHOT));
        assertThat(attempts).extracting(AgentRun::status, AgentRun::stopReason, AgentRun::runPolicy).containsOnly(
                org.assertj.core.groups.Tuple.tuple(AgentRunStatus.LIMIT_EXCEEDED, "MAX_MODEL_CALLS",
                        "inventory-agent-run/9"));
        assertThat(model.requests()).extracting(ModelRequest::maxOutputTokens).containsOnly(600);
        assertThat(runs.started.values()).extracting(start -> start.limits().recorded().get("maxModelCalls"))
                .containsOnly("1");
    }

    /** The policy bounds the attempts of one analysis even when the model policy lists more models. */
    @Test
    void maxAttemptsOfThePolicyStopsTheFallback() {
        model.thenFailure(ModelFailure.Type.UNAVAILABLE).thenText(pause());

        var proposal = proposer(policy("inventory-agent-run/9", 5, 1500, 1))
                .propose(scope, SnapshotFixture.OPPORTUNITY, snapshot);

        assertThat(proposal).isEmpty();
        assertThat(model.requests()).extracting(ModelRequest::model).containsExactly(PRIMARY);
        assertThat(runs.attempts(scope,
                InventoryAgentProposer.analysisKey(SnapshotFixture.OPPORTUNITY, SnapshotFixture.SNAPSHOT)))
                .hasSize(1);
    }

    /** ADR-0027 section 6.2: a run that is not admitted sends nothing, and the fallback is not tried either. */
    @Test
    void aRunOverTheStoreLimitIsNotAdmittedAndNothingIsReserved() {
        governance.admission(com.ecommerceagent.aigovernance.AiAdmission.Admission.STORE_LIMIT);
        model.thenText(pause());

        var decision = proposer().decide(scope, SnapshotFixture.OPPORTUNITY, snapshot);

        assertThat(decision.proposal()).isEmpty();
        assertThat(decision.fallback()).isEqualTo("AI_GOVERNANCE:STORE_LIMIT");
        assertThat(model.requests()).isEmpty();
        assertThat(governance.reservations()).isEmpty();
        AgentRun run = runs.only();
        assertThat(run.status()).isEqualTo(AgentRunStatus.LIMIT_EXCEEDED);
        assertThat(run.stopReason()).isEqualTo("AI_GOVERNANCE:STORE_LIMIT");
    }

    /** Every admitted run asks with its policy's store limit and gives its lease back, also when it fails. */
    @Test
    void anAdmittedRunReleasesItsLeaseWhenItEnds() {
        model.thenFailure(ModelFailure.Type.UNAVAILABLE).thenText(pause());

        assertThat(proposer().propose(scope, SnapshotFixture.OPPORTUNITY, snapshot)).isPresent();

        assertThat(governance.events()).filteredOn(event -> event.startsWith("ADMISSION:"))
                .containsExactly("ADMISSION:GRANTED:1", "ADMISSION:GRANTED:1");
        assertThat(governance.events()).filteredOn("LEASE_RELEASED"::equals).hasSize(2);
        assertThat(governance.leases()).isEmpty();
    }

    /** ADR-0027 section 12 (E-54.8): a store stop ends a running run before its next call, like the platform stop. */
    @Test
    void aStoreStopEndsARunningRunBeforeItsNextCall() {
        assertAScopedStopEndsTheRun(SendDecision.STORE_STOPPED);
    }

    /** ADR-0027 section 12 (E-54.8): so does an automation stop. */
    @Test
    void anAutomationStopEndsARunningRunBeforeItsNextCall() {
        assertAScopedStopEndsTheRun(SendDecision.AUTOMATION_STOPPED);
    }

    private void assertAScopedStopEndsTheRun(SendDecision stop) {
        hooks = List.of(new AgentHook() {
            int calls;

            @Override
            public void beforeModelCall(AgentRunContext run, ModelRequest request) {
                if (++calls == 2) {
                    governance.stop(stop);
                }
            }
        });
        model.thenTools(call("t1", InventoryReadTools.FORECAST, "{\"productVariantId\":\"" + VARIANT + "\"}"))
                .thenText(pause());

        var decision = proposer().decide(scope, SnapshotFixture.OPPORTUNITY, snapshot);

        assertThat(decision.proposal()).isEmpty();
        assertThat(decision.fallback()).isEqualTo("AI_GOVERNANCE:" + stop.name());
        assertThat(model.requests()).as("the second call was reserved but never sent").hasSize(1);
        assertThat(runs.only().status()).isEqualTo(AgentRunStatus.STOPPED);
        assertThat(runs.only().stopReason()).isEqualTo("AI_GOVERNANCE:" + stop.name());
        assertThat(governance.leases()).isEmpty();

        var next = proposer().decide(scope, SnapshotFixture.OPPORTUNITY, snapshot(UUID.randomUUID(), 0));
        assertThat(next.fallback()).as("no new run is admitted").isEqualTo("AI_GOVERNANCE:" + stop.name());
        assertThat(model.requests()).hasSize(1);
    }

    /** ADR-0027 section 12: the stop takes effect before the next call of a running run, without a restart. */
    @Test
    void thePlatformStopEndsARunningRunBeforeItsNextCall() {
        hooks = List.of(new AgentHook() {
            int calls;

            @Override
            public void beforeModelCall(AgentRunContext run, ModelRequest request) {
                if (++calls == 2) {
                    governance.stop(true);
                }
            }
        });
        model.thenTools(call("t1", InventoryReadTools.FORECAST, "{\"productVariantId\":\"" + VARIANT + "\"}"))
                .thenText(pause());

        var decision = proposer().decide(scope, SnapshotFixture.OPPORTUNITY, snapshot);

        assertThat(decision.proposal()).isEmpty();
        assertThat(decision.fallback()).isEqualTo("AI_GOVERNANCE:AI_STOPPED");
        assertThat(model.requests()).as("the second call was reserved but never sent").hasSize(1);
        assertThat(governance.events()).anyMatch(event -> event.startsWith("STOPPED:"));
        AgentRun run = runs.only();
        assertThat(run.status()).isEqualTo(AgentRunStatus.STOPPED);
        assertThat(run.stopReason()).isEqualTo("AI_GOVERNANCE:AI_STOPPED");
        assertThat(governance.leases()).isEmpty();

        // While the stop is on, a new analysis is not admitted at all.
        var next = proposer().decide(scope, SnapshotFixture.OPPORTUNITY, snapshot(UUID.randomUUID(), 0));
        assertThat(next.fallback()).isEqualTo("AI_GOVERNANCE:AI_STOPPED");
        assertThat(model.requests()).hasSize(1);
    }

    /** ADR-0027 section 5 (A1): a revoked access stops a running run before its next paid call. */
    @Test
    void aRevokedAccessEndsARunningRunBeforeItsNextCall() {
        hooks = List.of(new AgentHook() {
            int calls;

            @Override
            public void beforeModelCall(AgentRunContext run, ModelRequest request) {
                if (++calls == 2) {
                    accessRevoked = true;
                }
            }
        });
        model.thenTools(call("t1", InventoryReadTools.FORECAST, "{\"productVariantId\":\"" + VARIANT + "\"}"))
                .thenText(pause());

        var decision = proposer().decide(scope, SnapshotFixture.OPPORTUNITY, snapshot);

        assertThat(decision.proposal()).isEmpty();
        assertThat(decision.fallback()).isEqualTo("AI_GOVERNANCE:AUTHORIZATION_REVOKED");
        assertThat(model.requests()).as("the second call was never sent").hasSize(1);
        assertThat(governance.reservations()).as("nothing was reserved for the refused call").hasSize(1);
        AgentRun run = runs.only();
        assertThat(run.status()).isEqualTo(AgentRunStatus.STOPPED);
        assertThat(governance.leases()).isEmpty();
    }

    /** ADR-0027 section 5 (A1): a budget exhausted during a run applies before its next call, not at the next run. */
    @Test
    void aBudgetExhaustedDuringARunAppliesBeforeItsNextCall() {
        hooks = List.of(new AgentHook() {
            int calls;

            @Override
            public void beforeModelCall(AgentRunContext run, ModelRequest request) {
                if (++calls == 2) {
                    governance.deny("BUDGET_EXHAUSTED:STORE:MONTHLY");
                }
            }
        });
        model.thenTools(call("t1", InventoryReadTools.FORECAST, "{\"productVariantId\":\"" + VARIANT + "\"}"))
                .thenText(pause());

        var decision = proposer().decide(scope, SnapshotFixture.OPPORTUNITY, snapshot);

        assertThat(decision.fallback()).isEqualTo("AI_GOVERNANCE:BUDGET_EXHAUSTED:STORE:MONTHLY");
        assertThat(model.requests()).hasSize(1);
        assertThat(runs.only().status()).isEqualTo(AgentRunStatus.LIMIT_EXCEEDED);
    }

    /** ADR-0027 section 6.2 (E-54.3): a call over the provider's rate limits is never sent and frees its budget. */
    @Test
    void aCallOverTheProviderRateLimitIsNotSent() {
        governance.slot(com.ecommerceagent.aigovernance.AiProviderLimiter.Slot.REQUEST_LIMIT);

        AgentRunResult result = run();

        assertThat(result.status()).isEqualTo(AgentRunStatus.LIMIT_EXCEEDED);
        assertThat(result.stopReason()).isEqualTo("AI_GOVERNANCE:PROVIDER_LIMIT:REQUEST_LIMIT");
        assertThat(model.requests()).isEmpty();
        assertThat(governance.reservations()).singleElement()
                .satisfies(reservation -> assertThat(reservation.state).isEqualTo("RELEASED"));
    }

    /** A rate-limited answer pauses the model with the provider's retry-after; the slot is always given back. */
    @Test
    void aRateLimitedAnswerPausesTheModelAndEverySlotIsReleased() {
        model.then(request -> {
            throw new ModelFailure(ModelFailure.Type.RATE_LIMITED, "RateLimitException", Duration.ofSeconds(7), "r",
                    true, null);
        });

        AgentRunResult result = run();

        assertThat(result.status()).isEqualTo(AgentRunStatus.MODEL_FAILED);
        assertThat(governance.pauses()).containsExactly(PRIMARY.provider() + "/" + PRIMARY.name()
                + ":PT7S:RATE_LIMITED");
        assertThat(governance.openSlots()).isEmpty();
    }

    /** ADR-0027 section 7: a new snapshot inside the cooldown is analysed again only after a material change. */
    @Test
    void aNewSnapshotInsideTheCooldownIsAnalysedOnlyAfterAMaterialChange() {
        model.thenText(pause()).thenText(pause()).thenText(pause());
        assertThat(proposer().propose(scope, SnapshotFixture.OPPORTUNITY, snapshot)).isPresent();

        clock.advance(Duration.ofMinutes(20));
        var same = proposer().decide(scope, SnapshotFixture.OPPORTUNITY, snapshot(UUID.randomUUID(), 17));
        assertThat(same.proposal()).isEmpty();
        assertThat(same.fallback()).isEqualTo("AGENT_COOLDOWN");
        assertThat(model.requests()).hasSize(1);

        // Out of stock is a material change: analysed at once.
        assertThat(proposer().propose(scope, SnapshotFixture.OPPORTUNITY, snapshot(UUID.randomUUID(), 0)))
                .isPresent();
        assertThat(model.requests()).hasSize(2);

        // After the cooldown an unchanged situation is analysed again.
        clock.advance(Duration.ofMinutes(61));
        assertThat(proposer().propose(scope, SnapshotFixture.OPPORTUNITY, snapshot(UUID.randomUUID(), 0)))
                .isPresent();
        assertThat(model.requests()).hasSize(3);
    }

    /** A new snapshot of the serum with another id and stock. */
    ContextSnapshot snapshot(UUID id, int availableToSell) {
        ObjectNode content = (ObjectNode) snapshot.content().deepCopy();
        ((ObjectNode) content.get("inventory")).put("availableToSell", availableToSell);
        return new ContextSnapshot(id, snapshot.opportunityId(), snapshot.contentSchema(), snapshot.asOf(), content,
                CanonicalJson.hash(content), snapshot.evidenceIds(), snapshot.createdAt());
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
        settings = new AgentSettings(false, List.of(PRIMARY.toString()), "low");
        assertThat(proposer().propose(scope, SnapshotFixture.OPPORTUNITY, snapshot)).isEmpty();
        assertThat(model.requests()).isEmpty();
        assertThat(runs.started).isEmpty();
    }
}
