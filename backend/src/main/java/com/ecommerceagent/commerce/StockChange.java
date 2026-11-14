package com.ecommerceagent.commerce;

/** Result of offering a pushed stock reading against what is already known (IG-04). */
public enum StockChange {
    /** Newer than the latest reading: recorded. */
    RECORDED,
    /** Older than the latest reading: ignored, the stock never moves back in source time. */
    STALE,
    /** Same source time and same stock as the latest reading: nothing new. */
    DUPLICATE,
    /** Same source time but a different stock: not recorded; an authoritative read must decide. */
    AMBIGUOUS
}
