package com.ecommerceagent.integration;

/** An incrementally read source stream with its own watermark (ADR-0010). */
enum SyncStream {
    PRODUCTS,
    ORDERS
}
