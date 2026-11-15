package com.ecommerceagent.intelligence.agent;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ecommerceagent.intelligence.model.AiCallDenied;
import com.ecommerceagent.intelligence.model.GovernedCall;
import com.ecommerceagent.intelligence.model.GovernedModelFailure;
import com.ecommerceagent.intelligence.model.GovernedModelGateway;
import com.ecommerceagent.intelligence.model.ModelFailure;
import com.ecommerceagent.intelligence.model.ModelMessage;
import com.ecommerceagent.intelligence.model.ModelRequest;
import com.ecommerceagent.intelligence.model.ModelTurn;
import com.ecommerceagent.intelligence.model.ModelUsage;
import com.ecommerceagent.intelligence.model.ToolCall;
import com.ecommerceagent.intelligence.model.ToolSpec;
import com.ecommerceagent.metrics.CanonicalJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Our own agent loop (document 02 §3, ADR-0016 point 5). The gateway performs one model step; tool calls go only
 * through the dispatcher; every limit ends the run with an explicit status; the final answer must be one JSON
 * object, with at most the configured number of repair requests. The runtime never submits or executes anything:
 * the caller gets the output and the backend validates it.
 */
@Service
public class AgentRuntime {

    static final String REPAIR_MESSAGE = "Your last answer was not one complete JSON object. Answer again with only "
            + "the recommendation as one JSON object following the schema, with no other text.";
    /** Characters per token for the conservative request estimate; real usage settles the cost after the call. */
    private static final int CHARS_PER_TOKEN = 3;
    private static final Logger log = LoggerFactory.getLogger(AgentRuntime.class);
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final GovernedModelGateway gateway;
    private final ToolRegistry registry;
    private final AgentRuns runs;
    private final List<AgentHook> hooks;
    private final AgentSettings settings;
    private final Clock clock;

    public AgentRuntime(GovernedModelGateway gateway, ToolRegistry registry, AgentRuns runs, List<AgentHook> hooks,
            AgentSettings settings, Clock clock) {
        this.gateway = gateway;
        this.registry = registry;
        this.runs = runs;
        this.hooks = List.copyOf(hooks);
        this.settings = settings;
        this.clock = clock;
    }

    public AgentRunResult run(AgentRunRequest request) {
        return new Run(request).execute();
    }

    /** The state of one run; not shared between threads. */
    private final class Run {

        private final AgentRunRequest request;
        private final RunLimits limits;
        private final UUID id = UUID.randomUUID();
        private final ToolRegistry.ToolSet tools;
        private final AgentRunContext context;
        private final List<ModelMessage> messages = new ArrayList<>();
        private final PriceList prices;
        private int seq;
        private int modelCalls;
        private int toolCalls;
        private int repairCalls;
        private int inputTokens;
        private int outputTokens;
        private boolean usageComplete = true;
        private BigDecimal cost = BigDecimal.ZERO;

        Run(AgentRunRequest request) {
            this.request = request;
            this.limits = request.agent().limits();
            this.tools = registry.forRun(request.agent(), request.skill().requiredReadTools());
            this.context = new AgentRunContext(id, request.scope(), request.opportunityId(), request.snapshot(),
                    request.agent(), request.model(), request.attempt());
            this.prices = settings.prices();
        }

        AgentRunResult execute() {
            Instant started = clock.instant();
            runs.start(request.scope(), new AgentRuns.Start(id, request.analysisKey(), request.attempt(),
                    request.opportunityId(), request.snapshot().id(), request.agent(), request.skill().reference(),
                    request.skill().hash(), request.prompts().hash(), tools.hash(), request.model().provider(),
                    request.model().name(), prices.version(), started));
            try {
                return loop(started);
            } catch (AgentHook.HookVeto veto) {
                return finish(AgentRunStatus.STOPPED, veto.code(), null);
            } catch (RuntimeException e) {
                log.warn("Agent run {} failed: {}", id, e.toString());
                return finish(AgentRunStatus.MODEL_FAILED, "RUNTIME_ERROR", null);
            }
        }

