package com.ecommerceagent.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Polls connected Meta accounts in the worker (ADR-0017). Tests disable it and call {@link MetaAdSync#syncDue()}. */
@Configuration(proxyBeanMethods = false)
@Profile("worker")
@EnableScheduling
@ConditionalOnProperty(name = "operator.integration.meta.poller-enabled", havingValue = "true", matchIfMissing = true)
class MetaAdPoller {

    private static final Logger log = LoggerFactory.getLogger(MetaAdPoller.class);

    private final MetaAdSync sync;

    MetaAdPoller(MetaAdSync sync) {
        this.sync = sync;
    }

    @Scheduled(initialDelayString = "15s", fixedDelayString = "${operator.integration.meta.poll-interval:30s}")
    void poll() {
        try {
            sync.syncDue();
        } catch (RuntimeException e) {
            log.warn("Meta ad polling failed", e);
        }
    }
}
