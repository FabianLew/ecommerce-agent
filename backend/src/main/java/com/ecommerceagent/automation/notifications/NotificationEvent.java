package com.ecommerceagent.automation.notifications;

import java.util.Map;
import java.util.UUID;

import org.springframework.modulith.NamedInterface;

/** What happened, with the key that makes a repeated report the same notification. */
@NamedInterface("notifications")
public record NotificationEvent(String dedupKey, NotificationKind kind, String code, Map<String, String> parameters,
        UUID opportunityId, UUID proposalId, UUID executionId) {

    public NotificationEvent {
        parameters = Map.copyOf(parameters);
    }
}
