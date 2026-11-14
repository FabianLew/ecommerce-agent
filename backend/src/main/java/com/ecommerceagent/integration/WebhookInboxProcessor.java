package com.ecommerceagent.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Drains the webhook inbox in the worker (ADR-0009). Several workers may run: items are claimed with
 * {@code FOR UPDATE SKIP LOCKED}. Tests disable it and call {@link WooWebhooks#processPending(int)}.
 */
@Configuration(proxyBeanMethods = false)
@Profile("worker")
@EnableScheduling
@ConditionalOnProperty(name = "operator.integration.webhooks.processor-enabled", havingValue = "true",
        matchIfMissing = true)
class WebhookInboxProcessor {

    private static final Logger log = LoggerFactory.getLogger(WebhookInboxProcessor.class);
    private static final int BATCH = 50;

    private final WooWebhooks webhooks;

    WebhookInboxProcessor(WooWebhooks webhooks) {
        this.webhooks = webhooks;
    }

    @Scheduled(fixedDelayString = "${operator.integration.webhooks.processor-interval:2s}")
    void drain() {
        try {
            int handled = webhooks.processPending(BATCH);
            if (handled > 0) {
                log.debug("Processed {} webhook inbox items", handled);
            }
        } catch (RuntimeException e) {
            log.warn("Webhook inbox processing failed", e);
        }
    }
}
