package com.ecommerceagent.intelligence.agent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/** A stored agent run (ADR-0016 point 6). */
public record AgentRun(UUID id, String analysisKey, int attempt, UUID opportunityId, UUID contextSnapshotId,
        String agentName, String agentVersion, String skillRef, String knowledgeHash, String promptHash,
        String toolsHash, String outputSchemaVersion, String modelPolicy, String provider, String model,
        String priceList, String runPolicy, AgentRunStatus status, String stopReason, int modelCalls, int toolCalls,
        int repairCalls, int inputTokens, int outputTokens, boolean usageComplete, BigDecimal estimatedCostUsd,
        JsonNode output, Instant startedAt, Instant finishedAt) {
}
