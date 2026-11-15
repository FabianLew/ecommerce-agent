package com.ecommerceagent.intelligence.model.provider;

import java.util.Map;
import java.util.Objects;

/**
 * Decides whether a failed Anthropic Messages API call is confirmed not charged (ADR-0027 section 4.5). The rule is
 * explicit and depends on the stage the request reached, the HTTP status and the provider's error type; anything it
 * does not list stays possibly charged ({@code UNKNOWN}), including a status that is a client error with an unexpected
 * error type and any error after a streaming response started.
 */
final class AnthropicChargeRules {

    /** Version of the rule set, written to the ADR; a change to the list is a new version. */
    static final String VERSION = "anthropic-charge/1";

    /** How far the request got before it failed. */
    enum Stage {
        /** The adapter refused before anything was sent (no model configured, deadline already passed). */
        NOT_SENT,
        /** The provider answered with an error response before any output (no response body was streamed). */
        ERROR_RESPONSE,
        /** A streaming response had started (message_start was received) and then failed. */
        STREAM_STARTED,
        /** No response: a timeout, a dropped connection or an I/O error after the request may have left. */
        NO_RESPONSE
    }

    /**
     * Error responses the provider returns before it processes the request, each with the only error type that
     * confirms it. A status alone is never enough.
     */
    private static final Map<Integer, String> REFUSED_BEFORE_PROCESSING = Map.of(
            400, "invalid_request_error",
            401, "authentication_error",
            403, "permission_error",
            404, "not_found_error",
            413, "request_too_large",
            429, "rate_limit_error",
            529, "overloaded_error");

    private AnthropicChargeRules() {
    }

    static boolean confirmedNotCharged(Stage stage, int status, String errorType) {
        return switch (stage) {
            case NOT_SENT -> true;
            case ERROR_RESPONSE -> errorType != null && Objects.equals(REFUSED_BEFORE_PROCESSING.get(status), errorType);
            case STREAM_STARTED, NO_RESPONSE -> false;
        };
    }
}
