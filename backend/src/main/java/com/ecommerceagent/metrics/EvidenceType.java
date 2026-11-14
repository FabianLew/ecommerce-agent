package com.ecommerceagent.metrics;

/** What a piece of evidence describes; the content shape is fixed per type and version. */
public enum EvidenceType {
    STOCK_READING(EvidenceKind.OBSERVATION),
    UNITS_SOLD(EvidenceKind.DERIVED_METRIC),
    SALES_VELOCITY(EvidenceKind.DERIVED_METRIC),
    DAYS_OF_COVER(EvidenceKind.DERIVED_METRIC),
    /** Known replenishment of a variant, including the explicit fact that none is known. */
    REPLENISHMENT(EvidenceKind.OBSERVATION),
    AD_STATE(EvidenceKind.OBSERVATION),
    AD_MAPPING(EvidenceKind.OBSERVATION);

    private final EvidenceKind kind;

    EvidenceType(EvidenceKind kind) {
        this.kind = kind;
    }

    public EvidenceKind kind() {
        return kind;
    }
}
