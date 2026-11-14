package com.ecommerceagent.commerce;

import java.time.Instant;

/** Orders placed in [{@code completeFrom}, {@code completeUntil}) are known to be fully synchronized. */
public record OrderCoverage(CommerceSource source, Instant completeFrom, Instant completeUntil) {

    public boolean covers(Instant from, Instant toExclusive) {
        return !completeFrom.isAfter(from) && !completeUntil.isBefore(toExclusive);
    }
}
