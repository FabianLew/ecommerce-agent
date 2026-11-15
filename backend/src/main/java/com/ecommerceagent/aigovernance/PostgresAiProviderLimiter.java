package com.ecommerceagent.aigovernance;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Provider rate limiting on PostgreSQL: one reviewed function call each (V35). */
@Service
class PostgresAiProviderLimiter implements AiProviderLimiter {

    private final JdbcClient jdbc;
    private final ProviderRateLimits limits;
    private final MeterRegistry meters;

    PostgresAiProviderLimiter(JdbcClient jdbc, ProviderRateLimits limits, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.limits = limits;
        this.meters = meters;
    }

    @Override
    public Slot acquire(UUID callId, String provider, String model, long inputTokens, long outputTokens,
            Duration ttl) {
        Objects.requireNonNull(callId, "callId");
        if (inputTokens > Integer.MAX_VALUE || outputTokens > Integer.MAX_VALUE) {
            throw new AiGovernanceException("TOKEN_LIMIT_TOO_LARGE");
        }
        ProviderRateLimits.ModelLimit limit = limits.limitOf(provider, model);
        String answer = jdbc.sql("""
                        SELECT ai_acquire_provider_slot(?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, make_interval(secs => ?))
                        """)
                .params(callId, provider, model, (int) inputTokens, (int) outputTokens, limit.requestsPerMinute(),
                        limit.inputTokensPerMinute(), limit.outputTokensPerMinute(), limit.maxParallel(),
                        ttl.toMillis() / 1000.0)
                .query(String.class).single();
        Slot slot = Slot.valueOf(answer);
        meters.counter("operator.ai.provider.slots", "provider", provider, "outcome", slot.name()).increment();
        return slot;
    }

    @Override
    public void release(UUID callId) {
        jdbc.sql("SELECT ai_release_provider_slot(?::uuid)").param(callId).query(Boolean.class).single();
    }

    @Override
    public void pause(String provider, String model, Duration retryAfter, String reason) {
        Duration pause = limits.pause(retryAfter);
        jdbc.sql("SELECT ai_pause_provider(?, ?, make_interval(secs => ?), ?)")
                .params(provider, model, pause.toMillis() / 1000.0, reason).query(java.sql.Timestamp.class).single();
        meters.counter("operator.ai.provider.throttled", "provider", provider).increment();
    }
}
