package com.ecommerceagent.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import com.ecommerceagent.TestcontainersConfiguration;
import com.ecommerceagent.automation.execution.ActionExecution;
import com.ecommerceagent.automation.execution.AuthorizedAction;
import com.ecommerceagent.automation.execution.ExecutionAuthority;
import com.ecommerceagent.automation.execution.ExecutionLedger;
import com.ecommerceagent.automation.execution.ExecutionStatus;
import com.ecommerceagent.automation.guardian.DecisionApprovals;
import com.ecommerceagent.automation.guardian.GuardianSignals;
import com.ecommerceagent.commerce.AdData;
import com.ecommerceagent.commerce.AdSource;
import com.ecommerceagent.commerce.AdStatus;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.metrics.CanonicalJson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * AuthorizedAction and the ledger (B-38, ADR-0020 points 3–5): an authorization only after a fresh policy check,
 * refusals that keep the write from happening, one ledger row per key, leases, limit slots and the cooldown.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "operator.policies.inventory-protection.mode=EXECUTE_ON_APPROVAL")
class ExecutionAuthorityTest extends DecisionScenario {

    @MockitoBean
    GuardianSignals signals;
    @Autowired
    DecisionApprovals approvals;
    @Autowired
    ExecutionAuthority authority;
    @Autowired
    ExecutionLedger ledger;

    UUID connection;
    TrustedStoreScope owner;

    @BeforeEach
    void metaConnected() {
        connection = metaConnection;
        owner = storeAccess.scopeFor(OWNER, store).orElseThrow();
    }

    UUID approved() {
        UUID proposalId = awaitingDecision();
        return approvals.approve(owner, proposalId, 1, "k-" + proposalId).approval().id();
    }

    @Test
    void anApprovalIsAuthorizedAfterAFreshPolicyCheck() {
        UUID approvalId = approved();

        ExecutionAuthority.Authorization authorization = authority.authorize(scope, approvalId);

        assertThat(authorization.refusal()).isNull();
        AuthorizedAction action = authorization.action();
        assertThat(action.targetId()).isEqualTo(dedicatedAd);
        assertThat(action.targetExternalId()).isEqualTo("ad-9001");
        assertThat(action.creativeFingerprint()).isEqualTo("fp-creative-44");
        assertThat(action.idempotencyKey()).isEqualTo(store + "/" + connection + "/" + action.proposalId()
                + "/1/PAUSE_AD/ad-9001");
        assertThat(action.expiresAt()).isEqualTo(action.issuedAt().plusSeconds(60));
        assertThat(authority.find(scope, action.id())).contains(action);
        // The recheck is a recorded evaluation of its own, on a new snapshot.
        assertThat(policies.ofProposal(scope, action.proposalId())).hasSize(2);
        assertThat(jdbc.sql("SELECT parameters->>'contextSnapshotId' FROM policy_evaluation WHERE id = ?")
                .param(action.policyEvaluationId()).query(String.class).single()).isNotNull();
    }

    @Test
    void refusalsStopTheActionBeforeAnyWrite() {
        UUID approvalId = approved();
        // Someone paused the ad in Ads Manager meanwhile: the recheck denies, so nothing is authorized.
        ads.upsertAd(scope, new AdData("ad-9001", "act-01", "Serum dedicated", AdStatus.PAUSED, "PAUSED",
                "fp-creative-44", clock.instant(), AdSource.FIXTURE));
        assertThat(authority.authorize(scope, approvalId).refusal()).isEqualTo("POLICY_DENY:ALREADY_PAUSED");

        ads.upsertAd(scope, new AdData("ad-9001", "act-01", "Serum dedicated", AdStatus.ACTIVE, "ACTIVE",
                "fp-creative-44", clock.instant(), AdSource.FIXTURE));
        jdbc.sql("UPDATE meta_connection SET can_write = false WHERE id = ?").param(connection).update();
        assertThat(authority.authorize(scope, approvalId).refusal()).isEqualTo("META_WRITE_PERMISSION_MISSING");

        jdbc.sql("UPDATE meta_connection SET can_write = true WHERE id = ?").param(connection).update();
        jdbc.sql("UPDATE store_membership SET role = 'ANALYST' WHERE user_id = ? AND store_id = ?")
                .params(OWNER, store).update();
        assertThat(authority.authorize(scope, approvalId).refusal()).isEqualTo("APPROVER_ACCESS_REVOKED");
        assertThat(authority.authorize(scope, UUID.randomUUID()).refusal()).isEqualTo("APPROVAL_NOT_FOUND");

        jdbc.sql("UPDATE store_membership SET role = 'OWNER' WHERE user_id = ? AND store_id = ?")
                .params(OWNER, store).update();
        jdbc.sql("UPDATE onboarding_progress SET monitoring_started_at = NULL WHERE store_id = ?").param(store)
                .update();
        assertThat(authority.authorize(scope, approvalId).refusal()).isEqualTo("MONITORING_NOT_STARTED");
        assertThat(jdbc.sql("SELECT count(*) FROM authorized_action").query(Long.class).single()).isZero();
    }

