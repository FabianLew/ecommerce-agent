package com.ecommerceagent.commerce;

import java.time.Instant;
import java.util.UUID;

public record Ad(UUID id, String platform, String externalId, String accountExternalId, String name,
        AdStatus ownStatus, String effectiveStatus, String creativeFingerprint, Instant observedAt, AdSource source) {
}
