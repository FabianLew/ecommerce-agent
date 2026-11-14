package com.ecommerceagent.metrics;

/** Why a metric has no value, or what limits it. Shown to users and given to the agent as evidence. */
public enum MetricReason {
    /** Orders of the window were not fully imported; a value would understate sales (ADR-0008). */
    SALES_HISTORY_INCOMPLETE,
    /** No qualifying sales in the window; days of cover would be infinite (IG-03). */
    NO_SALES_IN_WINDOW,
    /** The variant does not track stock quantities. */
    STOCK_NOT_TRACKED,
    /** Stock is shared by the variants of a product; per-variant cover would be wrong (03 §2). */
    SHARED_STOCK_NOT_SUPPORTED,
    /** No stock reading at or before the moment of calculation. */
    NO_STOCK_OBSERVATION,
    /** The source reports reservations separately but did not report them. */
    AVAILABLE_STOCK_UNKNOWN,
    /** The variant was out of stock during the window, so sales understate demand (IG-25). */
    STOCKOUT_IN_WINDOW,
    /** No stock reading inside the window: it is unknown whether the variant was sellable all the time. */
    STOCK_HISTORY_UNVERIFIED,
    /** Backorders are allowed, so stock can go below zero and cover is only indicative. */
    BACKORDERS_ALLOWED
}
