package com.ecommerceagent.automation.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Drains the outbox in the worker (ADR-0010). Tests disable it and call {@link EventDispatch#dispatchPending}. */
@Configuration(proxyBeanMethods = false)
@Profile("worker")
@EnableScheduling
@ConditionalOnProperty(name = "operator.automation.dispatcher-enabled", havingValue = "true", matchIfMissing = true)
class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    private static final int BATCH = 100;

    private final EventDispatch dispatch;

    OutboxDispatcher(EventDispatch dispatch) {
        this.dispatch = dispatch;
    }

    @Scheduled(fixedDelayString = "${operator.automation.dispatcher-interval:1s}")
    void drain() {
        try {
            dispatch.dispatchPending(BATCH);
        } catch (RuntimeException e) {
            log.warn("Outbox dispatch failed", e);
        }
    }
}
