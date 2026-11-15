package com.ecommerceagent.automation.notifications;

import org.springframework.modulith.NamedInterface;

/**
 * Sends one e-mail (plan decision I3, ADR-0021 point 2). Implementations never log message bodies or recipients'
 * addresses. Every attempt for a delivery carries the same {@link MailMessage#deliveryId() idempotency key} and the
 * same {@link MailMessage#messageId() Message-ID}. The answer says what is known about acceptance: an exception is
 * treated as {@link Uncertain}, never as a refusal, because the message may have been taken.
 */
@NamedInterface("notifications")
public interface MailProvider {

    record MailMessage(String deliveryId, String messageId, String to, String subject, String body) {
    }

    /** What the provider can do; recorded per provider in O-11. Without lookup or idempotency nothing is resent. */
    record Capabilities(boolean idempotencyKeys, boolean statusLookup, boolean events) {

        public static final Capabilities NONE = new Capabilities(false, false, false);
    }

    sealed interface SendResult permits Accepted, Refused, Uncertain {
    }

    /** The provider took the message. */
    record Accepted(String providerMessageId) implements SendResult {
    }

    /** The provider definitely did not take the message (validation, authentication, a 4xx meaning "not taken"). */
    record Refused(String code, boolean retryable) implements SendResult {
    }

    /** The answer was lost or ambiguous (timeout, reset after the request was written, 5xx). */
    record Uncertain(String code) implements SendResult {
    }

    enum LookupStatus {
        FOUND,
        NOT_FOUND,
        UNKNOWN
    }

    record Lookup(LookupStatus status, String providerMessageId) {

        public static final Lookup UNKNOWN = new Lookup(LookupStatus.UNKNOWN, null);
    }

    SendResult send(MailMessage message);

    default Capabilities capabilities() {
        return Capabilities.NONE;
    }

    /** Asks the provider whether it has the message, by idempotency key or Message-ID; only with status lookup. */
    default Lookup lookup(MailMessage message) {
        return Lookup.UNKNOWN;
    }
}
