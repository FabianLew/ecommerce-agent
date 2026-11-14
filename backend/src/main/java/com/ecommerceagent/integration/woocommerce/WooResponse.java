package com.ecommerceagent.integration.woocommerce;

import java.util.OptionalLong;

/** An HTTP response from the WooCommerce REST API with the pagination headers we use. */
public record WooResponse(int status, String contentType, String body, OptionalLong total, OptionalLong totalPages) {

    public boolean isJson() {
        return contentType != null && contentType.toLowerCase().startsWith("application/json");
    }

    public boolean isSuccess() {
        return status >= 200 && status < 300;
    }
}
