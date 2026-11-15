package com.ecommerceagent.automation.notifications;

import org.springframework.modulith.NamedInterface;

/** Some deliveries of a stored notification failed; they stay FAILED and are retried, the notification stays. */
@NamedInterface("notifications")
public class NotificationDeliveryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public NotificationDeliveryException(int failed) {
        super(failed + " deliveries failed");
    }
}
