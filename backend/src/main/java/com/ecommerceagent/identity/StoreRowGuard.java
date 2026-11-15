package com.ecommerceagent.identity;

import java.util.Objects;
import java.util.Optional;

/**
 * The verified store scope whose rows the current thread may touch (ADR-0022 point 3). Bound only where
 * {@link StoreAccess} built a {@link TrustedStoreScope} for a request, an activity or one store of a system job;
 * {@link RowSecurityDataSource} applies it to every connection the thread checks out. Unbound, store tables show and
 * accept no rows. A thread holds at most one store: binding another store while one is bound is refused, binding the
 * same store again is a no-op.
 */
public final class StoreRowGuard {

    /** Removes the binding when closed (a no-op for a repeated binding of the same store). */
    public interface Binding extends AutoCloseable {
        @Override
        void close();
    }

    private static final ThreadLocal<TrustedStoreScope> CURRENT = new ThreadLocal<>();

    private StoreRowGuard() {
    }

    public static Binding bind(TrustedStoreScope scope) {
        Objects.requireNonNull(scope, "scope");
        TrustedStoreScope previous = CURRENT.get();
        if (previous != null) {
            if (previous.storeId().equals(scope.storeId())
                    && previous.organizationId().equals(scope.organizationId())) {
                return () -> { };
            }
            throw new IllegalStateException("Another store is already bound to this thread");
        }
        CURRENT.set(scope);
        return CURRENT::remove;
    }

    public static Optional<TrustedStoreScope> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** Drops any binding of this thread, e.g. after a request whose resolver bound a scope. */
    public static void clear() {
        CURRENT.remove();
    }
}
