package com.ecommerceagent.commerce;

import java.time.Instant;
import java.util.Objects;

/** An ad as read from its platform (or fixture). */
public record AdData(String externalId, String accountExternalId, String name, AdStatus ownStatus,
        String effectiveStatus, String creativeFingerprint, Instant observedAt, AdSource source) {

    public AdData {
        Objects.requireNonNull(externalId, "externalId");
        Objects.requireNonNull(accountExternalId, "accountExternalId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(ownStatus, "ownStatus");
        Objects.requireNonNull(effectiveStatus, "effectiveStatus");
        Objects.requireNonNull(creativeFingerprint, "creativeFingerprint");
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(source, "source");
    }
}
