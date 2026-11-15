package com.ecommerceagent.aigovernance;

/**
 * Looks up one call with its provider by the request id the provider gave it (ADR-0027 section 4.5). Optional: a
 * provider that cannot report the outcome of a single request has no source, and its uncertain calls wait for a
 * platform operator or the conservative settlement. A source answers only what the provider confirms; anything else
 * is {@link Lookup.Unconfirmed}, and the reservation stays {@code UNKNOWN}. Matching is by request id only, never by
 * time, model or amount.
 */
public interface ProviderUsageSource {

    /** The provider this source answers for, as in {@code ai_price.provider}. */
    String provider();

    Lookup lookup(String model, String providerRequestId);

    /** What the provider confirmed about one request. */
    sealed interface Lookup {

        /** The provider billed the request with this usage; the cost is computed from the reservation's price. */
        record Charged(ProviderUsage usage) implements Lookup {
        }

        /** The provider confirmed that it did not bill the request. */
        record NotCharged(String evidence) implements Lookup {
        }

        /** The provider could not confirm either (not found yet, unavailable, ambiguous). */
        record Unconfirmed(String reason) implements Lookup {
        }
    }
}
