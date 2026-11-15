package com.ecommerceagent.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Runs every {@link RetentionTask} in batches and records one {@code RETENTION_APPLIED} audit entry per task that
 * changed rows (ADR-0022 point 5). {@link RetentionSchedule} calls it in the worker profile when
 * {@code operator.retention.enabled=true}. It runs without a store binding: retention walks all stores.
 */
@Component
@EnableConfigurationProperties(RetentionSettings.class)
public class RetentionRunner {

    static final String SERVICE = "retention";
    private static final Logger log = LoggerFactory.getLogger(RetentionRunner.class);

    private final List<RetentionTask> tasks;
    private final RetentionSettings settings;
    private final AuditTrail audit;
    private final Clock clock;

    RetentionRunner(List<RetentionTask> tasks, RetentionSettings settings, AuditTrail audit, Clock clock) {
        this.tasks = tasks;
        this.settings = settings;
        this.audit = audit;
        this.clock = clock;
    }

    /** Applies every task once; returns the changed rows per task. */
    public Map<String, Integer> runAll() {
        Map<String, Integer> changed = new LinkedHashMap<>();
        for (RetentionTask task : tasks) {
            Duration period = settings.periods().get(task.name());
            if (period == null) {
                log.warn("Retention task {} has no period in operator.retention.periods and is skipped", task.name());
                continue;
            }
            Instant cutoff = clock.instant().minus(period);
            try {
                changed.put(task.name(), run(task, cutoff));
            } catch (RuntimeException e) {
                log.error("Retention task {} failed", task.name(), e);
            }
        }
        return changed;
    }

    private int run(RetentionTask task, Instant cutoff) {
        int total = 0;
        for (int batch = 0; batch < settings.maxBatches(); batch++) {
            int rows = task.apply(cutoff, settings.batchSize());
            total += rows;
            if (rows < settings.batchSize()) {
                break;
            }
        }
        if (total > 0) {
            audit.record(new AuditEntry("RETENTION_APPLIED", null, null, null, SERVICE, "retention_task",
                    task.name(), Map.of("rows", Integer.toString(total), "cutoff", cutoff.toString())));
        }
        return total;
    }
}
