package com.ecommerceagent;

import java.util.regex.Pattern;

/**
 * Log correlation keys shared by HTTP and background processing (B-07, B-08).
 * {@code requestId} identifies one HTTP request; {@code correlationId} follows a business flow across
 * requests, workflows and activities. Both are diagnostic only and never carry authorization.
 */
public final class Correlation {

    public static final String REQUEST_ID = "requestId";
    public static final String CORRELATION_ID = "correlationId";

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{8,64}");

    private Correlation() {
    }

    /** Accepts caller-provided IDs only in a safe shape, so they cannot inject into logs. */
    public static boolean isSafeId(String value) {
        return value != null && SAFE_ID.matcher(value).matches();
    }
}
