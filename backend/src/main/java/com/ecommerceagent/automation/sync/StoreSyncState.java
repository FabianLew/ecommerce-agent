package com.ecommerceagent.automation.sync;

import java.util.List;

/** Input of {@link StoreSyncWorkflow}, carried over {@code continueAsNew}. */
public record StoreSyncState(String connectionId, List<String> recentEventIds, long refreshDelaySeconds) {

    public StoreSyncState {
        recentEventIds = recentEventIds == null ? List.of() : List.copyOf(recentEventIds);
    }
}
