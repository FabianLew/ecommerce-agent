package com.ecommerceagent.commerce;

import java.math.BigDecimal;
import java.util.Objects;

/** One order line. {@code variantExternalId} links it to a catalog variant of the same source. */
public record OrderLineData(String externalLineId, String variantExternalId, String productExternalId, String sku,
        int quantity, BigDecimal lineTotalAmount, BigDecimal lineTaxAmount) {

    public OrderLineData {
        Objects.requireNonNull(externalLineId, "externalLineId");
        Objects.requireNonNull(lineTotalAmount, "lineTotalAmount");
        Objects.requireNonNull(lineTaxAmount, "lineTaxAmount");
    }
}
