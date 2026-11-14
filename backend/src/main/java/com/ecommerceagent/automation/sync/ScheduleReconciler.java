package com.ecommerceagent.automation.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Creates missing sync schedules in the worker: at start and every minute, so a store gets polling within a
 * minute of its first successful import (ADR-0010). The Temporal test server has no schedules: tests disable it.
 */
@Configuration(proxyBeanMethods = false)
@Profile("worker")
@EnableScheduling
@ConditionalOnProperty(name = "operator.automation.sync.schedules-enabled", havingValue = "true", matchIfMissing = true)
class ScheduleReconciler {

    private static final Logger log = LoggerFactory.getLogger(ScheduleReconciler.class);

    private final SyncSchedules schedules;

    ScheduleReconciler(SyncSchedules schedules) {
        this.schedules = schedules;
    }

    @Scheduled(initialDelayString = "5s", fixedDelayString = "${operator.automation.sync.schedule-check-interval:60s}")
    void ensure() {
        try {
            schedules.ensureAll();
        } catch (RuntimeException e) {
            log.warn("Ensuring sync schedules failed", e);
        }
    }
}
