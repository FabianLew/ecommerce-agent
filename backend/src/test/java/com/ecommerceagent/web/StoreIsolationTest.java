package com.ecommerceagent.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import com.ecommerceagent.FixtureDb;
import com.ecommerceagent.TestcontainersConfiguration;
import com.ecommerceagent.identity.StorePermission;
import com.ecommerceagent.identity.TrustedStoreScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runtime safety (ADR-0004): a member of store A never sees store B, and cannot tell whether a
 * store it has no access to exists. Part of the merge-blocking safety tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import({TestcontainersConfiguration.class, StoreIsolationTest.ManageProbeController.class})
class StoreIsolationTest {

    /** Stand-in for the first STORE_MANAGE endpoint until one exists in the product API. */
    @RestController
    static class ManageProbeController {

        @PostMapping("/api/v1/stores/{storeId}/test-manage-probe")
        Map<String, String> manage(@RequiresPermission(StorePermission.STORE_MANAGE) TrustedStoreScope scope) {
            return Map.of("storeId", scope.storeId().toString());
        }
    }

    static final String ISSUER = "https://issuer.test/realms/operator";

    static final UUID ORG = UUID.fromString("10000000-0000-4000-8000-000000000001");
    static final UUID OTHER_ORG = UUID.fromString("10000000-0000-4000-8000-000000000002");
    static final UUID STORE_A = UUID.fromString("20000000-0000-4000-8000-00000000000a");
    static final UUID STORE_B = UUID.fromString("20000000-0000-4000-8000-00000000000b");
    static final UUID STORE_C = UUID.fromString("20000000-0000-4000-8000-00000000000c");
    static final UUID USER_A = UUID.fromString("30000000-0000-4000-8000-0000000000a1");
    static final UUID USER_B = UUID.fromString("30000000-0000-4000-8000-0000000000b1");

    @Autowired
    MockMvc mvc;

    @Autowired
    FixtureDb jdbc;

    @BeforeEach
    void tenants() {
        jdbc.sql("TRUNCATE store_membership, app_user, store, organization CASCADE").update();
        jdbc.sql("INSERT INTO organization (id, name) VALUES (?, 'Org'), (?, 'Other org')")
                .params(ORG, OTHER_ORG).update();
        jdbc.sql("""
                INSERT INTO store (id, organization_id, name, currency, timezone) VALUES
                  (?, ?, 'Store A', 'PLN', 'Europe/Warsaw'),
                  (?, ?, 'Store B', 'PLN', 'Europe/Warsaw'),
                  (?, ?, 'Store C', 'EUR', 'Europe/Berlin')
                """).params(STORE_A, ORG, STORE_B, ORG, STORE_C, OTHER_ORG).update();
        jdbc.sql("""
                INSERT INTO app_user (id, issuer, subject, email) VALUES
                  (?, ?, 'subject-a', 'a@shop.test'), (?, ?, 'subject-b', 'b@shop.test')
                """).params(USER_A, ISSUER, USER_B, ISSUER).update();
        jdbc.sql("""
                INSERT INTO store_membership (organization_id, store_id, user_id, role) VALUES
                  (?, ?, ?, 'OWNER'), (?, ?, ?, 'ANALYST'), (?, ?, ?, 'OWNER')
                """).params(ORG, STORE_A, USER_A, ORG, STORE_A, USER_B, ORG, STORE_B, USER_B).update();
    }

    static RequestPostProcessor user(String subject) {
        return jwt().jwt(token -> token.issuer(ISSUER).subject(subject).claim("email", subject + "@shop.test"));
    }

