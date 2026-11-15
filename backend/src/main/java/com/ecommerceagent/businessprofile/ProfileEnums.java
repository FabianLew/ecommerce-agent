package com.ecommerceagent.businessprofile;

/** Closed vocabularies of the Store Business Profile (document 05 §B1, ADR-0018). */
public final class ProfileEnums {

    private ProfileEnums() {
    }

    public enum Status {
        DRAFT,
        APPROVED,
        SUPERSEDED
    }

    /** A direction for ranking recommendations, never a permission or a budget multiplier. */
    public enum Goal {
        PROFITABILITY,
        GROWTH,
        BALANCED
    }

    public enum RiskPreference {
        LOW,
        MEDIUM,
        HIGH
    }

    public enum TaxBasis {
        EX_TAX,
        INC_TAX
    }

    public enum Priority {
        INVENTORY_CONTINUITY,
        CONTRIBUTION_MARGIN,
        CASH_PRESERVATION,
        CUSTOMER_ACQUISITION
    }

    /** Fields an analysis may need that the profile does not have yet. */
    public enum MissingField {
        GOAL,
        PLANNING_HORIZON,
        RISK_PREFERENCE,
        PLANNED_AD_SPEND,
        /** Costs per SKU (F5); until then no analysis may assume a margin. */
        ECONOMIC_MODEL,
        MAX_ACCEPTABLE_ACQUISITION_LOSS
    }
}
