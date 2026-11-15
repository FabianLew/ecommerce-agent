package com.ecommerceagent.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import com.ecommerceagent.FixtureDb;
import com.ecommerceagent.TestcontainersConfiguration;
import com.ecommerceagent.identity.StoreRowGuard;
import com.ecommerceagent.identity.TestScopes;
import com.ecommerceagent.identity.TrustedStoreScope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/** ADR-0022 point 4: a credential sealed with a previous key is readable and moves to the current key on use. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "operator.integration.secrets-key=" + CredentialRotationTest.NEW_KEY,
        "operator.integration.secrets-key-id=test-v2",
        "operator.integration.previous-secrets-keys.test-v1=" + CredentialRotationTest.OLD_KEY})
class CredentialRotationTest {

    static final String OLD_KEY = "+8ixWUDP7q0PlVTSA1mCnWZsD8tYl+VhgMS+hqXdv1w=";
    static final String NEW_KEY = "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=";

    @Autowired
    FixtureDb jdbc;

    @Autowired
    IntegrationConnectionRepository repository;

    @Autowired
    SecretCipher cipher;

    @Test
    void aValueOfThePreviousKeyIsResealedWhenOpened() {
        UUID organization = UUID.randomUUID();
        UUID store = UUID.randomUUID();
        UUID connection = UUID.randomUUID();
        jdbc.sql("INSERT INTO organization (id, name) VALUES (?, 'Rotation')").param(organization).update();
        jdbc.sql("INSERT INTO store (id, organization_id, name, currency, timezone) VALUES (?, ?, 'S', 'PLN', 'UTC')")
                .params(store, organization).update();
        jdbc.sql("""
                        INSERT INTO integration_connection (id, organization_id, store_id, provider, store_url, status,
                                                            created_at, updated_at, webhook_endpoint_key)
                        VALUES (?, ?, ?, 'WOOCOMMERCE', 'https://shop.example', 'CHECK_REQUIRED', now(), now(), ?)
                        """)
                .params(connection, organization, store, UUID.randomUUID().toString().replace("-", "")).update();
        TrustedStoreScope scope = TestScopes.system(organization, store);
        String associatedData = IntegrationConnections.associatedData(scope, connection, "consumer_key");
        SecretCipher previous = new SecretCipher(OLD_KEY, "test-v1");

        try (StoreRowGuard.Binding ignored = StoreRowGuard.bind(scope)) {
            repository.saveCredential(scope, connection, "consumer_key", previous.seal("ck_value", associatedData));
            SecretCipher.Sealed stored = repository.credential(scope, connection, "consumer_key").orElseThrow();
            assertThat(stored.keyId()).isEqualTo("test-v1");

            assertThat(IntegrationConnections.openAndRefresh(repository, cipher, scope, connection, "consumer_key",
                    stored)).isEqualTo("ck_value");

            SecretCipher.Sealed resealed = repository.credential(scope, connection, "consumer_key").orElseThrow();
            assertThat(resealed.keyId()).isEqualTo("test-v2");
            assertThat(cipher.open(resealed, associatedData)).isEqualTo("ck_value");
        }
    }
}
