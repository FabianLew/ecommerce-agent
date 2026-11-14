package com.ecommerceagent.automation.guardian;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.modulith.NamedInterface;

/** A recorded policy evaluation of one proposal revision (ADR-0012). */
@NamedInterface("guardian")
public record PolicyEvaluation(UUID id, UUID proposalId, int revision, String policyKey, String policyVersion,
        InventoryProtectionPolicy.Mode mode, PolicyOutcome outcome, List<PolicyCheck> checks, Instant evaluatedAt) {

    public PolicyEvaluation {
        checks = List.copyOf(checks);
    }

    /** The code of the check that decided the outcome, or the autonomy code when all passed. */
    public String decisiveCode() {
        return checks.stream().filter(check -> check.result() == PolicyCheck.Result.FAILED).findFirst()
                .map(PolicyCheck::code).orElse(checks.isEmpty() ? null : checks.getLast().code());
    }
}