        private AgentRunResult loop(Instant started) {
            Optional<PriceList.Price> price = prices.price(request.model());
            if (price.isEmpty()) {
                return finish(AgentRunStatus.MODEL_FAILED, "MODEL_NOT_PRICED", null);
            }
            hooks.forEach(hook -> hook.beforeAgentRun(context));
            Instant deadline = started.plus(limits.maxWallTime());
            gateway.capabilities(request.model());
            List<ToolSpec> specs = tools.definitions().stream().map(ToolDefinition::spec).toList();
            ToolDispatcher dispatcher = new ToolDispatcher(tools, new ToolExecutionContext(request.scope(), id,
                    request.snapshot()));
            messages.add(new ModelMessage.User(request.task()));
            boolean repairing = false;
            while (true) {
                if (modelCalls >= limits.maxModelCalls()) {
                    return finish(AgentRunStatus.LIMIT_EXCEEDED, "MAX_MODEL_CALLS", null);
                }
                if (!clock.instant().isBefore(deadline)) {
                    return finish(AgentRunStatus.LIMIT_EXCEEDED, "MAX_WALL_TIME", null);
                }
                int outputCap = Math.min(settings.maxOutputTokensPerCall(), limits.maxOutputTokensTotal() - outputTokens);
                if (outputCap <= 0) {
                    return finish(AgentRunStatus.LIMIT_EXCEEDED, "MAX_OUTPUT_TOKENS", null);
                }
                List<ToolSpec> offered = repairing ? List.of() : specs;
                int estimatedInput = estimate(offered);
                if (estimatedInput > limits.maxInputTokens()) {
                    return finish(AgentRunStatus.LIMIT_EXCEEDED, "MAX_INPUT_TOKENS", null);
                }
                BigDecimal reserved = price.get().cost(estimatedInput, outputCap);
                if (request.costSpentBefore().add(cost).add(reserved).compareTo(limits.maxEstimatedCostUsd()) > 0) {
                    return finish(AgentRunStatus.LIMIT_EXCEEDED, "MAX_COST", null);
                }
                ModelRequest call = new ModelRequest(id, request.model(), request.system(), messages, offered,
                        outputCap, deadline, settings.effort());
                for (AgentHook hook : hooks) {
                    hook.beforeModelCall(context, call);
                }
                Instant callStarted = clock.instant();
                ModelTurn turn;
                try {
                    modelCalls++;
                    turn = gateway.complete(request.scope(), new GovernedCall(automationType(), call, modelCalls,
                            estimatedInput)).turn();
                } catch (AiCallDenied denied) {
                    // Nothing was sent: no cost, and the run ends on the governance decision (ADR-0027).
                    step(AgentRunStep.Kind.MODEL_CALL, request.model().toString(), null, "DENIED", denied.reason(),
                            deniedPayload(denied), callStarted);
                    return finish(denied.budget() ? AgentRunStatus.LIMIT_EXCEEDED : AgentRunStatus.MODEL_FAILED,
                            "AI_GOVERNANCE:" + denied.reason(), null);
                } catch (ModelFailure failure) {
                    if (failure instanceof GovernedModelFailure governedFailure && governedFailure.possiblyCharged()) {
                        // The request may have been charged: it counts against the run at its reserved maximum.
                        usageComplete = false;
                        inputTokens += estimatedInput;
                        outputTokens += outputCap;
                        cost = cost.add(reserved);
                    }
                    step(AgentRunStep.Kind.MODEL_CALL, request.model().toString(), null, "FAILED",
                            failure.type().name(), failurePayload(failure), callStarted);
                    return finish(AgentRunStatus.MODEL_FAILED, failure.type().name(), null);
                }
                settle(turn.usage(), estimatedInput, outputCap, price.get());
                step(AgentRunStep.Kind.MODEL_CALL, request.model().toString(), null, turn.finishReason().name(), null,
                        turnPayload(turn), callStarted);
                for (AgentHook hook : hooks) {
                    hook.afterModelCall(context, turn);
                }
                if (turn.finishReason() == ModelTurn.FinishReason.REFUSAL) {
                    return finish(AgentRunStatus.REFUSED, "REFUSAL", null);
                }
                boolean truncated = turn.finishReason() == ModelTurn.FinishReason.MAX_TOKENS;
                if (!turn.toolCalls().isEmpty() && !truncated && !repairing) {
                    if (toolCalls + turn.toolCalls().size() > limits.maxToolCalls()) {
                        return finish(AgentRunStatus.LIMIT_EXCEEDED, "MAX_TOOL_CALLS", null);
                    }
                    messages.add(turn.asMessage());
                    List<ModelMessage.ToolResult> results = new ArrayList<>();
                    for (ToolCall toolCall : turn.toolCalls()) {
                        results.add(callTool(dispatcher, toolCall));
                    }
                    messages.add(new ModelMessage.ToolResults(results));
                    continue;
                }
                Optional<JsonNode> output = truncated || !turn.toolCalls().isEmpty() ? Optional.empty()
                        : parse(turn.text());
                if (output.isPresent()) {
                    return finish(AgentRunStatus.SUCCEEDED, null, output.get());
                }
                if (repairCalls >= limits.maxOutputRepairCalls()) {
                    return finish(AgentRunStatus.OUTPUT_INVALID, truncated ? "TRUNCATED" : "NOT_JSON", null);
                }
                repairCalls++;
                repairing = true;
                // A truncated or tool-carrying answer is not kept as a turn; the repair asks for the JSON alone.
                messages.add(new ModelMessage.Assistant(turn.text(), List.of(), null));
                messages.add(new ModelMessage.User(REPAIR_MESSAGE));
            }
        }

