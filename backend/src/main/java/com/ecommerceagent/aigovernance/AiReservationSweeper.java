package com.ecommerceagent.aigovernance;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Closes reservations that nobody closed (ADR-0027 section 4.5): expired {@code RESERVED} ones are released,
 * expired {@code IN_PROGRESS} ones become {@code UNKNOWN}, and {@code UNKNOWN} ones older than the configured age are
 * reconciled conservatively. It runs across stores through the reviewed function {@code ai_sweep_reservations}.
 */
@Service
public class AiReservationSweeper {

    private static final Logger log = LoggerFactory.getLogger(AiReservationSweeper.class);

    private final JdbcClient jdbc;
    private final AiGovernanceSettings settings;

    AiReservationSweeper(JdbcClient jdbc, AiGovernanceSettings settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    public SweepResult sweep() {
        Duration after = settings.unknownConservativeSettlementEnabled()
                ? settings.unknownConservativeSettlementAfter() : null;
        SweepResult result = jdbc.sql("""
                        SELECT released_count, unknown_count, reconciled_count
                        FROM ai_sweep_reservations(?::interval, ?)
                        """)
                .params(after == null ? null : after.toSeconds() + " seconds", settings.sweepBatchSize())
                .query((rs, n) -> new SweepResult(rs.getInt("released_count"), rs.getInt("unknown_count"),
                        rs.getInt("reconciled_count")))
                .single();
        if (result.unknown() > 0 || result.reconciled() > 0) {
            log.warn("AI reservations swept: {} released, {} now UNKNOWN, {} reconciled conservatively",
                    result.released(), result.unknown(), result.reconciled());
        } else if (result.released() > 0) {
            log.info("AI reservations swept: {} released unsent", result.released());
        }
        return result;
    }

    /** Counts of one sweep. */
    public record SweepResult(int released, int unknown, int reconciled) {
    }
}
