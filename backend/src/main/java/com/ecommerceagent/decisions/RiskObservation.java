package com.ecommerceagent.decisions;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One assessment of a variant's stock risk (ADR-0011); {@code reason} explains UNKNOWN. */
public record RiskObservation(UUID id, UUID variantId, Instant observedAt, Instant stockConfirmedAt,
        Integer availableToSell, BigDecimal daysOfCover, RiskOutcome outcome, String reason, BigDecimal thresholdDays,
        String detectorVersion, UUID opportunityId) {
}
