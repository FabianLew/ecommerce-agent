package com.ecommerceagent.commerce;

/**
 * How pending reservations relate to the reported stock (document 03 §2). Adapters declare it, so
 * reservations are never subtracted twice.
 */
public enum ReservationSemantics {
    /** The source already subtracted reservations: the reported quantity is sellable. */
    ALREADY_ACCOUNTED_FOR,
    /** The source reports physical stock and reservations separately. */
    SEPARATE_RESERVATIONS
}
