package com.ecommerceagent.web;

import static com.ecommerceagent.web.IntegrationApiTest.ANALYST;
import static com.ecommerceagent.web.IntegrationApiTest.ISSUER;
import static com.ecommerceagent.web.IntegrationApiTest.ORG;
import static com.ecommerceagent.web.IntegrationApiTest.OWNER;
import static com.ecommerceagent.web.IntegrationApiTest.OWNER_B;
import static com.ecommerceagent.web.IntegrationApiTest.STORE_A;
import static com.ecommerceagent.web.IntegrationApiTest.STORE_B;
import static com.ecommerceagent.web.IntegrationApiTest.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ecommerceagent.FixtureDb;
import com.ecommerceagent.TestcontainersConfiguration;
import com.ecommerceagent.identity.StoreAccess;
import com.ecommerceagent.integration.MetaAdSync;
import com.ecommerceagent.integration.meta.GraphApiStub;
import com.ecommerceagent.integration.meta.GraphApiStub.Reply;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Meta connection API and ad polling (B-31, ADR-0017) against a Graph API stub. Runtime safety: the token never
 * leaves the backend, only STORE_MANAGE changes the connection, and polled ads land only in the connection's store.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"api", "test"})
@Import(TestcontainersConfiguration.class)
class MetaConnectionApiTest {

    static final String TOKEN = GraphApiStub.TOKEN;
    static final GraphApiStub GRAPH;

