package com.ecommerceagent.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.ecommerceagent.TestcontainersConfiguration;
import com.ecommerceagent.automation.guardian.DecisionApprovals;
import com.ecommerceagent.automation.guardian.GuardianSignals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The store's mutation stop (B-40, ADR-0020 point 7): who may stop and resume, the version check, the audit trail,
 * store isolation, and the stop refusing approvals while rejections still work.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "operator.policies.inventory-protection.mode=EXECUTE_ON_APPROVAL")
class MutationStopApiTest extends DecisionScenario {

    static final UUID OPERATOR = UUID.fromString("b8300000-0000-4000-8000-0000000000c1");
    static final UUID ANALYST = UUID.fromString("b8300000-0000-4000-8000-0000000000d1");

    @MockitoBean
    GuardianSignals signals;
    @Autowired
    DecisionApprovals approvals;

    @BeforeEach
    void people() {
        jdbc.sql("INSERT INTO app_user (id, issuer, subject, email) VALUES (?, ?, 'operator', 'op@shop.test'), "
                + "(?, ?, 'analyst', 'an@shop.test')").params(OPERATOR, ISSUER, ANALYST, ISSUER).update();
        jdbc.sql("""
                INSERT INTO store_membership (organization_id, store_id, user_id, role) VALUES
                  (?, ?, ?, 'DECISION_OPERATOR'), (?, ?, ?, 'ANALYST')
                """).params(ORG, store, OPERATOR, ORG, store, ANALYST).update();
    }

    ResultActions change(String subject, UUID storeId, String etag, boolean stopped, String reason)
            throws Exception {
        var request = post("/api/v1/stores/{id}/mutation-stop", storeId).with(user(subject))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"stopped\":" + stopped + (reason == null ? "" : ",\"reason\":\"" + reason + "\"") + "}");
        if (etag != null) {
            request.header("If-Match", etag);
        }
        return mvc.perform(request);
    }

    @Test
    void anOperatorStopsAndOnlyAManagerResumesFromTheVersionSeen() throws Exception {
        mvc.perform(get("/api/v1/stores/{id}/mutation-stop", store).with(user("analyst")))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"stop-0\""))
                .andExpect(jsonPath("$.stopped").value(false))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.globallyStopped").value(false))
                .andExpect(jsonPath("$.canStop").value(false))
                .andExpect(jsonPath("$.canResume").value(false));
        // ADR-0022: the request's row-level security binding does not outlive the request.
        org.assertj.core.api.Assertions.assertThat(com.ecommerceagent.identity.StoreRowGuard.current()).isEmpty();

        change("analyst", store, "\"stop-0\"", true, "Promo").andExpect(status().isForbidden());
        change("operator", store, null, true, "Promo").andExpect(status().isPreconditionRequired());
        change("operator", store, "\"stop-0\"", true, null).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].code").value("REASON_REQUIRED"));
        change("operator", store, "\"stop-0\"", true, "Black Friday freeze").andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"stop-1\""))
                .andExpect(jsonPath("$.stopped").value(true))
                .andExpect(jsonPath("$.reason").value("Black Friday freeze"))
                .andExpect(jsonPath("$.changedBy").value(OPERATOR.toString()));
        // A second click on the old version is refused rather than applied twice.
        change("operator", store, "\"stop-0\"", true, "again").andExpect(status().isPreconditionFailed());

        // The operator may stop, not resume; the owner resumes.
        change("operator", store, "\"stop-1\"", false, null).andExpect(status().isForbidden());
        change("owner", store, "\"stop-1\"", false, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.stopped").value(false))
                .andExpect(jsonPath("$.version").value(2));

        assertThat(jdbc.sql("SELECT action FROM audit_event WHERE store_id = ? AND resource_type = "
                + "'store_mutation_stop' ORDER BY occurred_at").param(store).query(String.class).list())
                .containsExactly("MUTATIONS_STOPPED", "MUTATIONS_RESUMED");
        // The other store is untouched and not reachable: it is not even visible to this person.
        assertThat(jdbc.sql("SELECT count(*) FROM store_mutation_stop WHERE store_id = ?").param(otherStore)
                .query(Long.class).single()).isZero();
        change("owner", otherStore, "\"stop-0\"", true, "x").andExpect(status().isNotFound());
    }

    @Test
    void aStopRefusesApprovalsButNotRejections() throws Exception {
        UUID proposalId = awaitingDecision();
        change("owner", store, "\"stop-0\"", true, "Checking the account").andExpect(status().isOk());

        mvc.perform(get("/api/v1/stores/{id}/decisions/{p}", store, proposalId).with(user("owner")))
                .andExpect(jsonPath("$.blockedReason").value("MUTATIONS_STOPPED"))
                .andExpect(jsonPath("$.allowedActions", not(hasItem("APPROVE"))))
                .andExpect(jsonPath("$.allowedActions", hasItem("REJECT")));
        var owner = storeAccess.scopeFor(OWNER, store).orElseThrow();
        try {
            approvals.approve(owner, proposalId, 1, "stop-k1");
            throw new AssertionError("approved while stopped");
        } catch (com.ecommerceagent.automation.guardian.ApprovalException e) {
            assertThat(e.code()).isEqualTo("MUTATIONS_STOPPED");
        }
        assertThat(approvals.reject(owner, proposalId, 1, "Not now", "stop-k2").approval().decision())
                .isEqualTo(DecisionApprovals.Decision.REJECTED);
    }
}
