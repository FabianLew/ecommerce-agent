package com.ecommerceagent.automation.execution;

import java.time.Instant;
import java.util.UUID;

import org.springframework.modulith.NamedInterface;

/**
 * A stored, short-lived authorization of one action (ADR-0020 point 3). Only {@link ExecutionAuthority} writes it;
 * the executor loads it by id, so a value built anywhere else authorizes nothing.
 */
@NamedInterface("execution")
public record AuthorizedAction(UUID id, UUID proposalId, int revision, String payloadHash, String actionHash,
        UUID approvalId, UUID policyEvaluationId, String actionType, UUID connectionId, String targetType,
        UUID targetId, String targetExternalId, String parametersHash, String expectedStatus, int mappingVersion,
        String creativeFingerprint, String idempotencyKey, Instant issuedAt, Instant expiresAt) {

    public static final String PAUSE_AD = "PAUSE_AD";
    public static final String META_AD = "META_AD";

    public boolean validAt(Instant now) {
        return now.isBefore(expiresAt);
    }

    /** {@code {store}/{connection}/{proposal}/{revision}/PAUSE_AD/{externalAdId}} (document 03 §7). */
    public static String key(UUID storeId, UUID connectionId, UUID proposalId, int revision, String actionType,
            String targetExternalId) {
        return storeId + "/" + connectionId + "/" + proposalId + "/" + revision + "/" + actionType + "/"
                + targetExternalId;
    }
}
