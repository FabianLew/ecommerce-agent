package com.ecommerceagent.automation.guardian;

/** The snapshot an analysis built, with the days of cover it saw (plain decimal string or null). */
public record ContextRef(String snapshotId, String daysOfCover) {
}
