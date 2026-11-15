package com.ecommerceagent.web;

import static com.ecommerceagent.web.IntegrationApiTest.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.ecommerceagent.FixtureDb;
import com.ecommerceagent.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Public WooCommerce webhook endpoint (B-14, ADR-0009). Runtime safety: only a correctly signed delivery is
 * stored, the store comes from the endpoint key, customer data never reaches the database and the webhook
 * secret never leaves the backend.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class WooWebhookApiTest {

    static final UUID ORG = IntegrationApiTest.ORG;
    static final UUID STORE_A = IntegrationApiTest.STORE_A;
    static final UUID STORE_B = IntegrationApiTest.STORE_B;
    static final String SECRET_A = "whsec-store-a-0123456789abcdef";
    static final String SECRET_B = "whsec-store-b-0123456789abcdef";
    static final String ORDER = """
            {"id":501,"status":"processing","currency":"PLN","total":"79.00","total_tax":"14.77",
             "discount_total":"0.00","shipping_total":"0.00","date_created_gmt":"2026-11-14T10:00:00",
             "date_modified_gmt":"2026-11-14T10:00:00","date_paid_gmt":"2026-11-14T10:00:00",
             "billing":{"first_name":"Jan","email":"jan.kowalski@example.com","phone":"+48600100200"},
             "shipping":{"address_1":"ul. Prosta 1"},"customer_ip_address":"203.0.113.7",
             "customer_user_agent":"Mozilla","customer_note":"Please call me",
             "line_items":[{"id":9,"product_id":14,"variation_id":0,"sku":"MUG","quantity":1,"total":"64.23",
                            "total_tax":"14.77","meta_data":[{"key":"Dedication","value":"For John"}]}]}
            """;

    @Autowired
    MockMvc mvc;

    @Autowired
    FixtureDb jdbc;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE store_membership, app_user, store, organization CASCADE").update();
        jdbc.sql("INSERT INTO organization (id, name) VALUES (?, 'Org')").params(ORG).update();
        jdbc.sql("""
                INSERT INTO store (id, organization_id, name, currency, timezone) VALUES
                  (?, ?, 'Store A', 'PLN', 'Europe/Warsaw'), (?, ?, 'Store B', 'PLN', 'Europe/Warsaw')
                """).params(STORE_A, ORG, STORE_B, ORG).update();
        jdbc.sql("""
                INSERT INTO app_user (id, issuer, subject, email) VALUES
                  (?, ?, 'owner', 'owner@shop.test'), (?, ?, 'analyst', 'analyst@shop.test'),
                  (?, ?, 'owner-b', 'owner-b@shop.test')
                """).params(IntegrationApiTest.OWNER, IntegrationApiTest.ISSUER, IntegrationApiTest.ANALYST,
                IntegrationApiTest.ISSUER, IntegrationApiTest.OWNER_B, IntegrationApiTest.ISSUER).update();
        jdbc.sql("""
                INSERT INTO store_membership (organization_id, store_id, user_id, role) VALUES
                  (?, ?, ?, 'OWNER'), (?, ?, ?, 'ANALYST'), (?, ?, ?, 'OWNER')
                """).params(ORG, STORE_A, IntegrationApiTest.OWNER, ORG, STORE_A, IntegrationApiTest.ANALYST,
                ORG, STORE_B, IntegrationApiTest.OWNER_B).update();
    }

    @Test
    void ownerSavesSecretAndSeesWhatToEnterInWooCommerce() throws Exception {
        String id = connection("owner", STORE_A);
        String body = secret("owner", STORE_A, id, SECRET_A)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.webhook.secretConfigured").value(true))
                .andExpect(jsonPath("$.webhook.apiVersion").value("wp_api_v3"))
                .andExpect(jsonPath("$.webhook.topics.length()").value(4))
                .andExpect(jsonPath("$.webhook.deliveryUrl").value(org.hamcrest.Matchers.matchesPattern(
                        "https://api\\.example\\.test/api/v1/webhooks/woocommerce/[0-9a-f]{32}")))
                .andExpect(jsonPath("$.allowedActions").value(org.hamcrest.Matchers.hasItem("SAVE_WEBHOOK_SECRET")))
                .andReturn().getResponse().getContentAsString();
        String list = mvc.perform(get("/api/v1/stores/{s}/integrations", STORE_A).with(user("owner")))
                .andReturn().getResponse().getContentAsString();

        assertThat(body + list).doesNotContain(SECRET_A).doesNotContain("whsec");
        assertThat(audit(STORE_A)).contains("INTEGRATION_WEBHOOK_SECRET_UPDATED").doesNotContain("whsec");
        List<byte[]> stored = jdbc.sql("SELECT ciphertext FROM integration_credential WHERE name = ?")
                .params("woocommerce.webhook_secret").query((rs, n) -> rs.getBytes(1)).list();
        assertThat(stored).hasSize(1).allSatisfy(bytes ->
                assertThat(new String(bytes, StandardCharsets.ISO_8859_1)).doesNotContain("whsec"));
    }

    @Test
    void analystCannotSetTheSecretAndWeakSecretsAreRejected() throws Exception {
        String id = connection("owner", STORE_A);
        secret("analyst", STORE_A, id, SECRET_A).andExpect(status().isForbidden());
        secret("owner", STORE_A, id, "short").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].code").value("SECRET_FORMAT"));
        secret("owner", STORE_A, id, "has spaces in the secret value").andExpect(status().isUnprocessableContent());
        // Another store's owner cannot reach this connection.
        secret("owner-b", STORE_B, id, SECRET_A).andExpect(status().isNotFound());
    }

    @Test
    void signedOrderIsStoredOnceWithoutCustomerData() throws Exception {
        String key = endpointKey(STORE_A, connection("owner", STORE_A));
        secret("owner", STORE_A, connectionId(STORE_A), SECRET_A).andExpect(status().isOk());

        deliver(key, "order.created", ORDER, SECRET_A).andExpect(status().isOk())
                .andExpect(jsonPath("$.receipt").value("ACCEPTED"));
        deliver(key, "order.created", ORDER, SECRET_A).andExpect(status().isOk())
                .andExpect(jsonPath("$.receipt").value("DUPLICATE"));

        List<String> payloads = jdbc.sql("SELECT payload::text FROM webhook_inbox WHERE store_id = ?")
                .params(STORE_A).query(String.class).list();
        assertThat(payloads).hasSize(1);
        assertThat(payloads.getFirst()).contains("\"line_items\"").contains("\"date_created_gmt\"")
                .doesNotContain("billing").doesNotContain("jan.kowalski").doesNotContain("+48600100200")
                .doesNotContain("Prosta").doesNotContain("203.0.113.7").doesNotContain("Mozilla")
                .doesNotContain("Please call").doesNotContain("Dedication").doesNotContain("For John");
        assertThat(jdbc.sql("SELECT last_webhook_at FROM integration_connection WHERE store_id = ?")
                .params(STORE_A).query(java.sql.Timestamp.class).single()).isNotNull();
    }

    @Test
    void unsignedOrWronglySignedDeliveriesAreRejected() throws Exception {
        String key = endpointKey(STORE_A, connection("owner", STORE_A));
        // No secret saved yet: nothing can be verified.
        deliver(key, "order.created", ORDER, SECRET_A).andExpect(status().isUnauthorized());
        secret("owner", STORE_A, connectionId(STORE_A), SECRET_A).andExpect(status().isOk());

        deliver(key, "order.created", ORDER, "whsec-wrong-0123456789abcdef").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("WEBHOOK_SIGNATURE_INVALID"));
        mvc.perform(post("/api/v1/webhooks/woocommerce/{key}", key).contentType(MediaType.APPLICATION_JSON)
                        .header("X-WC-Webhook-Topic", "order.created").content(ORDER))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/webhooks/woocommerce/{key}", key).contentType(MediaType.APPLICATION_JSON)
                        .header("X-WC-Webhook-Topic", "order.created").header("X-WC-Webhook-Signature", "not base64!")
                        .content(ORDER))
                .andExpect(status().isUnauthorized());
        // A signature over a different body does not match (the raw bytes are signed).
        mvc.perform(post("/api/v1/webhooks/woocommerce/{key}", key).contentType(MediaType.APPLICATION_JSON)
                        .header("X-WC-Webhook-Topic", "order.created")
                        .header("X-WC-Webhook-Signature", sign(SECRET_A, ORDER))
                        .content(ORDER.replace("79.00", "1.00")))
                .andExpect(status().isUnauthorized());

        assertThat(inboxCount()).isZero();
    }

    @Test
    void storeComesFromTheEndpointKeyNotFromTheSender() throws Exception {
        String keyA = endpointKey(STORE_A, connection("owner", STORE_A));
        String keyB = endpointKey(STORE_B, connection("owner-b", STORE_B));
        secret("owner", STORE_A, connectionId(STORE_A), SECRET_A).andExpect(status().isOk());
        secret("owner-b", STORE_B, connectionId(STORE_B), SECRET_B).andExpect(status().isOk());

        // Store B's secret does not open store A's endpoint.
        deliver(keyA, "order.created", ORDER, SECRET_B).andExpect(status().isUnauthorized());
        deliver(keyB, "order.created", ORDER, SECRET_B).andExpect(status().isOk());

        assertThat(jdbc.sql("SELECT store_id FROM webhook_inbox").query(UUID.class).list()).containsExactly(STORE_B);
    }

    @Test
    void pingUnknownEndpointOversizedBodyAndOtherTopics() throws Exception {
        String key = endpointKey(STORE_A, connection("owner", STORE_A));
        // Woo's unsigned test delivery when a webhook is saved, before our secret exists.
        mvc.perform(post("/api/v1/webhooks/woocommerce/{key}", key)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).content("webhook_id=12"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.receipt").value("PING"));

        mvc.perform(post("/api/v1/webhooks/woocommerce/{key}", "0".repeat(32))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).content("webhook_id=12"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/webhooks/woocommerce/{key}", "not-a-key")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());

        secret("owner", STORE_A, connectionId(STORE_A), SECRET_A).andExpect(status().isOk());
        String big = "{\"id\":1,\"pad\":\"" + "x".repeat(1024 * 1024) + "\"}";
        deliver(key, "product.updated", big, SECRET_A).andExpect(status().is(413));
        deliver(key, "coupon.created", "{\"id\":3}", SECRET_A).andExpect(status().isOk())
                .andExpect(jsonPath("$.receipt").value("IGNORED"));
        deliver(key, "product.updated", "[1,2]", SECRET_A).andExpect(status().isOk())
                .andExpect(jsonPath("$.receipt").value("IGNORED"));

        assertThat(inboxCount()).isZero();
    }

    private ResultActions deliver(String key, String topic, String body, String secret) throws Exception {
        return mvc.perform(post("/api/v1/webhooks/woocommerce/{key}", key)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-WC-Webhook-Topic", topic)
                .header("X-WC-Webhook-Resource", topic.substring(0, topic.indexOf('.')))
                .header("X-WC-Webhook-ID", "7")
                .header("X-WC-Webhook-Delivery-ID", UUID.randomUUID().toString().replace("-", ""))
                .header("X-WC-Webhook-Signature", sign(secret, body))
                .content(body.getBytes(StandardCharsets.UTF_8)));
    }

    static String sign(String secret, String body) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private String connection(String subject, UUID store) throws Exception {
        String body = mvc.perform(post("/api/v1/stores/{storeId}/integration-connections", store).with(user(subject))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"WOOCOMMERCE\",\"storeUrl\":\"https://shop.example.com\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private String connectionId(UUID store) {
        return jdbc.sql("SELECT id FROM integration_connection WHERE store_id = ?").params(store)
                .query(UUID.class).single().toString();
    }

    private String endpointKey(UUID store, String connectionId) {
        return jdbc.sql("SELECT webhook_endpoint_key FROM integration_connection WHERE id = ? AND store_id = ?")
                .params(UUID.fromString(connectionId), store).query(String.class).single();
    }

    private ResultActions secret(String subject, UUID store, String id, String secret) throws Exception {
        return mvc.perform(post("/api/v1/stores/{storeId}/integration-connections/{id}/webhook-secret", store, id)
                .with(user(subject)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"secret\":\"" + secret + "\"}"));
    }

    private long inboxCount() {
        return jdbc.sql("SELECT count(*) FROM webhook_inbox").query(Long.class).single();
    }

    private String audit(UUID store) {
        return String.join("\n", jdbc.sql("SELECT action || ' ' || details::text FROM audit_event WHERE store_id = ?")
                .params(store).query(String.class).list());
    }
}
