package com.ecommerceagent.identity;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs retention every {@code operator.retention.interval} in the worker profile, only when enabled (ADR-0022). */
@Component
@Profile("worker")
@EnableScheduling
@ConditionalOnProperty(name = "operator.retention.enabled", havingValue = "true")
class RetentionSchedule {

    private final RetentionRunner runner;

    RetentionSchedule(RetentionRunner runner) {
        this.runner = runner;
    }

    @Scheduled(initialDelayString = "5m", fixedDelayString = "${operator.retention.interval:24h}")
    void run() {
        runner.runAll();
    }
}
