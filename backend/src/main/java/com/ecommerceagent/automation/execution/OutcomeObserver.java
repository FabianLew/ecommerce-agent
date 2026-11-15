package com.ecommerceagent.automation.execution;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Observes due checkpoints of verified outcomes (ADR-0021 point 5); reads only. */
@Component
@Profile("worker")
class OutcomeObserver {

    private final ActionOutcomes outcomes;

    OutcomeObserver(ActionOutcomes outcomes) {
        this.outcomes = outcomes;
    }

    @Scheduled(initialDelayString = "60s", fixedDelayString = "${operator.outcomes.observe-interval:10m}")
    void observe() {
        outcomes.observeDue(100);
    }
}
