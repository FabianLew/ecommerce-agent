package com.ecommerceagent.identity;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Who acts within a {@link TrustedStoreScope} (ADR-0004): a signed-in user, or a backend service
 * running without one (e.g. scheduled monitoring). The type itself grants nothing; scopes carrying
 * an actor are only built by {@link StoreAccess} after a database check.
 */
public sealed interface Actor {

    /** Value stored in audit records and workflow headers. */
    String kind();

    record UserActor(UUID userId) implements Actor {

        public UserActor {
            Objects.requireNonNull(userId, "userId");
        }

        @Override
        public String kind() {
            return "USER";
        }
    }

    record SystemActor(String service) implements Actor {

        private static final Pattern SERVICE_NAME = Pattern.compile("[a-z][a-z0-9-]{2,63}");

        public SystemActor {
            if (service == null || !SERVICE_NAME.matcher(service).matches()) {
                throw new IllegalArgumentException("Invalid system service name: " + service);
            }
        }

        @Override
        public String kind() {
            return "SYSTEM";
        }
    }
}
