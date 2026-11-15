package com.ecommerceagent.identity;

import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs a system job store by store (ADR-0022 point 4): each store's work runs bound to that store's system scope,
 * so row-level security checks it like a user's request. A failure in one store is logged and does not stop the
 * others. There is no cross-store variant: a question that spans stores is a reviewed database function.
 */
@Component
public class SystemStoreRunner {

    private static final Logger log = LoggerFactory.getLogger(SystemStoreRunner.class);

    private final StoreAccess storeAccess;

    SystemStoreRunner(StoreAccess storeAccess) {
        this.storeAccess = storeAccess;
    }

    /** Returns the number of stores whose work failed. */
    public int forEachStore(String service, Consumer<TrustedStoreScope> work) {
        int failed = 0;
        for (TrustedStoreScope scope : storeAccess.systemScopes(service)) {
            try (StoreRowGuard.Binding ignored = StoreRowGuard.bind(scope)) {
                work.accept(scope);
            } catch (RuntimeException e) {
                failed++;
                log.warn("System job {} failed for store {}", service, scope.storeId(), e);
            }
        }
        return failed;
    }
}
