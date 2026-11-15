package com.ecommerceagent.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.ecommerceagent.FixtureDb;
import com.ecommerceagent.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * ADR-0027 section 15 (E-54.6): the platform AI endpoints answer only platform operators. The decisions themselves
 * are tested in {@code AiReconciliationTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class PlatformAiApiTest {

    static final String ISSUER = "https://issuer.test/realms/operator";

    @Autowired
    MockMvc mvc;

    @Autowired
    FixtureDb fixtures;

    @Test
    void onlyAPlatformOperatorReachesThePlatformAiEndpoints() throws Exception {
        String member = "member-" + UUID.randomUUID();
        String operator = "ops-" + UUID.randomUUID();
        String body = "{\"outcome\":\"NOT_CHARGED\",\"reason\":\"No request in the console\"}";

        mvc.perform(get("/api/v1/platform/ai/reservations/uncertain")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/platform/ai/reservations/uncertain").with(user(member)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/v1/platform/ai/budget-alerts").with(user(member))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/platform/ai/reservations/{id}/reconciliation", UUID.randomUUID())
                        .with(user(member)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/platform/ai/reservations/{id}/correction", UUID.randomUUID())
                        .with(user(member)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/platform/ai/reservations/uncertain").with(user(operator)))
                .andExpect(status().isForbidden());
        fixtures.sql("""
                        INSERT INTO platform_operator (user_id, granted_by, reason)
                        SELECT id, 'ops:test', 'test' FROM app_user WHERE issuer = ? AND subject = ?
                        """).params(ISSUER, operator).update();

        mvc.perform(get("/api/v1/platform/ai/reservations/uncertain").with(user(operator)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.hasMore").value(false));
        mvc.perform(get("/api/v1/platform/ai/budget-alerts").with(user(operator)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray());
        mvc.perform(get("/api/v1/platform/ai/budget-alerts").param("limit", "0").with(user(operator)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/platform/ai/reservations/uncertain").param("limit", "0").with(user(operator)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_LIMIT"));
        mvc.perform(post("/api/v1/platform/ai/reservations/{id}/reconciliation", UUID.randomUUID())
                        .with(user(operator)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/platform/ai/reservations/{id}/reconciliation", UUID.randomUUID())
                        .with(user(operator)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"FREE\",\"reason\":\"x\"}"))
                .andExpect(status().is(422)).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post("/api/v1/platform/ai/reservations/{id}/correction", UUID.randomUUID())
                        .with(user(operator)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"CONSERVATIVE\",\"reason\":\"x\"}"))
                .andExpect(status().is(422));

        fixtures.sql("""
                        UPDATE platform_operator SET revoked_at = now()
                        WHERE user_id = (SELECT id FROM app_user WHERE issuer = ? AND subject = ?)
                        """).params(ISSUER, operator).update();
        mvc.perform(get("/api/v1/platform/ai/reservations/uncertain").with(user(operator)))
                .andExpect(status().isForbidden());
    }

    @Test
    void onlyAPlatformOperatorStopsAndResumesAi() throws Exception {
        String member = "member-" + UUID.randomUUID();
        String operator = "ops-" + UUID.randomUUID();
        String automation = "api-stop-" + UUID.randomUUID().toString().substring(0, 8);
        fixtures.registerAutomations(automation);
        String stop = "{\"automationType\":\"" + automation + "\",\"stopped\":true,\"reason\":\"Regression\"}";
        String resume = "{\"automationType\":\"" + automation + "\",\"stopped\":false,\"reason\":\"Fixed\"}";

        mvc.perform(post("/api/v1/platform/ai/stops").with(user(member)).contentType(MediaType.APPLICATION_JSON)
                .content(stop)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/platform/ai/stops").with(user(member))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/platform/ai/stops/history").with(user(member))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/platform/ai/stops").with(user(operator))).andExpect(status().isForbidden());
        fixtures.sql("""
                        INSERT INTO platform_operator (user_id, granted_by, reason)
                        SELECT id, 'ops:test', 'test' FROM app_user WHERE issuer = ? AND subject = ?
                        """).params(ISSUER, operator).update();

        mvc.perform(post("/api/v1/platform/ai/stops").with(user(operator)).contentType(MediaType.APPLICATION_JSON)
                .content(stop)).andExpect(status().isOk()).andExpect(jsonPath("$.changed").value(true));
        mvc.perform(post("/api/v1/platform/ai/stops").with(user(operator)).contentType(MediaType.APPLICATION_JSON)
                .content(stop)).andExpect(status().isOk()).andExpect(jsonPath("$.changed").value(false));
        mvc.perform(get("/api/v1/platform/ai/stops").with(user(operator))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.automationType == '" + automation + "')].level").value("AUTOMATION"));
        mvc.perform(post("/api/v1/platform/ai/stops").with(user(operator)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"stopped\":true,\"reason\":\"everything\"}")).andExpect(status().is(422));
        mvc.perform(post("/api/v1/platform/ai/stops").with(user(operator)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"storeId\":\"" + UUID.randomUUID() + "\",\"stopped\":true,\"reason\":\"x\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/platform/ai/stops").with(user(operator)).contentType(MediaType.APPLICATION_JSON)
                .content(resume)).andExpect(status().isOk()).andExpect(jsonPath("$.changed").value(true));
        mvc.perform(get("/api/v1/platform/ai/stops/history").param("limit", "5").with(user(operator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].automationType").value(automation))
                .andExpect(jsonPath("$.items[0].stopped").value(false))
                .andExpect(jsonPath("$.items[1].stopped").value(true));
    }

    static RequestPostProcessor user(String subject) {
        return jwt().jwt(token -> token.issuer(ISSUER).subject(subject).claim("email", subject + "@shop.test"));
    }
}
