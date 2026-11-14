package com.ecommerceagent.decisions;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * The context one analysis saw (ADR-0011): immutable content with its hash and the evidence it refers to.
 * {@code opportunityId} is null only for contexts built outside an episode (tests, offline evaluation).
 */
public record ContextSnapshot(UUID id, UUID opportunityId, String contentSchema, Instant asOf, JsonNode content,
        String contentHash, List<UUID> evidenceIds, Instant createdAt) {

    public ContextSnapshot {
        evidenceIds = List.copyOf(evidenceIds);
    }
}
