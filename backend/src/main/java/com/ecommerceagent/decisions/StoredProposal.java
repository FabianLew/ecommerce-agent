package com.ecommerceagent.decisions;

import java.time.Instant;

/** A persisted proposal: immutable content and hash, and its current status. */
public record StoredProposal(DecisionProposal proposal, String payloadHash, ProposalStatus status,
        Instant statusChangedAt) {
}
