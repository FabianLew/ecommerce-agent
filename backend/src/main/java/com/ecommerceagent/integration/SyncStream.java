package com.ecommerceagent.integration;

/** An incrementally read source stream with its own watermark (ADR-0010). */
enum SyncStream {
    PRODUCTS,
    ORDERS,
    /** Orders before the initial import's window; moves back only (ADR-0015). */
    HISTORY
}
