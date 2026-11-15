package com.ecommerceagent.businessprofile;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.ecommerceagent.businessprofile.ProfileEnums.Goal;
import com.ecommerceagent.businessprofile.ProfileEnums.MissingField;
import com.ecommerceagent.businessprofile.ProfileEnums.Priority;
import com.ecommerceagent.businessprofile.ProfileEnums.RiskPreference;
import com.ecommerceagent.businessprofile.ProfileEnums.Status;
import com.ecommerceagent.businessprofile.ProfileEnums.TaxBasis;

/** One version of a store's business profile (ADR-0018). An approved version never changes. */
public record BusinessProfile(UUID id, int version, Status status, Goal goal, Integer planningHorizonDays,
        RiskPreference riskPreference, TaxBasis taxBasis, List<Priority> priorities, PlannedAdSpend plannedAdSpend,
        String note, int revision, UUID approvedBy, Instant approvedAt, Instant effectiveFrom, Instant updatedAt) {

    public BusinessProfile {
        priorities = List.copyOf(priorities);
    }

    /** A declared spend plan in minor units of the store currency, for one month. */
    public record PlannedAdSpend(long amountMinor, String currency, String period) {
    }

    public String reference() {
        return "store-profile:" + id + "@" + version;
    }

    public List<MissingField> missingFields() {
        List<MissingField> missing = new ArrayList<>();
        if (goal == null) {
            missing.add(MissingField.GOAL);
        }
        if (planningHorizonDays == null) {
            missing.add(MissingField.PLANNING_HORIZON);
        }
        if (riskPreference == null) {
            missing.add(MissingField.RISK_PREFERENCE);
        }
        if (plannedAdSpend == null) {
            missing.add(MissingField.PLANNED_AD_SPEND);
        }
        missing.add(MissingField.ECONOMIC_MODEL);
        missing.add(MissingField.MAX_ACCEPTABLE_ACQUISITION_LOSS);
        return List.copyOf(missing);
    }

    public boolean approvable() {
        return goal != null && planningHorizonDays != null && riskPreference != null;
    }
}
