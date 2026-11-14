package com.ecommerceagent.automation.inventory;

/**
 * Result of one risk assessment (B-19, ADR-0011). {@code nextCheckInSeconds} is set when a risk series is
 * building up and needs another fresh read; the delay comes from the activity so the workflow keeps its own
 * clock. {@code daysOfCover} is a plain decimal string or null (absent in histories recorded before B-22).
 */
public record RiskCheck(String outcome, String reason, String opportunityId, boolean opened,
        Long nextCheckInSeconds, String daysOfCover) {
}
