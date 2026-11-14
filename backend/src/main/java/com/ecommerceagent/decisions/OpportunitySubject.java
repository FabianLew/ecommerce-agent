package com.ecommerceagent.decisions;

import java.util.Objects;
import java.util.UUID;

public record OpportunitySubject(UUID opportunityId) implements DecisionSubject {

    public OpportunitySubject {
        Objects.requireNonNull(opportunityId, "opportunityId");
    }
}
