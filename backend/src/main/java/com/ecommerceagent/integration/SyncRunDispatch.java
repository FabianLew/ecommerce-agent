package com.ecommerceagent.integration;

import java.time.Instant;
import java.util.UUID;

/**
 * A running import as the reconciler sees it (FIX-02): identifiers only, no store data. The store comes from
 * the run record, so a system scope built from it is bound to the trusted record, not to any caller input.
 * {@code workflowStartedAt} is null until the workflow start has been confirmed.
 */
public record SyncRunDispatch(UUID runId, UUID organizationId, UUID storeId, Instant startedAt,
        Instant workflowStartedAt) {

    public boolean workflowStarted() {
        return workflowStartedAt != null;
    }
}
