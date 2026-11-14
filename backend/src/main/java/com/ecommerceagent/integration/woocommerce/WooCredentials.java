package com.ecommerceagent.integration.woocommerce;

/** WooCommerce REST API key pair. {@link #toString()} never prints the values. */
public record WooCredentials(String consumerKey, String consumerSecret) {

    public String keyHint() {
        return consumerKey.length() <= 4 ? "****" : consumerKey.substring(consumerKey.length() - 4);
    }

    @Override
    public String toString() {
        return "WooCredentials[consumerKey=…" + keyHint() + ", consumerSecret=<redacted>]";
    }
}
