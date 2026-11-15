package com.ecommerceagent.automation.notifications;

import com.ecommerceagent.identity.DatabaseGauges;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** E-mail deliveries that will not be retried any more (ADR-0024); worker only. */
@Component
@Profile("worker")
class NotificationGauges {

    NotificationGauges(MeterRegistry registry, JdbcClient jdbc, NotificationSettings settings) {
        new DatabaseGauges(registry, jdbc)
                .gauge("operator.notification.deliveries.exhausted",
                        "E-mail deliveries FAILED after the last attempt", null,
                        "SELECT count(*) FROM notification_delivery WHERE status = 'FAILED' AND attempts >= "
                                + settings.maxAttempts());
    }
}
