package com.ecommerceagent.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.ecommerceagent.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Integrations API (B-12, ADR-0007) against a stub WooCommerce server. Runtime safety: secrets never leave
 * the backend, store isolation holds, and only STORE_MANAGE may change a connection.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class IntegrationApiTest {

    static final String ISSUER = "https://issuer.test/realms/operator";
    static final UUID ORG = UUID.fromString("50000000-0000-4000-8000-000000000001");
    static final UUID STORE_A = UUID.fromString("60000000-0000-4000-8000-00000000000a");
    static final UUID STORE_B = UUID.fromString("60000000-0000-4000-8000-00000000000b");
    static final UUID OWNER = UUID.fromString("70000000-0000-4000-8000-0000000000a1");
    static final UUID ANALYST = UUID.fromString("70000000-0000-4000-8000-0000000000a2");
    static final UUID OWNER_B = UUID.fromString("70000000-0000-4000-8000-0000000000b1");

    static final String KEY = "ck_stubkey0000000000000000000000000000wxyz";
    static final String SECRET = "cs_stubsecret00000000000000000000000000000";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    HttpServer woo;
    String wooUrl;
    final AtomicReference<String> currency = new AtomicReference<>("PLN");
    final AtomicReference<String> productsBody = new AtomicReference<>("[{\"id\":1}]");
    final AtomicReference<String> productsType = new AtomicReference<>("application/json; charset=UTF-8");

    @BeforeEach
    void setUp() throws IOException {
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
                """).params(OWNER, ISSUER, ANALYST, ISSUER, OWNER_B, ISSUER).update();
        jdbc.sql("""
                INSERT INTO store_membership (organization_id, store_id, user_id, role) VALUES
                  (?, ?, ?, 'OWNER'), (?, ?, ?, 'ANALYST'), (?, ?, ?, 'OWNER')
                """).params(ORG, STORE_A, OWNER, ORG, STORE_A, ANALYST, ORG, STORE_B, OWNER_B).update();

        woo = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        woo.createContext("/wp-json/wc/v3/", this::handle);
        woo.start();
        wooUrl = "http://127.0.0.1:" + woo.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        woo.stop(0);
    }

    /** Accepts only the stub key, sent as an OAuth 1.0a parameter (plain HTTP). */
    private void handle(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getRawQuery();
        boolean signed = query != null && query.contains("oauth_signature=");
        boolean secretCorrect = query != null && query.contains("oauth_consumer_key=" + KEY)
                && exchange.getRequestHeaders().getFirst("Authorization") == null;
        String path = exchange.getRequestURI().getPath();
        if (!signed || !secretCorrect || "wrong".equals(currency.get())) {
            respond(exchange, 401, "application/json", "{\"code\":\"woocommerce_rest_authentication_error\"}");
        } else if (path.endsWith("/products")) {
            respond(exchange, 200, productsType.get(), productsBody.get());
        } else if (path.endsWith("/orders")) {
            respond(exchange, 200, "application/json", "[]");
        } else if (path.endsWith("/settings/general/woocommerce_currency")) {
            respond(exchange, 200, "application/json", "{\"id\":\"woocommerce_currency\",\"value\":\"" + currency.get() + "\"}");
        } else {
            respond(exchange, 404, "application/json", "{\"code\":\"rest_no_route\"}");
        }
    }

    private static void respond(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    static RequestPostProcessor user(String subject) {
        return jwt().jwt(token -> token.issuer(ISSUER).subject(subject).claim("email", subject + "@shop.test"));
    }

    private ResultActions create(String subject, UUID store, String url) throws Exception {
        return mvc.perform(post("/api/v1/stores/{storeId}/integration-connections", store).with(user(subject))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"WOOCOMMERCE\",\"storeUrl\":\"" + url + "\"}"));
    }

    private ResultActions credentials(String subject, UUID store, String id, String key, String secret) throws Exception {
        return mvc.perform(post("/api/v1/stores/{storeId}/integration-connections/{id}/credentials", store, id)
                .with(user(subject)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"consumerKey\":\"" + key + "\",\"consumerSecret\":\"" + secret + "\"}"));
    }

    private ResultActions check(String subject, UUID store, String id) throws Exception {
        return mvc.perform(post("/api/v1/stores/{storeId}/integrations/{id}/checks", store, id).with(user(subject)));
    }

    private String connectedId() throws Exception {
        String body = create("owner", STORE_A, wooUrl).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");
        credentials("owner", STORE_A, id, KEY, SECRET).andExpect(status().isOk());
        return id;
    }

    @Test
    void ownerConnectsAndChecksWooCommerce() throws Exception {
        create("owner", STORE_A, wooUrl + "/")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_CREDENTIALS"))
                .andExpect(jsonPath("$.storeUrl").value(wooUrl))
                .andExpect(jsonPath("$.allowedActions.length()").value(2));
        String id = JsonPath.read(mvc.perform(get("/api/v1/stores/{s}/integrations", STORE_A).with(user("owner")))
                .andReturn().getResponse().getContentAsString(), "$.items[0].id");

        credentials("owner", STORE_A, id, KEY, SECRET)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHECK_REQUIRED"))
                .andExpect(jsonPath("$.keyHint").value("wxyz"));

        check("owner", STORE_A, id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONNECTED"))
                .andExpect(jsonPath("$.warnings.length()").value(0))
                .andExpect(jsonPath("$.sourceCurrency").value("PLN"))
                .andExpect(jsonPath("$.integration.status").value("CONNECTED"))
                .andExpect(jsonPath("$.integration.lastSuccessAt").isNotEmpty());
    }

    @Test
    void secretsNeverLeaveTheBackend() throws Exception {
        String id = connectedId();
        StringBuilder bodies = new StringBuilder();
        bodies.append(check("owner", STORE_A, id).andReturn().getResponse().getContentAsString());
        bodies.append(mvc.perform(get("/api/v1/stores/{s}/integrations", STORE_A).with(user("owner")))
                .andReturn().getResponse().getContentAsString());
        bodies.append(mvc.perform(get("/api/v1/stores/{s}/integrations/{id}", STORE_A, id).with(user("owner")))
                .andReturn().getResponse().getContentAsString());
        bodies.append(credentials("owner", STORE_A, id, KEY, SECRET).andReturn().getResponse().getContentAsString());

        assertThat(bodies.toString()).doesNotContain(SECRET).doesNotContain(KEY).doesNotContain("stubsecret");
        String audit = String.join("\n", jdbc.sql("SELECT action || ' ' || details::text FROM audit_event WHERE store_id = ?")
                .params(STORE_A).query(String.class).list());
        assertThat(audit).contains("INTEGRATION").isNotEmpty();
        assertThat(audit).doesNotContain(SECRET).doesNotContain(KEY);
        List<byte[]> stored = jdbc.sql("SELECT ciphertext FROM integration_credential WHERE store_id = ?")
                .params(STORE_A).query((rs, n) -> rs.getBytes(1)).list();
        assertThat(stored).hasSize(2).allSatisfy(bytes ->
                assertThat(new String(bytes, StandardCharsets.ISO_8859_1)).doesNotContain("stub"));
    }

    @Test
    void analystSeesConnectionButCannotChangeOrCheckIt() throws Exception {
        String id = connectedId();

        mvc.perform(get("/api/v1/stores/{s}/integrations", STORE_A).with(user("analyst")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(id))
                .andExpect(jsonPath("$.items[0].allowedActions.length()").value(0));
        create("analyst", STORE_A, wooUrl).andExpect(status().isForbidden());
        credentials("analyst", STORE_A, id, KEY, SECRET).andExpect(status().isForbidden());
        check("analyst", STORE_A, id).andExpect(status().isForbidden());
    }

    @Test
    void otherStoresConnectionsAreInvisible() throws Exception {
        String id = connectedId();

        mvc.perform(get("/api/v1/stores/{s}/integrations", STORE_B).with(user("owner-b")))
                .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/v1/stores/{s}/integrations/{id}", STORE_B, id).with(user("owner-b")))
                .andExpect(status().isNotFound());
        check("owner-b", STORE_B, id).andExpect(status().isNotFound());
        credentials("owner-b", STORE_B, id, KEY, SECRET).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/stores/{s}/integrations/{id}", STORE_A, id).with(user("owner-b")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/stores/{s}/integrations/{id}", STORE_A, "not-a-uuid").with(user("owner")))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidInputIsRejectedWithFieldErrors() throws Exception {
        create("owner", STORE_A, "ftp://shop.example.com")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("storeUrl"));
        String id = JsonPath.read(create("owner", STORE_A, wooUrl).andReturn().getResponse().getContentAsString(), "$.id");
        create("owner", STORE_A, wooUrl).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_CONNECTED"));

        credentials("owner", STORE_A, id, SECRET, KEY)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("consumerKey"))
                .andExpect(jsonPath("$.fieldErrors[0].code").value("KEY_FORMAT"));
        check("owner", STORE_A, id)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].code").value("CREDENTIALS_MISSING"));
        mvc.perform(post("/api/v1/stores/{s}/integration-connections", STORE_A).with(user("owner"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"provider\":\"SHOPIFY\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void checkReportsRepairableErrors() throws Exception {
        String id = connectedId();

        currency.set("EUR");
        check("owner", STORE_A, id).andExpect(jsonPath("$.status").value("CONNECTED"))
                .andExpect(jsonPath("$.warnings[0]").value("CURRENCY_MISMATCH"));

        currency.set("wrong");
        check("owner", STORE_A, id).andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.errorCode").value("AUTH_FAILED"))
                .andExpect(jsonPath("$.integration.lastErrorCode").value("AUTH_FAILED"))
                .andExpect(jsonPath("$.integration.lastSuccessAt").isNotEmpty());

        currency.set("PLN");
        productsType.set("text/html");
        productsBody.set("<html>Welcome</html>");
        check("owner", STORE_A, id).andExpect(jsonPath("$.errorCode").value("NOT_WOOCOMMERCE"));

        woo.stop(0);
        check("owner", STORE_A, id).andExpect(jsonPath("$.errorCode").value("UNREACHABLE"));
    }

    @Test
    void newCredentialsRequireANewCheck() throws Exception {
        String id = connectedId();
        check("owner", STORE_A, id).andExpect(jsonPath("$.status").value("CONNECTED"));

        credentials("owner", STORE_A, id, KEY, SECRET)
                .andExpect(jsonPath("$.status").value("CHECK_REQUIRED"));
    }
}
