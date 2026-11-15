package com.ecommerceagent.integration;

import com.ecommerceagent.identity.DatabaseGauges;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Backlogs and broken connections of this module (ADR-0024); registered once, by the worker. */
@Component
@Profile("worker")
class IntegrationGauges {

    IntegrationGauges(MeterRegistry registry, JdbcClient jdbc) {
        new DatabaseGauges(registry, jdbc)
                .gauge("operator.webhook.inbox.pending", "Webhook deliveries waiting to be processed", null)
                .gauge("operator.webhook.inbox.oldest.age", "Age of the oldest unprocessed webhook delivery",
                        "seconds")
                .gauge("operator.outbox.pending", "Outbox events not dispatched yet", null)
                .gauge("operator.outbox.oldest.age", "Age of the oldest undispatched outbox event", "seconds")
                .gauge("operator.connections.failing", "WooCommerce and Meta connections in ERROR", null);
    }
}
