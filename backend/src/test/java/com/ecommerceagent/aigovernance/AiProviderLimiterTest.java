package com.ecommerceagent.aigovernance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.ecommerceagent.FixtureDb;
import com.ecommerceagent.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** ADR-0027 section 6.2: provider rate limits on PostgreSQL, across concurrent callers (E-54.3). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
class AiProviderLimiterTest {

    private static final Duration TTL = Duration.ofMinutes(2);

    @Autowired
    AiProviderLimiter limiter;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    FixtureDb fixtures;

    private String slot(String model, int rpm, int inputTpm, int outputTpm, int parallel, int in, int out) {
        return jdbc.sql("""
                        SELECT ai_acquire_provider_slot(?::uuid, 'anthropic', ?, ?, ?, ?, ?, ?, ?,
                                                        interval '2 minutes')
                        """)
                .params(UUID.randomUUID(), model, in, out, rpm, inputTpm, outputTpm, parallel)
                .query(String.class).single();
    }

    @Test
    void requestsAndTokensPerMinuteAreBounded() {
        String model = "m-" + UUID.randomUUID();
        assertThat(slot(model, 2, 1000, 1000, 10, 100, 100)).isEqualTo("GRANTED");
        assertThat(slot(model, 2, 1000, 1000, 10, 100, 100)).isEqualTo("GRANTED");
        assertThat(slot(model, 2, 1000, 1000, 10, 100, 100)).isEqualTo("REQUEST_LIMIT");

        String tokens = "t-" + UUID.randomUUID();
        assertThat(slot(tokens, 100, 1000, 1000, 10, 900, 10)).isEqualTo("GRANTED");
        assertThat(slot(tokens, 100, 1000, 1000, 10, 200, 10)).isEqualTo("INPUT_TOKEN_LIMIT");
        assertThat(slot(tokens, 100, 1000, 1000, 10, 50, 995)).isEqualTo("OUTPUT_TOKEN_LIMIT");
        assertThat(slot(tokens, 100, 1000, 1000, 10, 50, 50)).as("smaller calls still fit").isEqualTo("GRANTED");
    }

    @Test
    void parallelCallsAreBoundedAndAReleasedSlotFreesIt() {
        String model = "p-" + UUID.randomUUID();
        UUID first = UUID.randomUUID();
        assertThat(limiter.acquire(first, "anthropic", model, 10, 10, TTL)).isEqualTo(AiProviderLimiter.Slot.GRANTED);
        assertThat(limiter.acquire(first, "anthropic", model, 10, 10, TTL)).as("a repeated call keeps its slot")
                .isEqualTo(AiProviderLimiter.Slot.GRANTED);
        // The default limits allow 8 in flight.
        for (int i = 0; i < 7; i++) {
            assertThat(limiter.acquire(UUID.randomUUID(), "anthropic", model, 10, 10, TTL))
                    .isEqualTo(AiProviderLimiter.Slot.GRANTED);
        }
        assertThat(limiter.acquire(UUID.randomUUID(), "anthropic", model, 10, 10, TTL))
                .isEqualTo(AiProviderLimiter.Slot.PARALLEL_LIMIT);
        limiter.release(first);
        assertThat(limiter.acquire(UUID.randomUUID(), "anthropic", model, 10, 10, TTL))
                .isEqualTo(AiProviderLimiter.Slot.GRANTED);
    }

    @Test
    void concurrentCallersNeverExceedTheLimit() throws Exception {
        String model = "c-" + UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            List<Callable<String>> calls = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                calls.add(() -> slot(model, 5, 1_000_000, 1_000_000, 100, 10, 10));
            }
            List<String> answers = new ArrayList<>();
            for (Future<String> answer : pool.invokeAll(calls)) {
                answers.add(answer.get());
            }
            assertThat(answers).filteredOn("GRANTED"::equals).hasSize(5);
            assertThat(answers).filteredOn("REQUEST_LIMIT"::equals).hasSize(15);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aRateLimitedModelIsPausedAndAPauseIsNeverShortened() {
        String model = "r-" + UUID.randomUUID();
        limiter.pause("anthropic", model, Duration.ofSeconds(120), "RATE_LIMITED");
        assertThat(limiter.acquire(UUID.randomUUID(), "anthropic", model, 10, 10, TTL))
                .isEqualTo(AiProviderLimiter.Slot.PROVIDER_PAUSED);
        assertThat(limiter.acquire(UUID.randomUUID(), "anthropic", "other-" + model, 10, 10, TTL))
                .as("another model is not paused").isEqualTo(AiProviderLimiter.Slot.GRANTED);

        limiter.pause("anthropic", model, Duration.ofSeconds(1), "RATE_LIMITED");
        assertThat(fixtures.sql("""
                        SELECT paused_until > now() + interval '100 seconds' FROM ai_provider_cooldown
                        WHERE provider = 'anthropic' AND model = ?
                        """).param(model).query(Boolean.class).single()).isTrue();

        // No retry-after: the default pause; a retry-after over the maximum is capped at ten minutes.
        String capped = "x-" + UUID.randomUUID();
        limiter.pause("anthropic", capped, Duration.ofHours(1), "RATE_LIMITED");
        assertThat(fixtures.sql("""
                        SELECT paused_until < now() + interval '601 seconds' FROM ai_provider_cooldown
                        WHERE provider = 'anthropic' AND model = ?
                        """).param(capped).query(Boolean.class).single()).isTrue();
    }

    @Test
    void theRuntimeNeitherSeesNorChangesTheLimitTables() {
        String model = "h-" + UUID.randomUUID();
        limiter.pause("anthropic", model, Duration.ofSeconds(60), "RATE_LIMITED");
        assertThat(limiter.acquire(UUID.randomUUID(), "anthropic", "s-" + model, 10, 10, TTL))
                .isEqualTo(AiProviderLimiter.Slot.GRANTED);

        assertThat(jdbc.sql("DELETE FROM ai_provider_cooldown").update()).isZero();
        assertThat(jdbc.sql("UPDATE ai_provider_call SET finished_at = now()").update()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM ai_provider_call").query(Integer.class).single()).isZero();
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO ai_provider_cooldown (provider, model, paused_until, reason, set_at)
                        VALUES ('anthropic', 'x', now(), 'x', now())
                        """).update()).isInstanceOf(DataAccessException.class);
        assertThat(fixtures.sql("SELECT count(*) FROM ai_provider_cooldown WHERE model = ?").param(model)
                .query(Integer.class).single()).isEqualTo(1);
    }
}
