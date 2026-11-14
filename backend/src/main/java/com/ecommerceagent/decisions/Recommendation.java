package com.ecommerceagent.decisions;

import java.util.List;
import java.util.Objects;

/** The proposer's part of a proposal. {@code action} and {@code monitorPlan} are null unless the disposition needs them. */
public record Recommendation(DecisionDisposition disposition, ActionSpec action, MonitorPlan monitorPlan,
        List<EvidenceRef> evidence, List<String> missingRequirements, List<String> reasonCodes, String summary,
        List<String> uncertainties) {

    public Recommendation {
        Objects.requireNonNull(disposition, "disposition");
        Objects.requireNonNull(summary, "summary");
        evidence = List.copyOf(evidence);
        missingRequirements = List.copyOf(missingRequirements);
        reasonCodes = List.copyOf(reasonCodes);
        uncertainties = List.copyOf(uncertainties);
    }
}
