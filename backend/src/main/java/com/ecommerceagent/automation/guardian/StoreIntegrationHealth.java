package com.ecommerceagent.automation.guardian;

import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.integration.IntegrationConnections;
import com.ecommerceagent.integration.IntegrationHealth;
import com.ecommerceagent.integration.IntegrationHealthService;
import com.ecommerceagent.integration.IntegrationProvider;
import org.springframework.stereotype.Component;

/** Health of the store's WooCommerce connection for the policy; null when the store has none. */
@Component
public class StoreIntegrationHealth {

    private final IntegrationConnections connections;
    private final IntegrationHealthService health;

    StoreIntegrationHealth(IntegrationConnections connections, IntegrationHealthService health) {
        this.connections = connections;
        this.health = health;
    }

    public IntegrationHealth.Status status(TrustedStoreScope scope) {
        return connections.list(scope).stream()
                .filter(connection -> connection.provider() == IntegrationProvider.WOOCOMMERCE)
                .findFirst()
                .map(connection -> health.health(scope, connection).status())
                .orElse(null);
    }
}
