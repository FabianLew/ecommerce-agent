package com.ecommerceagent.automation.notifications;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.modulith.NamedInterface;

/** A stored notification as a person sees it; {@code read} is that person's mark. */
@NamedInterface("notifications")
public record Notification(UUID id, NotificationKind kind, NotificationKind.Severity severity, String code,
        Map<String, String> parameters, UUID opportunityId, UUID proposalId, UUID executionId, Instant createdAt,
        boolean read) {
}
