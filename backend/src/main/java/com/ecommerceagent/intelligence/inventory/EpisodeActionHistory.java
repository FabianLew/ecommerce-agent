package com.ecommerceagent.intelligence.inventory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.ecommerceagent.identity.TrustedStoreScope;

/**
 * The executed actions of an episode's proposals with their ledger state, frozen into the snapshot's
 * {@code recentActions} section (ADR-0016 point 4). The execution module implements it; intelligence never reads the
 * ledger itself.
 */
public interface EpisodeActionHistory {

    record ExecutedAction(UUID proposalId, int revision, String actionType, String targetExternalId, String status,
            Instant updatedAt) {
    }

    /** The executions of the proposals, oldest first. */
    List<ExecutedAction> executed(TrustedStoreScope scope, List<UUID> proposalIds);
}
