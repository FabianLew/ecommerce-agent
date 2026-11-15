package com.ecommerceagent.automation.guardian;

import org.springframework.modulith.NamedInterface;

/** Result of evaluating a policy for a proposed action (02 §9, ADR-0012). */
@NamedInterface("guardian")
public enum PolicyOutcome {
    ALLOW,
    REQUIRE_APPROVAL,
    DENY,
    DEFER
}
