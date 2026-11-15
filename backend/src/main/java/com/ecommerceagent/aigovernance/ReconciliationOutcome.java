package com.ecommerceagent.aigovernance;

/** How an {@code UNKNOWN} reservation is closed (ADR-0027 section 4.5). */
public enum ReconciliationOutcome {
    /** The provider confirmed the call was not charged: the whole reservation goes back to the budgets. */
    NOT_CHARGED,
    /** The provider confirmed a charge: that amount is recorded in full, even above the reservation. */
    CHARGED,
    /** No provider data: the full reserved amount is recorded as spent. */
    CONSERVATIVE
}
