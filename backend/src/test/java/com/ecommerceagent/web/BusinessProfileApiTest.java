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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.ecommerceagent.FixtureDb;
import com.ecommerceagent.TestcontainersConfiguration;
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
 * Store Business Profile and onboarding (B-33, ADR-0018). Runtime safety: only STORE_MANAGE edits and a person
 * approves, an approved version never changes, stale revisions conflict, stores are isolated, and the browser cannot
 * mark an onboarding step complete.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class BusinessProfileApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    FixtureDb jdbc;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE store_membership, app_user, store, organization, audit_event CASCADE").update();
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
    }

    private ResultActions draft(String subject, UUID store, String fields, int revision) throws Exception {
        return mvc.perform(put("/api/v1/stores/{storeId}/business-profile/draft", store).with(user(subject))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{" + fields + (fields.isEmpty() ? "" : ",") + "\"expectedRevision\":" + revision + "}"));
    }

    private ResultActions approve(String subject, UUID store, int revision) throws Exception {
        return mvc.perform(post("/api/v1/stores/{storeId}/business-profile/draft/approval", store).with(user(subject))
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":" + revision + "}"));
    }

    @Test
    void aDraftIsEditedWithItsRevisionAndApprovedByAnOwner() throws Exception {
        draft("owner", STORE_A, "\"planningHorizonDays\":30", 0).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.missingFields[0]").value("GOAL"));
        // The goal is never defaulted for the owner: approval needs it.
        approve("owner", STORE_A, 1).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("goal"));
        draft("owner", STORE_A, "\"goal\":\"BALANCED\"", 0).andExpect(status().isConflict());
        draft("owner", STORE_A, "\"goal\":\"BALANCED\",\"planningHorizonDays\":30,\"riskPreference\":\"LOW\","
                + "\"priorities\":[\"INVENTORY_CONTINUITY\"],\"plannedAdSpendMinor\":3000000,"
                + "\"plannedAdSpendPeriod\":\"2026-12\"", 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.plannedAdSpend.currency").value("PLN"));
        approve("owner", STORE_A, 1).andExpect(status().isConflict());
        approve("owner", STORE_A, 2).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.missingFields").value(org.hamcrest.Matchers.contains("ECONOMIC_MODEL",
                        "MAX_ACCEPTABLE_ACQUISITION_LOSS")));

        // A second version supersedes the first, which is never changed.
        String before = jdbc.sql("SELECT goal || risk_preference || revision FROM store_business_profile")
                .query(String.class).single();
        draft("owner", STORE_A, "\"goal\":\"PROFITABILITY\",\"planningHorizonDays\":60,\"riskPreference\":\"MEDIUM\"",
                0).andExpect(jsonPath("$.version").value(2));
        mvc.perform(get("/api/v1/stores/{storeId}/business-profile", STORE_A).with(user("analyst")))
                .andExpect(jsonPath("$.approved.version").value(1))
                .andExpect(jsonPath("$.draft.version").value(2))
                .andExpect(jsonPath("$.canEdit").value(false));
        assertThat(jdbc.sql("SELECT goal || risk_preference || revision FROM store_business_profile WHERE version = 1")
                .query(String.class).single()).isEqualTo(before);
        approve("owner", STORE_A, 1).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        mvc.perform(get("/api/v1/stores/{storeId}/business-profile/versions", STORE_A).with(user("owner")))
                .andExpect(jsonPath("$.items[0].status").value("APPROVED"))
                .andExpect(jsonPath("$.items[1].status").value("SUPERSEDED"));
        assertThat(jdbc.sql("SELECT action FROM audit_event WHERE action = 'BUSINESS_PROFILE_APPROVED'")
                .query(String.class).list()).hasSize(2);
    }

    @Test
    void onlyStoreManageEditsAndStoresAreIsolated() throws Exception {
        draft("analyst", STORE_A, "\"goal\":\"GROWTH\"", 0).andExpect(status().isForbidden());
        draft("owner", STORE_A, "\"goal\":\"GROWTH\",\"planningHorizonDays\":30,\"riskPreference\":\"HIGH\"", 0);
        approve("analyst", STORE_A, 1).andExpect(status().isForbidden());
        approve("owner-b", STORE_B, 1).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/stores/{storeId}/business-profile", STORE_B).with(user("owner-b")))
                .andExpect(jsonPath("$.draft").doesNotExist());
        mvc.perform(get("/api/v1/stores/{storeId}/business-profile", STORE_A).with(user("owner-b")))
                .andExpect(status().isNotFound());
        draft("owner", STORE_A, "\"planningHorizonDays\":3", 1).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].code").value("OUT_OF_RANGE"));
        draft("owner", STORE_A, "\"plannedAdSpendMinor\":100,\"plannedAdSpendPeriod\":\"2026-13\"", 1)
                .andExpect(jsonPath("$.fieldErrors[0].code").value("PERIOD_FORMAT"));
    }

    @Test
    void onboardingFollowsTheRealStateAndAcknowledgementsCannotCompleteAStep() throws Exception {
        mvc.perform(get("/api/v1/stores/{storeId}/onboarding", STORE_A).with(user("owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextStep").value("CONNECT_STORE"))
                .andExpect(jsonPath("$.steps[0].complete").value(false))
                .andExpect(jsonPath("$.steps[0].blockers[0]").value("WOOCOMMERCE_NOT_CONNECTED"))
                .andExpect(jsonPath("$.domains[1].readiness").value("NOT_STARTED"))
                .andExpect(jsonPath("$.version").value(0));

        mvc.perform(patch("/api/v1/stores/{storeId}/onboarding", STORE_A).with(user("owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentStep\":\"BUSINESS_DATA\",\"acknowledgements\":[\"SKIP_META\"],"
                                + "\"expectedVersion\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStep").value("BUSINESS_DATA"))
                .andExpect(jsonPath("$.nextStep").value("CONNECT_STORE"))
                .andExpect(jsonPath("$.steps[1].complete").value(true))
                .andExpect(jsonPath("$.domains[1].readiness").value("SKIPPED"))
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(patch("/api/v1/stores/{storeId}/onboarding", STORE_A).with(user("owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentStep\":\"CONNECT_STORE\",\"expectedVersion\":0}"))
                .andExpect(status().isConflict());
        mvc.perform(patch("/api/v1/stores/{storeId}/onboarding", STORE_A).with(user("analyst"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentStep\":\"CONNECT_STORE\",\"expectedVersion\":1}"))
                .andExpect(status().isForbidden());

        draft("owner", STORE_A, "\"goal\":\"BALANCED\",\"planningHorizonDays\":30,\"riskPreference\":\"LOW\"", 0);
        approve("owner", STORE_A, 1);
        mvc.perform(get("/api/v1/stores/{storeId}/onboarding", STORE_A).with(user("owner")))
                .andExpect(jsonPath("$.steps[2].complete").value(true))
                .andExpect(jsonPath("$.domains[3].readiness").value("READY"));
        mvc.perform(get("/api/v1/stores/{storeId}/onboarding", STORE_B).with(user("owner-b")))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.domains[3].readiness").value("NOT_STARTED"));
    }

    /** ADR-0025: monitoring starts only after the earlier steps, once, with the scope in force now. */
    @Test
    void monitoringStartsOnceAfterTheEarlierSteps() throws Exception {
        draft("owner", STORE_A, "\"goal\":\"BALANCED\",\"planningHorizonDays\":30,\"riskPreference\":\"LOW\"", 0);
        approve("owner", STORE_A, 1);
        mvc.perform(get("/api/v1/stores/{storeId}/onboarding", STORE_A).with(user("owner")))
                .andExpect(jsonPath("$.steps[3].blockers[0]").value("EARLIER_STEPS_INCOMPLETE"))
                .andExpect(jsonPath("$.monitoring.ads").value("NONE"));
        startMonitoring("owner", STORE_A, 0).andExpect(status().isUnprocessableContent());

        UUID connection = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO integration_connection (id, organization_id, store_id, provider, store_url, status,
                                                            created_at, updated_at, webhook_endpoint_key)
                        VALUES (?, ?, ?, 'WOOCOMMERCE', 'https://shop.example', 'CONNECTED', now(), now(), ?)
                        """).params(connection, ORG, STORE_A, UUID.randomUUID().toString().replace("-", "")).update();
        jdbc.sql("""
                        INSERT INTO sync_run (id, organization_id, store_id, connection_id, kind, status, window_from,
                                              window_until, started_at, finished_at, workflow_started_at)
                        VALUES (?, ?, ?, ?, 'INITIAL_IMPORT', 'SUCCEEDED', now() - interval '14 days', now(), now(),
                                now(), now())
                        """).params(UUID.randomUUID(), ORG, STORE_A, connection).update();
        mvc.perform(patch("/api/v1/stores/{storeId}/onboarding", STORE_A).with(user("owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentStep\":\"START_MONITORING\",\"acknowledgements\":[\"SKIP_META\"],"
                                + "\"expectedVersion\":0}"))
                .andExpect(jsonPath("$.steps[3].blockers[0]").value("MONITORING_NOT_STARTED"));

        startMonitoring("analyst", STORE_A, 1).andExpect(status().isForbidden());
        startMonitoring("owner", STORE_A, 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[3].complete").value(true))
                .andExpect(jsonPath("$.monitoring.startedBy").value(OWNER.toString()))
                .andExpect(jsonPath("$.monitoring.ads").value("NONE"))
                .andExpect(jsonPath("$.version").value(2));
        startMonitoring("owner", STORE_A, 1).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        org.assertj.core.api.Assertions.assertThat(jdbc.sql(
                        "SELECT count(*) FROM audit_event WHERE action = 'MONITORING_STARTED' AND store_id = ?")
                .param(STORE_A).query(Long.class).single()).isEqualTo(1);
        mvc.perform(get("/api/v1/stores/{storeId}/onboarding", STORE_B).with(user("owner-b")))
                .andExpect(jsonPath("$.steps[3].complete").value(false));
    }

    private org.springframework.test.web.servlet.ResultActions startMonitoring(String subject, UUID store,
            int version) throws Exception {
        return mvc.perform(post("/api/v1/stores/{storeId}/onboarding/monitoring-start", store).with(user(subject))
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":" + version + "}"));
    }
}