    static {
        try {
            GRAPH = new GraphApiStub();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void graph(DynamicPropertyRegistry registry) {
        registry.add("operator.integration.meta.graph-base-url", () -> GRAPH.baseUrl().toString());
    }

    @AfterAll
    static void stopGraph() {
        GRAPH.close();
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    FixtureDb jdbc;

    @Autowired
    MetaAdSync sync;

    @Autowired
    StoreAccess storeAccess;

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
                """).params(OWNER, ISSUER, ANALYST, ISSUER, OWNER_B, ISSUER).update();
        jdbc.sql("""
                INSERT INTO store_membership (organization_id, store_id, user_id, role) VALUES
                  (?, ?, ?, 'OWNER'), (?, ?, ?, 'ANALYST'), (?, ?, ?, 'OWNER')
                """).params(ORG, STORE_A, OWNER, ORG, STORE_A, ANALYST, ORG, STORE_B, OWNER_B).update();
        GRAPH.reset();
        GRAPH.on("GET act_123", Reply.ok("{\"id\":\"act_123\",\"name\":\"Shop A ads\",\"account_status\":1,"
                + "\"currency\":\"PLN\"}"));
        GRAPH.on("GET me/permissions", Reply.ok("{\"data\":[{\"permission\":\"ads_read\",\"status\":\"granted\"},"
                + "{\"permission\":\"ads_management\",\"status\":\"granted\"}]}"));
        GRAPH.on("GET act_123/ads", Reply.ok("{\"data\":["
                + "{\"id\":\"9001\",\"name\":\"Serum\",\"status\":\"ACTIVE\",\"effective_status\":\"ACTIVE\","
                + "\"creative\":{\"id\":\"c-1\"}},"
                + "{\"id\":\"9002\",\"name\":\"Catalog\",\"status\":\"PAUSED\",\"effective_status\":\"PAUSED\"},"
                + "{\"id\":\"9003\",\"name\":\"Odd\",\"status\":\"IN_REVIEW\",\"effective_status\":\"PENDING_REVIEW\"}"
                + "]}"));
    }

    private ResultActions create(String subject, UUID store, String accountId) throws Exception {
        return mvc.perform(post("/api/v1/stores/{storeId}/meta-connection", store).with(user(subject))
                .contentType(MediaType.APPLICATION_JSON).content("{\"accountId\":\"" + accountId + "\"}"));
    }

    private ResultActions token(String subject, UUID store, String id, String token) throws Exception {
        return mvc.perform(post("/api/v1/stores/{storeId}/meta-connection/{id}/token", store, id).with(user(subject))
                .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + token + "\"}"));
    }

    private String connect() throws Exception {
        String body = create("owner", STORE_A, "act_123").andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountId").value("123"))
                .andExpect(jsonPath("$.status").value("PENDING_CREDENTIALS"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");
        token("owner", STORE_A, id, TOKEN).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHECK_REQUIRED"))
                .andExpect(jsonPath("$.tokenHint").value("wxyz"));
        mvc.perform(post("/api/v1/stores/{storeId}/meta-connection/{id}/checks", STORE_A, id).with(user("owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONNECTED"))
                .andExpect(jsonPath("$.accountName").value("Shop A ads"))
                .andExpect(jsonPath("$.canWrite").value(true));
        return id;
    }

    @Test
    void connectsAnAccountAndNeverReturnsTheToken() throws Exception {
        String id = connect();
        String body = mvc.perform(get("/api/v1/stores/{storeId}/meta-connection", STORE_A).with(user("owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connection.id").value(id))
                .andExpect(jsonPath("$.connection.allowedActions[1]").value("RUN_CHECK"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(TOKEN.substring(0, 20));
        // Stored only encrypted.
        byte[] ciphertext = jdbc.sql("SELECT ciphertext FROM meta_credential").query(byte[].class).single();
        assertThat(new String(ciphertext, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("EAAstub");
        assertThat(GRAPH.requests).allSatisfy(r -> assertThat(r.uri().toString()).doesNotContain(TOKEN));
        create("owner", STORE_A, "456").andExpect(status().isConflict());
    }

    @Test
    void validatesInputAndRequiresStoreManage() throws Exception {
        create("owner", STORE_A, "act_12x").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].code").value("ACCOUNT_ID_FORMAT"));
        create("analyst", STORE_A, "123").andExpect(status().isForbidden());
        String id = JsonPath.read(create("owner", STORE_A, "123").andReturn().getResponse().getContentAsString(),
                "$.id");
        token("owner", STORE_A, id, "short").andExpect(status().isUnprocessableContent());
        token("analyst", STORE_A, id, TOKEN).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/stores/{storeId}/meta-connection", STORE_A).with(user("analyst")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canCreate").value(false))
                .andExpect(jsonPath("$.connection.allowedActions").isEmpty());
    }

    @Test
    void anotherStoreCannotSeeOrChangeTheConnection() throws Exception {
        String id = connect();
        mvc.perform(get("/api/v1/stores/{storeId}/meta-connection", STORE_B).with(user("owner-b")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.connection").doesNotExist());
        token("owner-b", STORE_B, id, TOKEN).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/stores/{storeId}/meta-connection/{id}/checks", STORE_B, id).with(user("owner-b")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/stores/{storeId}/meta-connection", STORE_A).with(user("owner-b")))
                .andExpect(status().isNotFound());
    }

    @Test
    void aMissingWritePermissionIsShownAndAWrongTokenIsAnError() throws Exception {
        GRAPH.on("GET me/permissions", Reply.ok("{\"data\":[{\"permission\":\"ads_read\",\"status\":\"granted\"}]}"));
        String id = JsonPath.read(create("owner", STORE_A, "123").andReturn().getResponse().getContentAsString(),
                "$.id");
        token("owner", STORE_A, id, TOKEN);
        mvc.perform(post("/api/v1/stores/{storeId}/meta-connection/{id}/checks", STORE_A, id).with(user("owner")))
                .andExpect(jsonPath("$.status").value("CONNECTED")).andExpect(jsonPath("$.canWrite").value(false));

        token("owner", STORE_A, id, "EAAwrongtoken00000000000000000000");
        mvc.perform(post("/api/v1/stores/{storeId}/meta-connection/{id}/checks", STORE_A, id).with(user("owner")))
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.lastErrorCode").value("AUTH_FAILED"))
                .andExpect(jsonPath("$.canWrite").value(false));
    }

    @Test
    void pollingUpsertsAdsOnlyIntoTheConnectionsStore() throws Exception {
        connect();
        assertThat(sync.syncDue()).isEqualTo(1);

        List<Map<String, Object>> ads = jdbc.sql("""
                SELECT store_id, external_id, account_external_id, own_status, effective_status, creative_fingerprint,
                       source FROM ad ORDER BY external_id
                """).query().listOfRows();
        assertThat(ads).hasSize(2);
        assertThat(ads).allSatisfy(row -> {
            assertThat(row.get("store_id")).isEqualTo(STORE_A);
            assertThat(row.get("source")).isEqualTo("META_API");
            assertThat(row.get("account_external_id")).isEqualTo("act_123");
        });
        assertThat(ads.getFirst().get("creative_fingerprint")).isEqualTo("meta-creative:c-1");
        assertThat(ads.get(1).get("creative_fingerprint")).isEqualTo("meta-creative:none");
        mvc.perform(get("/api/v1/stores/{storeId}/meta-connection", STORE_A).with(user("owner")))
                .andExpect(jsonPath("$.connection.adsSeen").value(2))
                .andExpect(jsonPath("$.connection.lastSyncAt").isNotEmpty());
        // A new creative replaces the fingerprint, so a verified mapping no longer matches (IG-09).
        GRAPH.on("GET act_123/ads", Reply.ok("{\"data\":[{\"id\":\"9001\",\"name\":\"Serum\",\"status\":\"PAUSED\","
                + "\"effective_status\":\"PAUSED\",\"creative\":{\"id\":\"c-2\"}}]}"));
        sync.syncDue();
        assertThat(jdbc.sql("SELECT creative_fingerprint || '/' || own_status FROM ad WHERE external_id = '9001'")
                .query(String.class).single()).isEqualTo("meta-creative:c-2/PAUSED");
    }

    @Test
    void aRateLimitPostponesTheNextPoll() throws Exception {
        connect();
        GRAPH.on("GET act_123/ads", Reply.error(400, 17, 2446079).withHeader("Retry-After", "600"));
        sync.syncDue();
        assertThat(jdbc.sql("SELECT last_sync_error FROM meta_connection").query(String.class).single())
                .isEqualTo("RATE_LIMITED");
        assertThat(jdbc.sql("SELECT next_sync_after > now() + interval '500 seconds' FROM meta_connection")
                .query(Boolean.class).single()).isTrue();
        int before = GRAPH.requests.size();
        assertThat(sync.syncDue()).isZero();
        assertThat(GRAPH.requests).hasSize(before);
    }

    @Test
    void anExpiredTokenStopsPollingUntilANewCheck() throws Exception {
        connect();
        GRAPH.on("GET act_123/ads", Reply.error(400, 190, 463));
        sync.syncDue();
        assertThat(jdbc.sql("SELECT status FROM meta_connection").query(String.class).single()).isEqualTo("ERROR");
        assertThat(sync.syncDue()).isZero();
    }
}