    @Test
    void requestsWithoutTokenAreRejected() throws Exception {
        mvc.perform(get("/api/v1/stores")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/stores/{id}", STORE_A)).andExpect(status().isUnauthorized());
    }

    @Test
    void userListsOnlyStoresWithMembership() throws Exception {
        mvc.perform(get("/api/v1/stores").with(user("subject-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].storeId").value(STORE_A.toString()))
                .andExpect(jsonPath("$.items[0].roles[0]").value("OWNER"))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void memberOfStoreAGets404ForStoreB() throws Exception {
        String body = mvc.perform(get("/api/v1/stores/{id}", STORE_B).with(user("subject-a")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(STORE_B.toString()).doesNotContain("Store B");
    }

    @Test
    void foreignUnknownAndMalformedStoreIdsAreIndistinguishable() throws Exception {
        String foreignOrg = notFoundBody(STORE_C.toString());
        String foreignStore = notFoundBody(STORE_B.toString());
        String unknown = notFoundBody(UUID.randomUUID().toString());
        String malformed = notFoundBody("not-a-uuid");

        assertThat(foreignOrg).isEqualTo(foreignStore).isEqualTo(unknown).isEqualTo(malformed)
                .contains("\"code\":\"NOT_FOUND\"");
    }

    @Test
    void deniedAccessIsAudited() throws Exception {
        long before = deniedEvents();

        mvc.perform(get("/api/v1/stores/{id}", STORE_B).with(user("subject-a"))).andExpect(status().isNotFound());

        assertThat(deniedEvents()).isEqualTo(before + 1);
    }

    private long deniedEvents() {
        // audit_event is append-only, so events from other tests remain; compare the delta.
        return jdbc.sql("""
                        SELECT count(*) FROM audit_event
                        WHERE action = 'STORE_ACCESS_DENIED' AND actor_user_id = ? AND resource_id = ?
                        """)
                .params(USER_A, STORE_B.toString()).query(Long.class).single();
    }

    @Test
    void memberSeesOwnStoreWithRoles() throws Exception {
        mvc.perform(get("/api/v1/stores/{id}", STORE_A).with(user("subject-b")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeId").value(STORE_A.toString()))
                .andExpect(jsonPath("$.organizationId").value(ORG.toString()))
                .andExpect(jsonPath("$.roles[0]").value("ANALYST"));
    }

    @Test
    void sameSubjectFromAnotherIssuerIsADifferentUser() throws Exception {
        mvc.perform(get("/api/v1/stores/{id}", STORE_A)
                        .with(jwt().jwt(token -> token.issuer("https://attacker.test").subject("subject-a"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void firstRequestRegistersUserOnce() throws Exception {
        mvc.perform(get("/api/v1/me").with(user("new-subject"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/me").with(user("new-subject")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("new-subject@shop.test"));

        assertThat(jdbc.sql("SELECT count(*) FROM app_user WHERE subject = 'new-subject'")
                .query(Long.class).single()).isEqualTo(1);
        mvc.perform(get("/api/v1/stores").with(user("new-subject")))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void storeDetailsExposeCurrentPermissions() throws Exception {
        mvc.perform(get("/api/v1/stores/{id}", STORE_A).with(user("subject-a")))
                .andExpect(jsonPath("$.permissions.length()").value(3))
                .andExpect(jsonPath("$.permissions[1]").value("STORE_MANAGE"));
        mvc.perform(get("/api/v1/stores/{id}", STORE_A).with(user("subject-b")))
                .andExpect(jsonPath("$.permissions.length()").value(1))
                .andExpect(jsonPath("$.permissions[0]").value("STORE_READ"));
    }

    @Test
    void manageEndpointRequiresStoreManagePermission() throws Exception {
        mvc.perform(post("/api/v1/stores/{id}/test-manage-probe", STORE_A).with(user("subject-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeId").value(STORE_A.toString()));
        mvc.perform(post("/api/v1/stores/{id}/test-manage-probe", STORE_A).with(user("subject-b")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void missingPermissionIsAuditedAndNonMembersStillGet404() throws Exception {
        long before = permissionDeniedEvents();

        mvc.perform(post("/api/v1/stores/{id}/test-manage-probe", STORE_A).with(user("subject-b")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/stores/{id}/test-manage-probe", STORE_B).with(user("subject-a")))
                .andExpect(status().isNotFound());

        assertThat(permissionDeniedEvents()).isEqualTo(before + 1);
    }

    @Test
    void revokedMembershipTakesEffectOnTheNextRequest() throws Exception {
        mvc.perform(get("/api/v1/stores/{id}", STORE_A).with(user("subject-b"))).andExpect(status().isOk());

        jdbc.sql("DELETE FROM store_membership WHERE user_id = ? AND store_id = ?").params(USER_B, STORE_A).update();

        mvc.perform(get("/api/v1/stores/{id}", STORE_A).with(user("subject-b"))).andExpect(status().isNotFound());
    }

    private long permissionDeniedEvents() {
        return jdbc.sql("""
                        SELECT count(*) FROM audit_event
                        WHERE action = 'STORE_PERMISSION_DENIED' AND actor_user_id = ? AND store_id = ?
                          AND details->>'permission' = 'STORE_MANAGE'
                        """)
                .params(USER_B, STORE_A).query(Long.class).single();
    }

    /** 404 body without the per-request requestId, which differs on every call by design. */
    private String notFoundBody(String storeId) throws Exception {
        return mvc.perform(get("/api/v1/stores/" + storeId).with(user("subject-a")))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString()
                .replaceAll("\"requestId\":\"[^\"]+\"", "\"requestId\":\"*\"");
    }
}
