package com.ecommerceagent.intelligence.agent;

import java.time.Instant;

import tools.jackson.databind.JsonNode;

/** One model call or tool call of a run, in canonical form. */
public record AgentRunStep(int seq, Kind kind, String name, String toolCallId, String status, String errorCode,
        JsonNode payload, long durationMillis, Instant createdAt) {

    public enum Kind {
        MODEL_CALL,
        TOOL_CALL
    }
}
