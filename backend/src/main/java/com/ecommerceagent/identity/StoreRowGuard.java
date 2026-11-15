package com.ecommerceagent.identity;

import java.util.Optional;

/**
 * The verified store scope whose rows the current thread may touch (ADR-0022 point 3). Bound only where a
 * {@link TrustedStoreScope} is built for a request or an activity; {@link RowSecurityDataSource} applies it to every
 * connection the thread checks out. Unbound work (migrations, jobs that walk all stores) runs with the login role.
 */
public final class StoreRowGuard {

    /** Restores the previous binding when closed. */
    public interface Binding extends AutoCloseable {
        @Override
        void close();
    }

    private static final ThreadLocal<TrustedStoreScope> CURRENT = new ThreadLocal<>();

    private StoreRowGuard() {
    }

    public static Binding bind(TrustedStoreScope scope) {
        TrustedStoreScope previous = CURRENT.get();
        CURRENT.set(java.util.Objects.requireNonNull(scope, "scope"));
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    public static Optional<TrustedStoreScope> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** Drops any binding of this thread, e.g. after a request whose resolver bound a scope. */
    public static void clear() {
        CURRENT.remove();
    }
}
