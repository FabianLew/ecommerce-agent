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
 * Runs every {@link RetentionTask} store by store under the retention system scope (ADR-0022 points 4 and 6), in
 * batches, and records one {@code RETENTION_APPLIED} audit entry per task and store that changed rows. Audit entries
 * without a store are handled once more unbound. A store on legal hold is skipped. {@link RetentionSchedule} calls it in the worker profile when
 * {@code operator.retention.enabled=true}.
 */
@Component
@EnableConfigurationProperties(RetentionSettings.class)
public class RetentionRunner {

    static final String SERVICE = "retention";
    private static final Logger log = LoggerFactory.getLogger(RetentionRunner.class);

    private final List<RetentionTask> tasks;
    private final RetentionSettings settings;
    private final SystemStoreRunner stores;
    private final AuditRetention auditRetention;
    private final RetentionHolds holds;
    private final AuditTrail audit;
    private final Clock clock;

    RetentionRunner(List<RetentionTask> tasks, RetentionSettings settings, SystemStoreRunner stores,
            AuditRetention auditRetention, RetentionHolds holds, AuditTrail audit, Clock clock) {
        this.holds = holds;
        this.tasks = tasks;
        this.settings = settings;
        this.stores = stores;
        this.auditRetention = auditRetention;
        this.audit = audit;
        this.clock = clock;
    }

    /** Applies every task once to every store; returns the changed rows per task over all stores. */
    public Map<String, Integer> runAll() {
        Map<String, Integer> changed = new LinkedHashMap<>();
        Instant now = clock.instant();
        stores.forEachStore(SERVICE, scope -> {
            // A legal hold stops every task for the store (ADR-0022 point 7).
            if (holds.hold(scope).isPresent()) {
                audit.record(AuditEntry.inScope(scope, "RETENTION_SKIPPED_HOLD", "store_retention_hold",
                        scope.storeId().toString(), Map.of()));
                return;
            }
            for (RetentionTask task : tasks) {
                Duration period = settings.periods().get(task.name());
                if (period == null) {
                    log.warn("Retention task {} has no period in operator.retention.periods and is skipped",
                            task.name());
                    continue;
                }
                Instant cutoff = now.minus(period);
                try {
                    int rows = run(batch -> task.apply(scope, cutoff, batch));
                    changed.merge(task.name(), rows, Integer::sum);
                    if (rows > 0) {
                        audit.record(AuditEntry.inScope(scope, "RETENTION_APPLIED", "retention_task", task.name(),
                                Map.of("rows", Integer.toString(rows), "cutoff", cutoff.toString())));
                    }
                } catch (RuntimeException e) {
                    log.error("Retention task {} failed for store {}", task.name(), scope.storeId(), e);
                }
            }
        });
        Duration auditPeriod = settings.periods().get(auditRetention.name());
        if (auditPeriod != null) {
            try {
                Instant cutoff = now.minus(auditPeriod);
                int rows = run(batch -> auditRetention.applyWithoutStore(cutoff, batch));
                changed.merge(auditRetention.name(), rows, Integer::sum);
                if (rows > 0) {
                    audit.record(new AuditEntry("RETENTION_APPLIED", null, null, null, SERVICE, "retention_task",
                            auditRetention.name() + ":without-store",
                            Map.of("rows", Integer.toString(rows), "cutoff", cutoff.toString())));
                }
            } catch (RuntimeException e) {
                log.error("Retention of audit entries without a store failed", e);
            }
        }
        return changed;
    }

    private int run(java.util.function.IntUnaryOperator batch) {
        int total = 0;
        for (int i = 0; i < settings.maxBatches(); i++) {
            int rows = batch.applyAsInt(settings.batchSize());
            total += rows;
            if (rows < settings.batchSize()) {
                break;
            }
        }
        return total;
    }
}
