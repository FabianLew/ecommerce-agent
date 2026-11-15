package com.ecommerceagent.web;

import com.ecommerceagent.ApiRateLimits;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;

/**
 * Counters and idempotent answers of the API on PostgreSQL (V36, ADR-0027 section 6.1). A count is one atomic
 * upsert per one-minute window, so limits hold across instances.
 */
@Repository
class ApiRequestProtection {

    private final JdbcClient jdbc;
    private final ApiRateLimits limits;
    private final Clock clock;

    ApiRequestProtection(JdbcClient jdbc, ApiRateLimits limits, Clock clock) {
        this.jdbc = jdbc;
        this.limits = limits;
        this.clock = clock;
    }

    /** Counts a request in the current minute of {@code bucket}; true while the count is within {@code limit}. */
    boolean hit(String bucket, int limit) {
        Instant window = clock.instant().truncatedTo(ChronoUnit.MINUTES);
        int hits = jdbc.sql("""
                        INSERT INTO api_rate_window AS w (bucket, window_start, hits) VALUES (?, ?, 1)
                        ON CONFLICT (bucket, window_start) DO UPDATE SET hits = w.hits + 1
                        RETURNING w.hits
                        """).params(bucket, Timestamp.from(window)).query(Integer.class).single();
        return hits <= limit;
    }

    /** Seconds until the current window ends. */
    long retryAfterSeconds() {
        Instant now = clock.instant();
        return Math.max(1, now.truncatedTo(ChronoUnit.MINUTES).plus(1, ChronoUnit.MINUTES).getEpochSecond()
                - now.getEpochSecond());
    }

    sealed interface Claim {
    }

    /** This request owns the key: run the command and record its answer. */
    record Claimed() implements Claim {
    }

    /** The key was used before for the same request; its answer, or null fields while it still runs. */
    record Seen(Integer status, String contentType, String body) implements Claim {
    }

    /** The key was used before for a different request. */
    record Conflict() implements Claim {
    }

    Claim claim(String owner, String key, String request) {
        int inserted = jdbc.sql("""
                        INSERT INTO api_idempotency (owner, idempotency_key, request, created_at)
                        VALUES (?, ?, ?, ?) ON CONFLICT (owner, idempotency_key) DO NOTHING
                        """).params(owner, key, request, Timestamp.from(clock.instant())).update();
        if (inserted == 1) {
            return new Claimed();
        }
        Optional<Claim> seen = jdbc.sql("""
                        SELECT request, status, content_type, body FROM api_idempotency
                        WHERE owner = ? AND idempotency_key = ?
                        """).params(owner, key)
                .query((rs, n) -> rs.getString("request").equals(request)
                        ? (Claim) new Seen((Integer) rs.getObject("status"), rs.getString("content_type"),
                                rs.getString("body"))
                        : new Conflict())
                .optional();
        // A claim that vanished between the two statements was abandoned: treat the request as new.
        return seen.orElseGet(() -> claim(owner, key, request));
    }

    void complete(String owner, String key, int status, String contentType, String body) {
        jdbc.sql("""
                        UPDATE api_idempotency SET status = ?, content_type = ?, body = ?, completed_at = ?
                        WHERE owner = ? AND idempotency_key = ? AND status IS NULL
                        """).params(status, contentType, body, Timestamp.from(clock.instant()), owner, key).update();
    }

    /** The command failed before it answered: the key may be used again. */
    void abandon(String owner, String key) {
        jdbc.sql("DELETE FROM api_idempotency WHERE owner = ? AND idempotency_key = ? AND status IS NULL")
                .params(owner, key).update();
    }

    @Scheduled(initialDelayString = "2m", fixedDelayString = "10m")
    void clean() {
        Instant now = clock.instant();
        jdbc.sql("DELETE FROM api_rate_window WHERE window_start < ?")
                .param(Timestamp.from(now.minus(1, ChronoUnit.HOURS))).update();
        jdbc.sql("DELETE FROM api_idempotency WHERE created_at < ?")
                .param(Timestamp.from(now.minus(limits.idempotencyRetention()))).update();
    }
}
