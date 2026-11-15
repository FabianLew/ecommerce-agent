package com.ecommerceagent.automation.execution;

import java.time.Instant;
import java.util.UUID;

import org.springframework.modulith.NamedInterface;
import tools.jackson.databind.JsonNode;

/** The ledger row of one idempotency key (ADR-0020 point 4). */
@NamedInterface("execution")
public record ActionExecution(UUID id, String idempotencyKey, UUID authorizedActionId, String actionType,
        String targetType, UUID targetId, ExecutionStatus status, JsonNode preState, String requestHash,
        String providerRequestId, String resultCode, boolean writeSent, int attempts, int version, Instant createdAt,
        Instant updatedAt) {
}
