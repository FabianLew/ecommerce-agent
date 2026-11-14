package com.ecommerceagent.automation;

import java.util.List;

/** Result of the workflow scope diagnostic: what the activity saw after verification. */
public record ScopeCheckResult(String organizationId, String storeId, String actorKind, String userId,
        String service, List<String> roles, List<String> permissions, String correlationId, String workflowId) {
}
