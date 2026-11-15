package com.ecommerceagent.intelligence.model;

import java.util.List;
import java.util.Objects;

/** A canonical message of a run's history. */
public sealed interface ModelMessage {

    /** Task or repair instruction from the backend. */
    record User(String text) implements ModelMessage {

        public User {
            Objects.requireNonNull(text, "text");
        }
    }

    /**
     * What the model returned. {@code providerState} is the adapter's own message, kept only for the rest of the
     * same attempt (ADR-0016 point 2); it is never stored or given to another adapter.
     */
    record Assistant(String text, List<ToolCall> toolCalls, Object providerState) implements ModelMessage {

        public Assistant {
            toolCalls = List.copyOf(toolCalls);
        }
    }

    /** The results of all tool calls of the previous assistant message, in one message. */
    record ToolResults(List<ToolResult> results) implements ModelMessage {

        public ToolResults {
            results = List.copyOf(results);
        }
    }

    record ToolResult(String toolCallId, String toolName, String content, boolean error) {

        public ToolResult {
            Objects.requireNonNull(toolCallId, "toolCallId");
            Objects.requireNonNull(toolName, "toolName");
            Objects.requireNonNull(content, "content");
        }
    }
}