        private ModelMessage.ToolResult callTool(ToolDispatcher dispatcher, ToolCall call) {
            Instant started = clock.instant();
            toolCalls++;
            ToolOutcome outcome;
            boolean cached = false;
            try {
                for (AgentHook hook : hooks) {
                    hook.beforeToolCall(context, call);
                }
                ToolDispatcher.Dispatch dispatch = dispatcher.dispatch(call);
                outcome = dispatch.outcome();
                cached = dispatch.cached();
                for (AgentHook hook : hooks) {
                    hook.afterToolCall(context, call, outcome);
                }
            } catch (AgentHook.HookVeto veto) {
                outcome = ToolOutcome.failed(ToolOutcome.ToolError.FORBIDDEN, veto.getMessage());
            }
            ObjectNode payload = NODES.objectNode();
            payload.set("arguments", arguments(call.argumentsJson()));
            payload.set("result", outcome.body());
            payload.put("cached", cached);
            step(AgentRunStep.Kind.TOOL_CALL, call.name(), call.id(), outcome.failed() ? "ERROR" : "OK",
                    outcome.failed() ? outcome.error().name() : null, payload, started);
            return new ModelMessage.ToolResult(call.id(), call.name(), CanonicalJson.write(outcome.body()),
                    outcome.failed());
        }

        private void settle(ModelUsage usage, int estimatedInput, int outputCap, PriceList.Price price) {
            int in = usage.inputTokens() != null ? usage.inputTokens() : estimatedInput;
            int out = usage.outputTokens() != null ? usage.outputTokens() : outputCap;
            if (!usage.known()) {
                usageComplete = false;
            }
            inputTokens += in;
            outputTokens += out;
            cost = cost.add(price.cost(in, out));
        }

        /** The agent's name as the automation of its budget and ledger rows. */
        private String automationType() {
            String name = request.agent().name().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
            return name.length() > 64 ? name.substring(0, 64) : name;
        }

