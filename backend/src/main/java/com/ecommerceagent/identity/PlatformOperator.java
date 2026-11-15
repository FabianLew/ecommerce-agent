package com.ecommerceagent.identity;

import java.util.Objects;
import java.util.UUID;

/**
 * A signed-in user who is a platform operator, checked against {@code platform_operator} when it was built. Only
 * {@link PlatformOperators} builds one, so holding it proves the check passed; platform operations re-check it right
 * before they change anything. Platform operators decide about platform money (ADR-0027 section 15); they are not
 * members of the stores they act on, and store users never become one through the application.
 */
public final class PlatformOperator {

    private final UUID userId;

    PlatformOperator(UUID userId) {
        this.userId = Objects.requireNonNull(userId, "userId");
    }

    public UUID userId() {
        return userId;
    }

    /** How the operator is recorded in ledgers and audit details. */
    public String recordedAs() {
        return "PLATFORM_OPERATOR:" + userId;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PlatformOperator operator && operator.userId.equals(userId);
    }

    @Override
    public int hashCode() {
        return userId.hashCode();
    }

    @Override
    public String toString() {
        return recordedAs();
    }
}
