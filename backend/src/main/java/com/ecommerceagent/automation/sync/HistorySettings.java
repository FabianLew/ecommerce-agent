package com.ecommerceagent.automation.sync;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Configuration;

/**
 * Order history import schedule (ADR-0015).
 *
 * @param enabled         whether the schedule reconciler creates history schedules
 * @param interval        how often a history run starts; also the backoff after a failed or incomplete chunk
 * @param maxChunksPerRun chunks read by one run
 * @param pause           pause between chunks of one run, to stay gentle with the shop
 */
@ConfigurationProperties("operator.automation.history")
record HistorySettings(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("10m") Duration interval,
        @DefaultValue("6") int maxChunksPerRun,
        @DefaultValue("2s") Duration pause) {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(HistorySettings.class)
    static class Enable {
    }
}
