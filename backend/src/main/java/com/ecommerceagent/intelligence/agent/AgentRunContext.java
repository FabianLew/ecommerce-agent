package com.ecommerceagent.intelligence.agent;

import java.util.UUID;

import com.ecommerceagent.decisions.ContextSnapshot;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.intelligence.model.ModelId;

/** The trusted facts of one run, as the backend set them (document 02 §3 step 1). */
public record AgentRunContext(UUID runId, TrustedStoreScope scope, UUID opportunityId, ContextSnapshot snapshot,
        AgentDefinition agent, ModelId model, int attempt) {
}
