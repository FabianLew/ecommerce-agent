package com.ecommerceagent.integration.meta;

/** The ad account read by the connection check. {@code accountStatus} 1 is active. */
public record MetaAccount(String id, String name, int accountStatus, String currency) {
}
