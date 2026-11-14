package com.ecommerceagent.integration;

/** Non-blocking findings of a successful check. */
public enum ConnectionWarning {
    /** WooCommerce uses a different currency than the store settings; metrics would mix currencies. */
    CURRENCY_MISMATCH,
    /** The key cannot read the currency setting, so the currency was not compared. */
    CURRENCY_UNKNOWN
}
