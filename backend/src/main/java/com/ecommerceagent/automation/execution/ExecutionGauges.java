package com.ecommerceagent.automation.execution;

import com.ecommerceagent.identity.DatabaseGauges;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Executions that need a person, stops in force and overdue checkpoints (ADR-0024); worker only. */
@Component
@Profile("worker")
class ExecutionGauges {

    ExecutionGauges(MeterRegistry registry, JdbcClient jdbc) {
        new DatabaseGauges(registry, jdbc)
                .gauge("operator.executions.unknown", "Executions whose effect is not confirmed (UNKNOWN)",
                        null)
                .gauge("operator.executions.stuck", "Executions IN_PROGRESS or APPLIED for more than 15 minutes",
                        null)
                .gauge("operator.mutation.stops.active", "Stores whose mutations are stopped", null)
                .gauge("operator.observations.overdue", "Outcome checkpoints more than an hour past due",
                        null);
    }
}
