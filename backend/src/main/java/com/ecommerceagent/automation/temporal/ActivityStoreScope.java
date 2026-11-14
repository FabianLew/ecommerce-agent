package com.ecommerceagent.automation.temporal;

import com.ecommerceagent.identity.TrustedStoreScope;

/**
 * The verified store scope of the running activity, set by {@link StoreScopeActivityInterceptor}.
 * Activities read the scope only from here, never from their arguments.
 */
public final class ActivityStoreScope {

    private static final ThreadLocal<TrustedStoreScope> CURRENT = new ThreadLocal<>();

    private ActivityStoreScope() {
    }

    public static TrustedStoreScope current() {
        TrustedStoreScope scope = CURRENT.get();
        if (scope == null) {
            throw new IllegalStateException("No verified store scope for this activity");
        }
        return scope;
    }

    static void set(TrustedStoreScope scope) {
        CURRENT.set(scope);
    }

    static void clear() {
        CURRENT.remove();
    }
}
