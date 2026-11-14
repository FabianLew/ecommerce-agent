package com.ecommerceagent.decisions;

import java.time.Instant;
import java.util.UUID;

/** One episode of a problem for one subject (ADR-0011). The subject is a product variant in the pilot. */
public record Opportunity(UUID id, OpportunityCategory category, String subjectType, UUID subjectId,
        OpportunityStatus status, int version, String closeReason, Instant openedAt, Instant updatedAt,
        Instant closedAt) {

    public static final String PRODUCT_VARIANT = "PRODUCT_VARIANT";
}
