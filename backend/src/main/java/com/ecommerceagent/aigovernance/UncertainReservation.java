package com.ecommerceagent.aigovernance;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A call whose cost is not confirmed: {@code UNKNOWN}, or reconciled conservatively and not yet corrected
 * ({@code RECONCILED_CONSERVATIVE}, {@code recordedCost} is the full reserved amount). Identifiers and amounts only.
 */
public record UncertainReservation(UUID reservationId, UUID organizationId, UUID storeId, String automationType,
        String provider, String model, String providerRequestId, String state, String stateReason,
        BigDecimal reservedAmount, BigDecimal recordedCost, String currency, Instant sentAt, Instant unknownAt,
        Instant settledAt) {

    public boolean unknown() {
        return "UNKNOWN".equals(state);
    }
}
