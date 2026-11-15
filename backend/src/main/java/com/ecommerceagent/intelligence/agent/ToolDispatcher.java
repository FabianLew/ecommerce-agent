package com.ecommerceagent.intelligence.agent;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.ecommerceagent.intelligence.model.ToolCall;
import com.ecommerceagent.metrics.CanonicalJson;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * Runs the tool calls of one run (document 02 §3 step 6). It never calls anything by a name the model supplied
 * unless that name is in the run's tool set; arguments are validated against the tool's closed schema; an identical
 * repeated call is served from the run's cache. Tool exceptions become {@code UNAVAILABLE}.
 */
public final class ToolDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ToolDispatcher.class);
    private static final SchemaRegistry SCHEMAS = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    private static final Map<String, Schema> COMPILED = new ConcurrentHashMap<>();

    private final ToolRegistry.ToolSet tools;
    private final ToolExecutionContext context;
    private final Map<String, ToolOutcome> cache = new HashMap<>();
    private int executions;

    public ToolDispatcher(ToolRegistry.ToolSet tools, ToolExecutionContext context) {
        this.tools = tools;
        this.context = context;
    }

    /** The outcome of one call, and whether it was served from the cache. */
    public record Dispatch(ToolOutcome outcome, boolean cached, long durationMillis) {
    }

    public Dispatch dispatch(ToolCall call) {
        long started = System.nanoTime();
        AgentTool tool = tools.get(call.name());
        if (tool == null) {
            return new Dispatch(ToolOutcome.failed(ToolOutcome.ToolError.TOOL_NOT_ALLOWED,
                    "No tool named " + call.name() + " is available in this analysis."), false, 0);
        }
        JsonNode arguments;
        try {
            arguments = CanonicalJson.read(call.argumentsJson());
        } catch (RuntimeException e) {
            return new Dispatch(ToolOutcome.failed(ToolOutcome.ToolError.INVALID_ARGUMENTS,
                    "Arguments are not valid JSON."), false, 0);
        }
        List<com.networknt.schema.Error> errors = schema(tool.definition()).validate(arguments);
        if (!errors.isEmpty()) {
            return new Dispatch(ToolOutcome.failed(ToolOutcome.ToolError.INVALID_ARGUMENTS,
                    "Arguments do not match the schema: " + errors.getFirst().getMessage()), false, 0);
        }
        String key = call.name() + ":" + CanonicalJson.write(arguments);
        ToolOutcome cached = cache.get(key);
        if (cached != null) {
            return new Dispatch(cached, true, 0);
        }
        ToolOutcome outcome;
        try {
            executions++;
            outcome = tool.execute(context, arguments);
        } catch (RuntimeException e) {
            log.warn("Tool {} failed in run {}: {}", call.name(), context.runId(), e.toString());
            outcome = ToolOutcome.failed(ToolOutcome.ToolError.UNAVAILABLE, "The tool failed; try again later.");
        }
        int size = CanonicalJson.write(outcome.body()).getBytes(StandardCharsets.UTF_8).length;
        if (size > tool.definition().maxResultBytes()) {
            outcome = ToolOutcome.failed(ToolOutcome.ToolError.INVALID_RESULT, "The result was too large.");
        }
        cache.put(key, outcome);
        return new Dispatch(outcome, false, (System.nanoTime() - started) / 1_000_000);
    }

    /** How many times a tool implementation actually ran. */
    public int executions() {
        return executions;
    }

    private static Schema schema(ToolDefinition definition) {
        return COMPILED.computeIfAbsent(definition.name() + "@" + definition.version(),
                key -> SCHEMAS.getSchema(CanonicalJson.write(definition.inputSchema()), InputFormat.JSON));
    }
}
