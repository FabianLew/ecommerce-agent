package com.ecommerceagent.aigovernance;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

import com.ecommerceagent.identity.TrustedStoreScope;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Admission on PostgreSQL: one reviewed function call each, on the bound store's connection (V33). */
@Service
class PostgresAiAdmission implements AiAdmission {

    private final JdbcClient jdbc;
    private final AiGovernanceSettings settings;
    private final MeterRegistry meters;

    PostgresAiAdmission(JdbcClient jdbc, AiGovernanceSettings settings, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.meters = meters;
    }

    @Override
    public Admission admit(TrustedStoreScope scope, UUID agentRunId, String automationType, int maxStoreRuns,
            Duration ttl) {
        PostgresAiBudgetGovernor.requireBound(scope);
        Objects.requireNonNull(agentRunId, "agentRunId");
        Objects.requireNonNull(ttl, "ttl");
        String answer = jdbc.sql("SELECT ai_acquire_run_lease(?::uuid, ?, ?, ?, make_interval(secs => ?))")
                .params(agentRunId, automationType, maxStoreRuns, settings.maxConcurrentRuns(), ttl.toMillis() / 1000.0)
                .query(String.class).single();
        Admission admission = Admission.valueOf(answer);
        meters.counter("operator.ai.admissions", "automation", automationType, "outcome", admission.name())
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
