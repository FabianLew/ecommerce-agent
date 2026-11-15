package com.ecommerceagent.automation.notifications;

import org.springframework.modulith.NamedInterface;

/** What a notification is about; the UI and the e-mail text are rendered from the kind and its parameters. */
@NamedInterface("notifications")
public enum NotificationKind {
    /** L0 (ADR-0026 point 1): confirmed cover below the warning threshold; proposes nothing. */
    STOCK_WARNING(Severity.WARNING),
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
