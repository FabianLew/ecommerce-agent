package com.ecommerceagent.integration;

import java.time.Instant;
import java.util.UUID;

/**
 * A store's Meta ad account connection as the API may show it: the token appears only as its last four characters
 * (ADR-0017).
 */
public record MetaConnection(UUID id, String accountId, MetaConnectionStatus status, String tokenHint,
        String accountName, String currency, boolean canWrite, Instant lastCheckAt, Instant lastSuccessAt,
        String lastErrorCode, Instant lastSyncAt, String lastSyncError, int adsSeen, Instant nextSyncAfter) {
}
