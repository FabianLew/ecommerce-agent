package com.ecommerceagent.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.ecommerceagent.TestcontainersConfiguration;
import com.ecommerceagent.automation.guardian.GuardianSignals;
import com.ecommerceagent.decisions.OpportunityStatus;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Approval and rejection commands (B-37, ADR-0020 point 2) with execution switched on: version and key checks,
 * preconditions, the stored decision and the signal, permissions and store isolation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "operator.policies.inventory-protection.mode=EXECUTE_ON_APPROVAL")
class DecisionApprovalApiTest extends DecisionScenario {

    @MockitoBean
    GuardianSignals signals;

    String etag(UUID proposalId) throws Exception {
        return mvc.perform(get("/api/v1/stores/{id}/decisions/{p}", store, proposalId).with(user("owner")))
                .andReturn().getResponse().getHeader("ETag");
    }

    ResultActions approve(UUID proposalId, String etag, String key, int revision) throws Exception {
        var request = post("/api/v1/stores/{id}/decisions/{p}/approvals", store, proposalId).with(user("owner"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":" + revision + "}");
        if (etag != null) {
            request.header("If-Match", etag);
        }
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request);
    }

    @Test
    void theDetailOffersApprovalAndTheApprovalIsStoredOnceAndSignalled() throws Exception {
        UUID proposalId = awaitingDecision();
        mvc.perform(get("/api/v1/stores/{id}/decisions/{p}", store, proposalId).with(user("owner")))
                .andExpect(jsonPath("$.allowedActions[0]").value("APPROVE"))
                .andExpect(jsonPath("$.allowedActions[1]").value("REJECT"))
                .andExpect(jsonPath("$.blockedReason").doesNotExist())
                .andExpect(jsonPath("$.policyEvaluation.mode").value("EXECUTE_ON_APPROVAL"));
        String etag = etag(proposalId);

        String approvalId = com.jayway.jsonpath.JsonPath.read(approve(proposalId, etag, "k-1", 1)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.decision").value("APPROVED"))
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.statusUrl").value("/api/v1/stores/" + store + "/decisions/" + proposalId))
                .andReturn().getResponse().getContentAsString(), "$.approvalId");

        // A double click with the same key gets the same answer and no second decision. The signal is sent again,
        // which recovers a lost one; the workflow ignores a repeated approval id.
        approve(proposalId, etag, "k-1", 1).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.approvalId").value(approvalId))
                .andExpect(jsonPath("$.replayed").value(true));
        verify(signals, times(2)).approvalDecided(eq(store), any(), eq(UUID.fromString(approvalId)));
        assertThat(jdbc.sql("SELECT count(*) FROM decision_approval WHERE proposal_id = ?").param(proposalId)
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT action_hash IS NOT NULL AND policy_evaluation_id IS NOT NULL "
                + "FROM decision_approval WHERE proposal_id = ?").param(proposalId).query(Boolean.class).single())
                .isTrue();
        mvc.perform(get("/api/v1/stores/{id}/decisions/{p}", store, proposalId).with(user("owner")))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.allowedActions.length()").value(0));
        mvc.perform(get("/api/v1/stores/{id}/activities", store).with(user("owner")))
                .andExpect(jsonPath("$.items[*].action").value(org.hamcrest.Matchers.hasItem("DECISION_APPROVED")));
        // Another key for the decided proposal is a conflict, not a second approval.
        approve(proposalId, etag(proposalId), "k-2", 1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_DECIDED"));
    }

    @Test
    void versionKeyAndRevisionAreRequired() throws Exception {
        UUID proposalId = awaitingDecision();
        String etag = etag(proposalId);

        approve(proposalId, null, "k-1", 1).andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("IF_MATCH_REQUIRED"));
        approve(proposalId, "\"stale\"", "k-1", 1).andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("VERSION_CHANGED"));
        approve(proposalId, etag, null, 1).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        approve(proposalId, etag, "k-1", 2).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVISION_MISMATCH"));
        // A key used for one request cannot carry another one.
        approve(proposalId, etag, "k-1", 1).andExpect(status().isAccepted());
        mvc.perform(post("/api/v1/stores/{id}/decisions/{p}/rejections", store, proposalId).with(user("owner"))
                        .header("Idempotency-Key", "k-1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void anOldRevisionOrAnExpiredProposalCannotBeApproved() throws Exception {
        UUID first = awaitingDecision();
        String etag = etag(first);
        analyse(episode, OpportunityStatus.AWAITING_DECISION);

        approve(first, etag(first), "k-old", 1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROPOSAL_NOT_OPEN"));
        assertThat(etag).isNotNull();
        verify(signals, never()).approvalDecided(any(), any(), any());
    }

    @Test
    void aRejectionClosesTheProposalWithItsReason() throws Exception {
        UUID proposalId = awaitingDecision();

        mvc.perform(post("/api/v1/stores/{id}/decisions/{p}/rejections", store, proposalId).with(user("owner"))
                        .header("If-Match", etag(proposalId)).header("Idempotency-Key", "r-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":1,\"reason\":\"Delivery arrives tomorrow\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.decision").value("REJECTED"));
        mvc.perform(get("/api/v1/stores/{id}/decisions/{p}", store, proposalId).with(user("owner")))
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void readersAndOtherStoresCannotDecide() throws Exception {
        UUID proposalId = awaitingDecision();
        jdbc.sql("INSERT INTO store_membership (organization_id, store_id, user_id, role) VALUES (?, ?, ?, 'ANALYST')")
                .params(ORG, store, OUTSIDER).update();
        String etag = etag(proposalId);

        mvc.perform(post("/api/v1/stores/{id}/decisions/{p}/approvals", store, proposalId).with(user("outsider"))
                        .header("If-Match", etag).header("Idempotency-Key", "x-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":1}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/stores/{id}/decisions/{p}", store, proposalId).with(user("outsider")))
                .andExpect(jsonPath("$.allowedActions.length()").value(0))
                .andExpect(jsonPath("$.blockedReason").value("PERMISSION_MISSING"));
        mvc.perform(post("/api/v1/stores/{id}/decisions/{p}/approvals", otherStore, proposalId).with(user("outsider"))
                        .header("If-Match", etag).header("Idempotency-Key", "x-2")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":1}"))
                .andExpect(status().isNotFound());
    }
}
