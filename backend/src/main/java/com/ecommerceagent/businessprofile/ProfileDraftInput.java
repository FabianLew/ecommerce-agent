package com.ecommerceagent.businessprofile;

import java.util.List;

import com.ecommerceagent.businessprofile.ProfileEnums.Goal;
import com.ecommerceagent.businessprofile.ProfileEnums.Priority;
import com.ecommerceagent.businessprofile.ProfileEnums.RiskPreference;
import com.ecommerceagent.businessprofile.ProfileEnums.TaxBasis;

/** The editable fields of a draft; the currency of the planned spend is always the store's. */
public record ProfileDraftInput(Goal goal, Integer planningHorizonDays, RiskPreference riskPreference,
        TaxBasis taxBasis, List<Priority> priorities, Long plannedAdSpendMinor, String plannedAdSpendPeriod,
        String note) {
}
