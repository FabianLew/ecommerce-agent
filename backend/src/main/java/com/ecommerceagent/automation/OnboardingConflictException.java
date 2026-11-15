package com.ecommerceagent.automation;

/** An onboarding update with a stale version ({@code conflict}) or a missing field. */
public class OnboardingConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final boolean conflict;

    public OnboardingConflictException(String message, boolean conflict) {
        super(message, null, false, false);
        this.conflict = conflict;
    }

    public boolean conflict() {
        return conflict;
    }
}
