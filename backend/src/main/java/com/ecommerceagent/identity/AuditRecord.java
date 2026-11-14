package com.ecommerceagent.identity;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A stored audit event of one store. The actor is a user (id and display name, never the e-mail) or a system
 * service; details are identifiers and codes only (ADR-0004).
 */
public record AuditRecord(UUID id, Instant occurredAt, String action, UUID actorUserId, String actorDisplayName,
        String actorService, String resourceType, String resourceId, Map<String, String> details) {

    public AuditRecord {
        details = Map.copyOf(details);
    }
}
