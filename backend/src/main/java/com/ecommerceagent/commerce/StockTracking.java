package com.ecommerceagent.commerce;

/** Where the stock of a variant is counted. Shared parent stock needs separate handling (document 03 §11). */
public enum StockTracking {
    VARIANT,
    PARENT,
    NONE
}
