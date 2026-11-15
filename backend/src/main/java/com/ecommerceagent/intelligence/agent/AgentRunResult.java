package com.ecommerceagent.intelligence.agent;

import java.math.BigDecimal;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/** How one attempt ended; {@code output} is the parsed JSON object when it succeeded. */
public record AgentRunResult(UUID runId, AgentRunStatus status, String stopReason, JsonNode output,
        BigDecimal estimatedCostUsd) {

    public boolean succeeded() {
        return status == AgentRunStatus.SUCCEEDED;
    }
}
