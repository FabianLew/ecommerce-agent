package com.ecommerceagent.integration;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A store's connection without any secret; safe to return from the API. */
public record IntegrationConnection(
        UUID id,
        IntegrationProvider provider,
        URI storeUrl,
        ConnectionStatus status,
        String keyHint,
        Instant lastCheckAt,
        Instant lastSuccessAt,
        ConnectionCheckError lastErrorCode,
        List<ConnectionWarning> lastCheckWarnings,
        String sourceCurrency,
        String webhookEndpointKey,
        Instant webhookSecretSetAt,
        Instant lastWebhookAt) {

    public IntegrationConnection {
        lastCheckWarnings = List.copyOf(lastCheckWarnings);
    }
}
