package com.ecommerceagent.integration;

import java.time.Instant;
import java.util.List;

/** Result of a read-only connection check. {@code error} is null when the check succeeded. */
public record ConnectionCheck(
        ConnectionStatus status,
        ConnectionCheckError error,
        List<ConnectionWarning> warnings,
        String sourceCurrency,
        Instant checkedAt) {

    public ConnectionCheck {
        warnings = List.copyOf(warnings);
    }
}
