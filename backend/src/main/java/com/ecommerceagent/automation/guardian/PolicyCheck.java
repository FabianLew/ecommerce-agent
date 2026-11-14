package com.ecommerceagent.automation.guardian;

import java.util.Map;

/**
 * One check of a policy evaluation, with the facts it looked at. {@code outcome} is the result the check forces
 * when it fails; a passed check has none.
 */
public record PolicyCheck(String name, Result result, PolicyOutcome outcome, String code, Map<String, String> facts) {

    public PolicyCheck {
        facts = Map.copyOf(facts);
    }

    public enum Result {
        PASSED,
        FAILED,
        NOT_EVALUATED
    }
}
