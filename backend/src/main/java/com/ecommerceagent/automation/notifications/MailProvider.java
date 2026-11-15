package com.ecommerceagent.automation.notifications;

import org.springframework.modulith.NamedInterface;

/**
 * Sends one e-mail (plan decision I3). The provider is chosen in O-11; implementations never log message bodies or
 * recipients' addresses. A thrown exception means the message was not accepted; it will be retried.
 */
@NamedInterface("notifications")
public interface MailProvider {

    record MailMessage(String deliveryId, String to, String subject, String body) {
    }

    void send(MailMessage message);
}