    @Test
    void oneLedgerRowPerKeyWithLeasesSlotsAndCooldown() {
        AuthorizedAction action = authority.authorize(scope, approved()).action();
        ActionExecution planned = ledger.plan(scope, action);
        assertThat(ledger.plan(scope, action).id()).isEqualTo(planned.id());
        assertThat(planned.status()).isEqualTo(ExecutionStatus.PLANNED);

        assertThat(ledger.takeLease(scope, "META_AD", dedicatedAd, "worker-1")).isTrue();
        assertThat(ledger.takeLease(scope, "META_AD", dedicatedAd, "worker-2")).isFalse();
        assertThat(ledger.holdsLease(scope, "META_AD", dedicatedAd, "worker-1")).isTrue();
        jdbc.sql("UPDATE action_target_lease SET lease_until = now() - interval '1 second'").update();
        assertThat(ledger.takeLease(scope, "META_AD", dedicatedAd, "worker-2")).isTrue();

        var pre = CanonicalJson.read("{\"ownStatus\":\"ACTIVE\"}");
        assertThat(ledger.admit(scope, planned.id(), pre, "h1")).isEqualTo(ExecutionLedger.Admission.ADMITTED);
        // A retry of the same key keeps its slot and does not take another.
        assertThat(ledger.admit(scope, planned.id(), pre, "h1")).isEqualTo(ExecutionLedger.Admission.ADMITTED);
        assertThat(slots()).isEqualTo(1);
        ledger.markWriteSent(scope, planned.id());
        ledger.move(scope, planned.id(), ExecutionStatus.APPLIED, null, "req-1");
        assertThatThrownBy(() -> ledger.move(scope, planned.id(), ExecutionStatus.PLANNED, null, null))
                .isInstanceOf(IllegalStateException.class);

        // Another key on the same ad within 24 h is a cooldown, not a second pause.
        ActionExecution again = ledger.plan(scope, variant(action, "again", dedicatedAd));
        assertThat(ledger.admit(scope, again.id(), pre, "h2")).isEqualTo(ExecutionLedger.Admission.COOLDOWN);

        // Two more ads fill the limit of three; the fourth is refused.
        for (String name : new String[] {"b", "c"}) {
            ActionExecution other = ledger.plan(scope, variant(action, name, UUID.randomUUID()));
            assertThat(ledger.admit(scope, other.id(), pre, "h")).isEqualTo(ExecutionLedger.Admission.ADMITTED);
        }
        ActionExecution fourth = ledger.plan(scope, variant(action, "d", UUID.randomUUID()));
        assertThat(ledger.admit(scope, fourth.id(), pre, "h")).isEqualTo(ExecutionLedger.Admission.LIMIT_REACHED);

        // A failure that never reached Meta gives its slot back; then the fourth fits.
        ActionExecution failing = ledger.byKey(scope, action.idempotencyKey() + "/b").orElseThrow();
        ledger.move(scope, failing.id(), ExecutionStatus.FAILED, "META_RATE_LIMITED", null);
        assertThat(ledger.admit(scope, fourth.id(), pre, "h")).isEqualTo(ExecutionLedger.Admission.ADMITTED);
    }

    long slots() {
        return jdbc.sql("SELECT count(*) FROM action_limit_slot WHERE released_at IS NULL").query(Long.class).single();
    }

    static AuthorizedAction variant(AuthorizedAction action, String suffix, UUID targetId) {
        return new AuthorizedAction(action.id(), action.proposalId(), action.revision(), action.payloadHash(),
                action.actionHash(), action.approvalId(), action.policyEvaluationId(), action.actionType(),
                action.connectionId(), action.targetType(), targetId, action.targetExternalId(),
                action.parametersHash(), action.expectedStatus(), action.mappingVersion(),
                action.creativeFingerprint(), action.idempotencyKey() + "/" + suffix, action.issuedAt(),
                Instant.now().plusSeconds(60));
    }
}
