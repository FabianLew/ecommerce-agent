package com.ecommerceagent.intelligence.agent;

import java.util.Objects;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** A tool's result as the model gets it: data, or an error body with a code. {@code error} is null on success. */
public record ToolOutcome(JsonNode body, ToolError error) {

    public enum ToolError {
        NOT_FOUND,
        FORBIDDEN,
        STALE_DATA,
        RATE_LIMITED,
        UNAVAILABLE,
        /** Set by the dispatcher: the name is not in the run's allowed set; nothing ran. */
        TOOL_NOT_ALLOWED,
        /** Set by the dispatcher: the arguments do not match the schema; nothing ran. */
        INVALID_ARGUMENTS,
        /** Set by the dispatcher: the result broke the tool's contract; it is not passed on. */
        INVALID_RESULT,
        /** Set by the dispatcher: a run limit stopped the call; nothing ran. */
        LIMIT_REACHED;

        public boolean retryable() {
            return this == RATE_LIMITED || this == UNAVAILABLE;
        }
    }

    public ToolOutcome {
        Objects.requireNonNull(body, "body");
    }

    public static ToolOutcome ok(JsonNode data) {
        return new ToolOutcome(data, null);
    }

    public static ToolOutcome failed(ToolError error, String message) {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("error", error.name());
        body.put("message", message);
        body.put("retryable", error.retryable());
        return new ToolOutcome(body, error);
    }

    public boolean failed() {
        return error != null;
    }
}
