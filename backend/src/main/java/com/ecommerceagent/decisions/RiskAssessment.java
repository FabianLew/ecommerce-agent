package com.ecommerceagent.decisions;

import java.time.Instant;

/**
 * Result of one detector run: the observation, the active episode (if any), whether this run opened it, and
 * when the series needs another observation to open an episode ({@code null} when it does not).
 */
public record RiskAssessment(RiskObservation observation, Opportunity opportunity, boolean opened,
        Instant nextCheckAt) {
}
