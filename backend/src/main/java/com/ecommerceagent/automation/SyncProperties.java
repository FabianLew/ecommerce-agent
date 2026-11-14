package com.ecommerceagent.automation;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Import workflow start and recovery (FIX-02).
 *
 * @param executionTimeout limit for one import workflow; the reconciler closes runs whose workflow hit it
 * @param startGrace       age of an unconfirmed run before the reconciler starts it; the request that created
 *                         the run normally starts it within milliseconds
 * @param startDeadline    age after which an unconfirmed run is no longer started but closed as FAILED
 * @param reconcileInterval delay between reconciliation passes in the worker
 * @param batchSize        running runs examined per pass
 */
@ConfigurationProperties("operator.sync")
record SyncProperties(
        @DefaultValue("2h") Duration executionTimeout,
        @DefaultValue("30s") Duration startGrace,
        @DefaultValue("15m") Duration startDeadline,
        @DefaultValue("1m") Duration reconcileInterval,
        @DefaultValue("100") int batchSize) {
}
