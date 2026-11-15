package com.ecommerceagent.aigovernance;

import com.ecommerceagent.identity.DatabaseGauges;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Worker only: runs the reservation sweep and publishes the AI reservation gauges (ADR-0024, ADR-0027). */
@Component
@Profile("worker")
@EnableScheduling
class AiGovernanceWorker {

    private final AiReservationSweeper sweeper;

    AiGovernanceWorker(AiReservationSweeper sweeper, MeterRegistry registry, JdbcClient jdbc, AiAdmission admission) {
        this.sweeper = sweeper;
        io.micrometer.core.instrument.Gauge.builder("operator.ai.stopped", admission, a -> a.stopped() ? 1 : 0)
                .description("1 while the platform AI stop is on (ADR-0027 section 12)").register(registry);
        new DatabaseGauges(registry, jdbc)
                .gauge("operator.ai.reservations.unknown", "AI calls whose cost is not confirmed (UNKNOWN)", null)
                .gauge("operator.ai.reservations.unknown.oldest.age", "Age of the oldest UNKNOWN AI reservation",
                        "seconds")
                .gauge("operator.ai.reservations.overdue",
                        "AI reservations open more than 10 minutes past expiry (the sweep is not running)", null)
                .gauge("operator.ai.overruns.recent", "AI calls in the last 24 hours that cost more than reserved",
                        null);
    }

    @Scheduled(initialDelayString = "30s", fixedDelayString = "${operator.ai-governance.sweep-interval:1m}")
    void sweep() {
        sweeper.sweep();
    }
}
