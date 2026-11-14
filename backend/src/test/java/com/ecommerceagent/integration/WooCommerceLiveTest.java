package com.ecommerceagent.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;

import com.ecommerceagent.integration.woocommerce.StoreUrlPolicy;
import com.ecommerceagent.integration.woocommerce.WooCommerceClient;
import com.ecommerceagent.integration.woocommerce.WooCredentials;
import com.ecommerceagent.integration.woocommerce.WooResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Against the real local WooCommerce from infra/docker-compose (seed.sh fixtures). Runs only with
 * {@code WOO_LIVE_URL=http://localhost:8081}; CI runs it in the local environment job. It proves the
 * OAuth 1.0a signature, including parameters with reserved characters, is accepted by WooCommerce.
 */
@EnabledIfEnvironmentVariable(named = "WOO_LIVE_URL", matches = "https?://.+")
class WooCommerceLiveTest {

    static final WooCredentials SEED_KEY = new WooCredentials(
            "ck_local_operator_0000000000000000000000", "cs_local_operator_0000000000000000000000");

    final URI store = URI.create(System.getenv("WOO_LIVE_URL"));
    final WooCommerceClient client = new WooCommerceClient(new StoreUrlPolicy(true, true, host -> null),
            Duration.ofSeconds(5), Duration.ofSeconds(20), 5_000_000, Clock.systemUTC());

    @Test
    void signedRequestWithReservedCharactersIsAccepted() throws Exception {
        WooResponse response = client.get(store, SEED_KEY, "products",
                Map.of("per_page", "20", "modified_after", "2000-01-01T00:00:00", "dates_are_gmt", "true"));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains("\"sku\":\"TSHIRT-BASIC\"").contains("\"sku\":\"MUG-LOGO\"");
        assertThat(response.total()).isNotEmpty();
    }

    @Test
    void wrongSecretIsRejected() throws Exception {
        WooResponse response = client.get(store, new WooCredentials(SEED_KEY.consumerKey(), "cs_wrong"), "products",
                Map.of());

        assertThat(response.status()).isEqualTo(401);
    }

    @Test
    void checkerReportsConnectedStoreInPln() {
        ConnectionCheck check = new WooConnectionChecker(client, Clock.systemUTC()).check(store, SEED_KEY, "PLN");

        assertThat(check.error()).isNull();
        assertThat(check.status()).isEqualTo(ConnectionStatus.CONNECTED);
        assertThat(check.sourceCurrency()).isEqualTo("PLN");
        assertThat(check.warnings()).isEmpty();
    }
}
