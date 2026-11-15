package com.ecommerceagent.automation.notifications;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code operator.notifications} (ADR-0021). */
@ConfigurationProperties("operator.notifications")
public record NotificationSettings(
        @DefaultValue("true") boolean sweepEnabled,
        @DefaultValue("5m") Duration retryInterval,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("1m") Duration claimDuration,
        @DefaultValue("http://localhost:3000") String appBaseUrl,
        /** The right-hand side of every delivery's stable {@code Message-ID} (ADR-0021 point 2). */
        @DefaultValue("notifications.localhost") String mailDomain) {
}
