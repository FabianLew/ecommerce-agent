package com.ecommerceagent.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.ecommerceagent.TestcontainersConfiguration;
import com.ecommerceagent.automation.execution.ActionLevel;
import com.ecommerceagent.automation.execution.ActionPermissions;
import com.ecommerceagent.automation.execution.ExecutionAuthority;
import com.ecommerceagent.automation.guardian.ApprovalException;
import com.ecommerceagent.automation.guardian.DecisionApprovals;
import com.ecommerceagent.automation.guardian.GuardianSignals;
import com.ecommerceagent.identity.TrustedStoreScope;
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
 * The per-store action permission (ADR-0025 points 2 to 4): the default, who may raise and lower it, the readiness
 * checks, the version check and audit, the effective level with its caps, and the gate on approval and
 * authorization. The deployment ceiling is {@code EXECUTE_ON_APPROVAL} here; {@code DecisionApiTest} covers the
 * {@code SHADOW_MODE} cap.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "operator.policies.inventory-protection.mode=EXECUTE_ON_APPROVAL")
class ActionPermissionApiTest extends DecisionScenario {

    static final UUID OPERATOR = UUID.fromString("b8300000-0000-4000-8000-0000000000c2");
    static final UUID ANALYST = UUID.fromString("b8300000-0000-4000-8000-0000000000d2");
    static final String PATH = "/api/v1/stores/{id}/action-permissions/{type}";

    @MockitoBean
    GuardianSignals signals;
    @Autowired
    DecisionApprovals approvals;
    @Autowired
    ExecutionAuthority authority;
    @Autowired
    ActionPermissions permissions;

    TrustedStoreScope owner;

    @BeforeEach
    void people() {
        jdbc.sql("INSERT INTO app_user (id, issuer, subject, email) VALUES (?, ?, 'operator', 'op@shop.test'), "
                + "(?, ?, 'analyst', 'an@shop.test')").params(OPERATOR, ISSUER, ANALYST, ISSUER).update();
        jdbc.sql("""
                INSERT INTO store_membership (organization_id, store_id, user_id, role) VALUES
                  (?, ?, ?, 'DECISION_OPERATOR'), (?, ?, ?, 'ANALYST')
                """).params(ORG, store, OPERATOR, ORG, store, ANALYST).update();
        // Every test starts from the default: no row, so PROPOSE_ONLY.
        jdbc.sql("DELETE FROM store_action_permission").update();
        owner = storeAccess.scopeFor(OWNER, store).orElseThrow();
    }

    ResultActions change(String subject, UUID storeId, String etag, String level, String reason) throws Exception {
        var request = put(PATH, storeId, "PAUSE_AD").with(user(subject)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"level\":\"" + level + "\"" + (reason == null ? "" : ",\"reason\":\"" + reason + "\"")
                        + "}");
        if (etag != null) {
            request.header("If-Match", etag);
        }
        return mvc.perform(request);
    }

