package com.ecommerceagent.integration;

import java.time.Instant;
import java.util.UUID;

/** One import run of a connection (ADR-0008). Counts are null until the step has finished. */
public record SyncRun(
        UUID id,
        UUID connectionId,
        SyncRunStatus status,
        Instant windowFrom,
        Instant windowUntil,
        Integer productsImported,
        Integer variantsImported,
        Integer ordersImported,
        Integer ordersExpected,
        String errorCode,
        Instant startedAt,
        Instant finishedAt) {
}
