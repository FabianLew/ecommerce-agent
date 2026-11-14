package com.ecommerceagent.automation.guardian;

/** What the variant workflow saw: the detector outcome and days of cover (plain decimal string or null). */
public record RiskSignal(String outcome, String daysOfCover) {
}
