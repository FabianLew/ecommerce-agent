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
                .gauge("operator.webhook.inbox.pending", "Webhook deliveries waiting to be processed", null,
                        "SELECT count(*) FROM webhook_inbox WHERE status = 'RECEIVED'")
                .gauge("operator.webhook.inbox.oldest.age", "Age of the oldest unprocessed webhook delivery",
                        "seconds", """
                        SELECT extract(epoch FROM now() - min(received_at)) FROM webhook_inbox
                        WHERE status = 'RECEIVED'""")
                .gauge("operator.outbox.pending", "Outbox events not dispatched yet", null,
                        "SELECT count(*) FROM outbox_event WHERE dispatched_at IS NULL")
                .gauge("operator.outbox.oldest.age", "Age of the oldest undispatched outbox event", "seconds", """
                        SELECT extract(epoch FROM now() - min(created_at)) FROM outbox_event
                        WHERE dispatched_at IS NULL""")
                .gauge("operator.connections.failing", "WooCommerce and Meta connections in ERROR", null, """
                        SELECT (SELECT count(*) FROM integration_connection WHERE status = 'ERROR')
                             + (SELECT count(*) FROM meta_connection WHERE status = 'ERROR')""");
    }
}
