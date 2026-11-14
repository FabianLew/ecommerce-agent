package com.ecommerceagent.commerce;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Order as read from a source, without customer personal data. Amounts are decimal values in the
 * order currency; {@code placedAt} is the source creation time in UTC.
 */
public record OrderData(String externalId, String status, String currency, BigDecimal totalAmount,
        BigDecimal taxAmount, BigDecimal discountAmount, BigDecimal shippingAmount, Instant placedAt,
        Instant paidAt, Instant completedAt, Instant sourceUpdatedAt, List<OrderLineData> lines) {

    public OrderData {
        Objects.requireNonNull(externalId, "externalId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(totalAmount, "totalAmount");
        Objects.requireNonNull(taxAmount, "taxAmount");
        Objects.requireNonNull(discountAmount, "discountAmount");
        Objects.requireNonNull(shippingAmount, "shippingAmount");
        Objects.requireNonNull(placedAt, "placedAt");
        lines = List.copyOf(lines);
    }
}
