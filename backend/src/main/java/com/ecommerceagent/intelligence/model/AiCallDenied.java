package com.ecommerceagent.intelligence.model;

/**
 * Governance did not admit the call, so no request was sent (ADR-0027). {@link #reason()} is the governor's reason,
 * e.g. {@code BUDGET_EXHAUSTED:STORE:MONTHLY}, {@code NO_PLATFORM_BUDGET} or {@code PRICE_UNVERIFIED}.
 */
public class AiCallDenied extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String reason;

    public AiCallDenied(String reason) {
        super("AI call not admitted: " + reason);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }

    /** The platform AI stop is on, or the run was not admitted while it was on. */
    public boolean stopped() {
        return reason.equals(AI_STOPPED);
    }

    public static final String AI_STOPPED = "AI_STOPPED";

    /** A budget or concurrency limit, not a price or configuration problem: the run ended on a limit. */
    public boolean budget() {
        return reason.startsWith("BUDGET_EXHAUSTED") || reason.startsWith("NO_PLATFORM_BUDGET")
                || reason.startsWith("NO_STORE_BUDGET") || reason.equals("STORE_LIMIT")
                || reason.equals("GLOBAL_LIMIT");
    }
}
