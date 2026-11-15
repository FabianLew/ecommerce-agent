package com.ecommerceagent.automation.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Retries open e-mail deliveries (ADR-0021 point 3); never touches the action a notification reports. */
@Component
@Profile("worker")
@ConditionalOnProperty(name = "operator.notifications.sweep-enabled", havingValue = "true", matchIfMissing = true)
class NotificationSweep {

    private static final Logger log = LoggerFactory.getLogger(NotificationSweep.class);

    private final OperatorNotifier notifier;

    NotificationSweep(OperatorNotifier notifier) {
        this.notifier = notifier;
    }

    @Scheduled(initialDelayString = "30s", fixedDelayString = "${operator.notifications.retry-interval:5m}")
    void sweep() {
        int failed = notifier.retryOpen(100);
        if (failed > 0) {
            log.warn("{} notification deliveries are still failing", failed);
        }
    }
}
