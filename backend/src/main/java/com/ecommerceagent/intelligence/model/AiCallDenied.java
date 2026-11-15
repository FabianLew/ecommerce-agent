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

    /**
     * The run must not go on whatever its limits say: the platform, store or automation AI stop is on (or was when
     * the run asked for admission), or the actor's access to the store was revoked.
     */
    public boolean stopped() {
        return reason.equals(AI_STOPPED) || reason.equals(STORE_STOPPED) || reason.equals(AUTOMATION_STOPPED)
                || reason.equals(AUTHORIZATION_REVOKED);
    }

    public static final String AI_STOPPED = "AI_STOPPED";

    /** AI is stopped for the store (independent of the platform stop). */
    public static final String STORE_STOPPED = "STORE_STOPPED";

    /** AI is stopped for the automation, on every store or on this one. */
    public static final String AUTOMATION_STOPPED = "AUTOMATION_STOPPED";

    /** The actor of the run no longer has access to the store. */
    public static final String AUTHORIZATION_REVOKED = "AUTHORIZATION_REVOKED";

    /** Prefix of a refused provider slot: {@code PROVIDER_LIMIT:REQUEST_LIMIT}, {@code PROVIDER_LIMIT:PROVIDER_PAUSED}. */
    public static final String PROVIDER_LIMIT_PREFIX = "PROVIDER_LIMIT:";

    /** A budget, concurrency or rate limit, not a price or configuration problem: the run ended on a limit. */
    public boolean budget() {
        return reason.startsWith(PROVIDER_LIMIT_PREFIX) || reason.startsWith("BUDGET_EXHAUSTED") || reason.startsWith("NO_PLATFORM_BUDGET")
                || reason.startsWith("NO_STORE_BUDGET") || reason.equals("STORE_LIMIT")
                || reason.equals("GLOBAL_LIMIT");
    }
}
