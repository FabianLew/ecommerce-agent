package com.ecommerceagent.decisions;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** When to look again and which conditions to watch until then. */
public record MonitorPlan(Instant nextReviewAt, List<String> conditions) {

    public MonitorPlan {
        Objects.requireNonNull(nextReviewAt, "nextReviewAt");
        conditions = List.copyOf(conditions);
    }
}
