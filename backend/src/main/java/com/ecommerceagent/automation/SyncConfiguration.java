package com.ecommerceagent.automation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Import start settings and the periodic reconciliation pass in the worker (FIX-02). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SyncProperties.class)
class SyncConfiguration {

    /** Runs in every worker replica; passes are idempotent, so they need no leader election. */
    @Component
    @Profile("worker")
    @EnableScheduling
    @ConditionalOnProperty(name = "operator.sync.reconciler-enabled", havingValue = "true", matchIfMissing = true)
    static class ReconcileSchedule {

        private final SyncRunReconciler reconciler;

        ReconcileSchedule(SyncRunReconciler reconciler) {
            this.reconciler = reconciler;
        }

        @Scheduled(initialDelayString = "${operator.sync.reconcile-interval:1m}",
                fixedDelayString = "${operator.sync.reconcile-interval:1m}")
        void reconcile() {
            reconciler.reconcile();
        }
    }
}
