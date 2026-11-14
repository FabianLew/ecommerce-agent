package com.ecommerceagent.decisions;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * DecisionProposal v1 (ADR-0012). The envelope is set by the backend, the recommendation by the proposer. A
 * model only ever produces one; the backend validates it, evaluates policy and decides what happens.
 */
public record DecisionProposal(String schemaVersion, UUID proposalId, int revision, DecisionSubject subject,
        Proposer proposer, UUID agentRunId, UUID contextSnapshotId, Instant createdAt, Instant expiresAt,
        Recommendation recommendation) {

    public static final String SCHEMA_VERSION = "1";

    public DecisionProposal {
        Objects.requireNonNull(proposalId, "proposalId");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(proposer, "proposer");
        Objects.requireNonNull(contextSnapshotId, "contextSnapshotId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(recommendation, "recommendation");
    }
}
