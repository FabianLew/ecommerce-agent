package com.ecommerceagent.intelligence.agent;

import java.util.Objects;
import java.util.UUID;

import com.ecommerceagent.decisions.ContextSnapshot;
import com.ecommerceagent.identity.TrustedStoreScope;

/**
 * What the backend, not the model, decides for a tool call: the store scope, the run and the frozen snapshot of the
 * analysis. Nothing in a tool's arguments can widen it.
 */
public record ToolExecutionContext(TrustedStoreScope scope, UUID runId, ContextSnapshot snapshot) {

    public ToolExecutionContext {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(snapshot, "snapshot");
    }
}
