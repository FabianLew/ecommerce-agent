package com.ecommerceagent.web;

import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** {@code meta} of a read response (04 §3, ADR-0014 point 9). */
record ReadMeta(
        String requestId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant generatedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant asOf,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String timezone,
        String dataVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Completeness completeness,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> warnings) {

    enum Completeness {
        COMPLETE,
        PARTIAL
    }

    ReadMeta {
        warnings = List.copyOf(warnings);
    }

    static ReadMeta live(String requestId, Instant now, String timezone) {
        return new ReadMeta(requestId, now, now, timezone, null, Completeness.COMPLETE, List.of());
    }
}
