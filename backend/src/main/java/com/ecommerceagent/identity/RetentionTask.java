package com.ecommerceagent.identity;

import java.time.Instant;

/**
 * One kind of data a module removes or redacts when it has served its purpose (ADR-0022 point 5). The module that
 * owns the table declares the task; {@link RetentionRunner} decides when it runs and with which cutoff.
 */
public interface RetentionTask {

    /** The key of the task's period in {@code operator.retention.periods}, e.g. {@code webhook-inbox}. */
    String name();

    /** Handles at most {@code batchSize} rows older than {@code cutoff}; returns how many rows it changed. */
    int apply(Instant cutoff, int batchSize);
}
