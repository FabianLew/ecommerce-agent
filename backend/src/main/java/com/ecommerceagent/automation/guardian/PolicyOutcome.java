package com.ecommerceagent.automation.guardian;

/** Result of evaluating a policy for a proposed action (02 §9, ADR-0012). */
public enum PolicyOutcome {
    ALLOW,
    REQUIRE_APPROVAL,
    DENY,
    DEFER
}
