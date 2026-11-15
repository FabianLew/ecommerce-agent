package com.ecommerceagent.aigovernance;

/**
 * Published once for each newly recorded budget alert, after it is committed, for a channel that tells platform
 * operators (e-mail or chat). Identifiers and amounts only.
 */
public record AiBudgetThresholdReached(AiBudgetAlert alert) {
}