    @Test
    void aNewStoreProposesOnlyAndOnlyAManagerRaisesFromAReadyStore() throws Exception {
        mvc.perform(get(PATH, store, "PAUSE_AD").with(user("analyst")))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"permission-0\""))
                .andExpect(jsonPath("$.level").value("PROPOSE_ONLY"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.effectiveLevel").value("PROPOSE_ONLY"))
                .andExpect(jsonPath("$.caps[0].source").value("STORE"))
                .andExpect(jsonPath("$.caps[0].code").value("ACTION_NOT_PERMITTED"))
                .andExpect(jsonPath("$.canRaise").value(false))
                .andExpect(jsonPath("$.canLower").value(false));
        mvc.perform(get(PATH, store, "INCREASE_BUDGET").with(user("owner"))).andExpect(status().isNotFound());

        change("analyst", store, "\"permission-0\"", "APPROVAL_REQUIRED", "L1").andExpect(status().isForbidden());
        // An approver can step back, never forward.
        change("operator", store, "\"permission-0\"", "APPROVAL_REQUIRED", "L1").andExpect(status().isForbidden());
        change("owner", store, null, "APPROVAL_REQUIRED", "L1").andExpect(status().isPreconditionRequired());
        change("owner", store, "\"permission-0\"", "APPROVAL_REQUIRED", null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].code").value("REASON_REQUIRED"));
        change("owner", store, "\"permission-3\"", "APPROVAL_REQUIRED", "L1")
                .andExpect(status().isPreconditionFailed());

        jdbc.sql("UPDATE onboarding_progress SET monitoring_started_at = NULL WHERE store_id = ?").param(store)
                .update();
        change("owner", store, "\"permission-0\"", "APPROVAL_REQUIRED", "L1").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MONITORING_NOT_STARTED"));
        jdbc.sql("UPDATE onboarding_progress SET monitoring_started_at = now() WHERE store_id = ?").param(store)
                .update();
        jdbc.sql("UPDATE meta_connection SET can_write = false WHERE id = ?").param(metaConnection).update();
        change("owner", store, "\"permission-0\"", "APPROVAL_REQUIRED", "L1").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("META_WRITE_PERMISSION_MISSING"));
        jdbc.sql("UPDATE meta_connection SET can_write = true WHERE id = ?").param(metaConnection).update();

        change("owner", store, "\"permission-0\"", "APPROVAL_REQUIRED", "Shadow period passed")
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"permission-1\""))
                .andExpect(jsonPath("$.level").value("APPROVAL_REQUIRED"))
                .andExpect(jsonPath("$.reason").value("Shadow period passed"))
                .andExpect(jsonPath("$.changedBy").value(OWNER.toString()))
                .andExpect(jsonPath("$.effectiveLevel").value("APPROVAL_REQUIRED"))
                .andExpect(jsonPath("$.caps").isEmpty());
        // A second click on the old version is refused rather than applied twice.
        change("owner", store, "\"permission-0\"", "APPROVAL_REQUIRED", "again")
                .andExpect(status().isPreconditionFailed());

        // The approver lowers it without a reason; the change is audited with both levels.
        change("operator", store, "\"permission-1\"", "OFF", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.level").value("OFF"))
                .andExpect(jsonPath("$.effectiveLevel").value("OFF"))
                .andExpect(jsonPath("$.version").value(2));
        assertThat(jdbc.sql("""
                        SELECT details ->> 'from' || '>' || (details ->> 'to') FROM audit_event
                        WHERE store_id = ? AND action = 'ACTION_PERMISSION_CHANGED' ORDER BY occurred_at
                        """).param(store).query(String.class).list())
                .containsExactly("PROPOSE_ONLY>APPROVAL_REQUIRED", "APPROVAL_REQUIRED>OFF");

        // The other store is not reachable, and its permission is untouched.
        change("owner", otherStore, "\"permission-0\"", "OFF", null).andExpect(status().isNotFound());
        assertThat(jdbc.sql("SELECT count(*) FROM store_action_permission WHERE store_id = ?").param(otherStore)
                .query(Long.class).single()).isZero();
    }

    /** The effective level is the lowest of the store's level, the Meta connection, the stop and the start. */
    @Test
    void theEffectiveLevelIsTheMinimumOfItsSources() {
        permissions.change(owner, ActionPermissions.PAUSE_AD, ActionLevel.APPROVAL_REQUIRED, "L1", 0);
        assertThat(permissions.effective(owner, ActionPermissions.PAUSE_AD).level())
                .isEqualTo(ActionLevel.APPROVAL_REQUIRED);

        jdbc.sql("UPDATE meta_connection SET can_write = false WHERE id = ?").param(metaConnection).update();
        assertThat(permissions.effective(owner, ActionPermissions.PAUSE_AD).refusal())
                .contains(ActionPermissions.META_WRITE_PERMISSION_MISSING);
        jdbc.sql("UPDATE meta_connection SET status = 'ERROR' WHERE id = ?").param(metaConnection).update();
        ActionPermissions.Effective disconnected = permissions.effective(owner, ActionPermissions.PAUSE_AD);
        assertThat(disconnected.level()).isEqualTo(ActionLevel.OFF);
        assertThat(disconnected.refusal()).contains(ActionPermissions.META_NOT_CONNECTED);
        jdbc.sql("UPDATE meta_connection SET status = 'CONNECTED', can_write = true WHERE id = ?")
                .param(metaConnection).update();

        jdbc.sql("""
                INSERT INTO store_mutation_stop (store_id, organization_id, stopped, reason, version, changed_by,
                                                 changed_at)
                VALUES (?, ?, true, 'Checking', 1, ?, now())
                """).params(store, ORG, OWNER).update();
        ActionPermissions.Effective stopped = permissions.effective(owner, ActionPermissions.PAUSE_AD);
        assertThat(stopped.level()).isEqualTo(ActionLevel.PROPOSE_ONLY);
        assertThat(stopped.refusal()).contains("MUTATIONS_STOPPED");
        // A stop does not take the proposal away from a person: it can still be rejected.
        assertThat(stopped.refusal(ActionPermissions.DECIDABLE)).isEmpty();
        jdbc.sql("DELETE FROM store_mutation_stop WHERE store_id = ?").param(store).update();

        jdbc.sql("UPDATE onboarding_progress SET monitoring_started_at = NULL WHERE store_id = ?").param(store)
                .update();
        assertThat(permissions.effective(owner, ActionPermissions.PAUSE_AD).refusal())
                .contains("MONITORING_NOT_STARTED");
    }

    /** ADR-0025 point 4: approving and authorizing both need APPROVAL_REQUIRED, checked right before the write. */
    @Test
    void aStoreBelowApprovalRequiredCannotApproveAndALoweredLevelStopsTheAuthorization() throws Exception {
        UUID proposalId = awaitingDecision();
        mvc.perform(get("/api/v1/stores/{id}/decisions/{p}", store, proposalId).with(user("owner")))
                .andExpect(jsonPath("$.blockedReason").value("ACTION_NOT_PERMITTED"))
                .andExpect(jsonPath("$.allowedActions", not(hasItem("APPROVE"))))
                .andExpect(jsonPath("$.allowedActions", not(hasItem("REJECT"))));
        assertThatThrownBy(() -> approvals.approve(owner, proposalId, 1, "perm-k1"))
                .isInstanceOfSatisfying(ApprovalException.class,
                        e -> assertThat(e.code()).isEqualTo("ACTION_NOT_PERMITTED"));

        permissions.change(owner, ActionPermissions.PAUSE_AD, ActionLevel.APPROVAL_REQUIRED, "L1", 0);
        mvc.perform(get("/api/v1/stores/{id}/decisions/{p}", store, proposalId).with(user("owner")))
                .andExpect(jsonPath("$.allowedActions", hasItem("APPROVE")));
        UUID approvalId = approvals.approve(owner, proposalId, 1, "perm-k2").approval().id();

        // Lowered between the approval and the authorization: nothing is authorized, so nothing is written.
        permissions.change(owner, ActionPermissions.PAUSE_AD, ActionLevel.PROPOSE_ONLY, null, 1);
        assertThat(authority.authorize(scope, approvalId).refusal()).isEqualTo("ACTION_NOT_PERMITTED");
        assertThat(jdbc.sql("SELECT count(*) FROM authorized_action").query(Long.class).single()).isZero();

        permissions.change(owner, ActionPermissions.PAUSE_AD, ActionLevel.APPROVAL_REQUIRED, "Back to L1", 2);
        assertThat(authority.authorize(scope, approvalId).issued()).isTrue();
    }
}
