package com.ecommerceagent.intelligence.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One model step: the trusted run id, the model, the system text, the history, the tools the model may ask for, the
 * output token limit of this call and the deadline of the run.
 */
public record ModelRequest(UUID runId, ModelId model, String system, List<ModelMessage> messages,
        List<ToolSpec> tools, int maxOutputTokens, Instant deadline, String effort) {

    public ModelRequest {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(system, "system");
        Objects.requireNonNull(deadline, "deadline");
        messages = List.copyOf(messages);
        tools = List.copyOf(tools);
        if (maxOutputTokens <= 0) {
            throw new IllegalArgumentException("maxOutputTokens must be positive");
        }
    }
}
