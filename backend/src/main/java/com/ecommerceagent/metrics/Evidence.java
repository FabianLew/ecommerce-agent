package com.ecommerceagent.metrics;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/** A stored, immutable piece of evidence (ADR-0011). New facts are new evidence, never a new version. */
public record Evidence(UUID id, int version, EvidenceType type, String resourceRef, String definitionVersion,
        JsonNode content, String contentHash, List<UUID> inputEvidenceIds, Instant observedAt, Instant availableAt,
        Instant computedAt) {

    public Evidence {
        inputEvidenceIds = List.copyOf(inputEvidenceIds);
    }

    public EvidenceKind kind() {
        return type.kind();
    }
}
