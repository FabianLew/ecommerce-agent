package com.ecommerceagent.intelligence.model;

import java.util.List;
import java.util.Objects;

/** The canonical result of one model step. */
public record ModelTurn(String text, List<ToolCall> toolCalls, FinishReason finishReason, ModelUsage usage,
        String providerRequestId, Object providerState) {

    public enum FinishReason {
        /** The model finished its answer. */
        COMPLETE,
        /** The model asks for tool calls. */
        TOOL_USE,
        /** The output limit cut the answer: it is neither a complete decision nor complete tool calls. */
        MAX_TOKENS,
        /** The provider declined the request. */
        REFUSAL,
        OTHER
    }

    public ModelTurn {
        Objects.requireNonNull(finishReason, "finishReason");
        Objects.requireNonNull(usage, "usage");
        toolCalls = List.copyOf(toolCalls);
        text = text == null ? "" : text;
    }

    public ModelMessage.Assistant asMessage() {
        return new ModelMessage.Assistant(text, toolCalls, providerState);
    }
}
