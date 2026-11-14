package com.ecommerceagent.integration;

/** How an accepted webhook delivery was handled; every receipt is acknowledged with 200. */
public enum WebhookReceipt {
    /** Stored in the inbox for processing. */
    ACCEPTED,
    /** The same body was already stored; nothing new happens. */
    DUPLICATE,
    /** WooCommerce's unsigned test delivery sent when a webhook is saved. */
    PING,
    /** Signed, but a topic or body we do not process; acknowledged so Woo keeps the webhook active. */
    IGNORED
}