        private int estimate(List<ToolSpec> offered) {
            long chars = request.system().length();
            for (ToolSpec spec : offered) {
                chars += spec.name().length() + spec.description().length() + spec.inputSchema().length();
            }
            for (ModelMessage message : messages) {
                chars += switch (message) {
                    case ModelMessage.User user -> user.text().length();
                    case ModelMessage.Assistant assistant -> assistant.text().length() + assistant.toolCalls().stream()
                            .mapToInt(call -> call.name().length() + call.argumentsJson().length()).sum();
                    case ModelMessage.ToolResults results -> results.results().stream()
                            .mapToInt(result -> result.content().length()).sum();
                };
            }
            return (int) Math.min(Integer.MAX_VALUE, chars / CHARS_PER_TOKEN + 1);
        }

        private void step(AgentRunStep.Kind kind, String name, String toolCallId, String status, String errorCode,
                JsonNode payload, Instant started) {
            Instant now = clock.instant();
            runs.step(request.scope(), id, new AgentRunStep(++seq, kind, name, toolCallId, status, errorCode, payload,
                    Math.max(0, Duration.between(started, now).toMillis()), now));
        }

        private AgentRunResult finish(AgentRunStatus status, String stopReason, JsonNode output) {
            runs.finish(request.scope(), id, new AgentRuns.Finish(status, stopReason, modelCalls, toolCalls,
                    repairCalls, inputTokens, outputTokens, usageComplete, cost, output, clock.instant()));
            return new AgentRunResult(id, status, stopReason, output, cost);
        }
    }

    /** One JSON object, optionally inside a Markdown code fence; anything else is not an answer. */
    static Optional<JsonNode> parse(String text) {
        String body = text == null ? "" : text.strip();
        if (body.startsWith("```")) {
            int firstLine = body.indexOf('\n');
            int fence = body.lastIndexOf("```");
            if (firstLine < 0 || fence <= firstLine) {
                return Optional.empty();
            }
            body = body.substring(firstLine + 1, fence).strip();
        }
        if (!body.startsWith("{") || !body.endsWith("}")) {
            return Optional.empty();
        }
        try {
            JsonNode node = CanonicalJson.read(body);
            return node.isObject() ? Optional.of(node) : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static JsonNode arguments(String json) {
        try {
            return CanonicalJson.read(json);
        } catch (RuntimeException e) {
            return NODES.stringNode(json);
        }
    }

    private static JsonNode turnPayload(ModelTurn turn) {
        ObjectNode payload = NODES.objectNode();
        payload.put("text", turn.text());
        ArrayNode calls = payload.putArray("toolCalls");
        for (ToolCall call : turn.toolCalls()) {
            calls.addObject().put("id", call.id()).put("name", call.name()).set("arguments",
                    arguments(call.argumentsJson()));
        }
        payload.put("finishReason", turn.finishReason().name());
        ObjectNode usage = payload.putObject("usage");
        if (turn.usage().inputTokens() == null) {
            usage.putNull("inputTokens");
        } else {
            usage.put("inputTokens", turn.usage().inputTokens());
        }
        if (turn.usage().outputTokens() == null) {
            usage.putNull("outputTokens");
        } else {
            usage.put("outputTokens", turn.usage().outputTokens());
        }
        payload.put("providerRequestId", turn.providerRequestId());
        return payload;
    }

    private static JsonNode deniedPayload(AiCallDenied denied) {
        ObjectNode payload = NODES.objectNode();
        payload.put("governance", denied.reason());
        return payload;
    }

    private static JsonNode failurePayload(ModelFailure failure) {
        ObjectNode payload = NODES.objectNode();
        payload.put("type", failure.type().name());
        payload.put("retryable", failure.retryable());
        payload.put("providerRequestId", failure.providerRequestId());
        if (failure instanceof GovernedModelFailure governed) {
            payload.put("possiblyCharged", governed.possiblyCharged());
        }
        return payload;
    }
}
