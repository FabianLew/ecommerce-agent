package com.ecommerceagent.automation.notifications;

import org.springframework.modulith.NamedInterface;

/** What a notification is about; the UI and the e-mail text are rendered from the kind and its parameters. */
@NamedInterface("notifications")
public enum NotificationKind {
    DECISION_NEEDED(Severity.INFO),
    ACTION_VERIFIED(Severity.INFO),
    ACTION_NOOP(Severity.INFO),
    ACTION_UNKNOWN(Severity.CRITICAL),
    ACTION_FAILED(Severity.WARNING),
    ACTION_CONFLICT(Severity.WARNING);

    public enum Severity {
        INFO,
        WARNING,
        CRITICAL
    }

    private final Severity severity;

    NotificationKind(Severity severity) {
        this.severity = severity;
    }

    public Severity severity() {
        return severity;
    }
}
