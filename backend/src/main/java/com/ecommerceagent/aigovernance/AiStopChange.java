package com.ecommerceagent.aigovernance;

import java.time.Instant;
import java.util.UUID;

/**
 * One stop or resume decision for a scoped AI stop (ADR-0027 section 12): a store ({@code automationType} null), an
 * automation on every store ({@code storeId} null) or an automation on one store.
 */
public record AiStopChange(long id, UUID organizationId, UUID storeId, String automationType, boolean stopped,
        String reason, String changedBy, Instant changedAt) {

    /** {@code STORE}, {@code AUTOMATION} or {@code STORE_AUTOMATION}. */
    public String level() {
        return storeId == null ? "AUTOMATION" : automationType == null ? "STORE" : "STORE_AUTOMATION";
    }
}
