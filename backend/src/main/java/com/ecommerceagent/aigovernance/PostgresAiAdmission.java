package com.ecommerceagent.aigovernance;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

import com.ecommerceagent.identity.TrustedStoreScope;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Admission on PostgreSQL: one reviewed function call each, on the bound store's connection (V33, V38). The platform
 * limit passed to the database is the share of the automation's priority ({@link AiAdmissionSettings}).
 */
@Service
class PostgresAiAdmission implements AiAdmission {

    private final JdbcClient jdbc;
    private final AiGovernanceSettings settings;
    private final AiAdmissionSettings priorities;
    private final MeterRegistry meters;

    PostgresAiAdmission(JdbcClient jdbc, AiGovernanceSettings settings, AiAdmissionSettings priorities,
            MeterRegistry meters) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.priorities = priorities;
        this.meters = meters;
    }

    @Override
    public Admission admit(TrustedStoreScope scope, UUID agentRunId, String automationType, int maxStoreRuns,
            Duration ttl) {
        PostgresAiBudgetGovernor.requireBound(scope);
        Objects.requireNonNull(agentRunId, "agentRunId");
        Objects.requireNonNull(ttl, "ttl");
        // Reserved slots (ADR-0027 section 6.2): a lower priority may fill only its share of the platform's runs.
        AiAdmissionSettings.Priority priority = priorities.priorityOf(automationType);
        int globalLimit = priorities.capacity(priority, settings.maxConcurrentRuns());
        String answer = jdbc.sql("SELECT ai_acquire_run_lease(?::uuid, ?, ?, ?, make_interval(secs => ?))")
                .params(agentRunId, automationType, maxStoreRuns, globalLimit, ttl.toMillis() / 1000.0)
                .query(String.class).single();
        Admission admission = Admission.valueOf(answer);
        meters.counter("operator.ai.admissions", "automation", automationType, "priority", priority.name(),
                        "outcome", admission.name())
                .increment();
        return admission;
    }

    @Override
    public void release(TrustedStoreScope scope, UUID agentRunId) {
        PostgresAiBudgetGovernor.requireBound(scope);
        jdbc.sql("SELECT ai_release_run_lease(?::uuid)").param(agentRunId).query(Boolean.class).single();
    }

    @Override
    public boolean stopped() {
        return Boolean.TRUE.equals(jdbc.sql("SELECT stopped FROM ai_platform_stop_state()").query(Boolean.class)
                .single());
    }
}
