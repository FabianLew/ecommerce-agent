package com.ecommerceagent.automation.execution;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.ecommerceagent.automation.Onboarding;
import com.ecommerceagent.automation.guardian.DecisionApprovals;
import com.ecommerceagent.automation.guardian.PolicyEvaluation;
import com.ecommerceagent.automation.guardian.PolicyEvaluations;
import com.ecommerceagent.automation.guardian.PolicyOutcome;
import com.ecommerceagent.commerce.Ad;
import com.ecommerceagent.commerce.AdRepository;
import com.ecommerceagent.decisions.ContextSnapshot;
import com.ecommerceagent.decisions.DecisionProposal;
import com.ecommerceagent.decisions.DecisionProposals;
import com.ecommerceagent.decisions.Opportunities;
import com.ecommerceagent.decisions.Opportunity;
import com.ecommerceagent.decisions.OpportunitySubject;
import com.ecommerceagent.decisions.PauseAd;
import com.ecommerceagent.decisions.StoredProposal;
import com.ecommerceagent.identity.AuditEntry;
import com.ecommerceagent.identity.AuditTrail;
import com.ecommerceagent.identity.StoreAccess;
import com.ecommerceagent.identity.StorePermission;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.integration.MetaConnection;
import com.ecommerceagent.integration.MetaConnectionStatus;
import com.ecommerceagent.integration.MetaConnections;
import com.ecommerceagent.intelligence.inventory.InventoryContext;
import com.ecommerceagent.intelligence.inventory.InventoryContextBuilder;
import com.ecommerceagent.metrics.CanonicalJson;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Issues {@link AuthorizedAction}s (ADR-0020 point 3): only for a stored approval that is still valid, whose author
 * still may approve, after a new policy evaluation on a fresh snapshot gives the same action again. Nothing else in
 * the application writes {@code authorized_action}.
 */
@Service
@NamedInterface("execution")
public class ExecutionAuthority {

    /** Issued, or refused with a reason code. */
    public record Authorization(AuthorizedAction action, String refusal) {

        public static Authorization refused(String code) {
            return new Authorization(null, code);
        }

        public boolean issued() {
            return action != null;
        }
    }

    private static final String RESOURCE = "authorized_action";

    private final DecisionApprovals approvals;
    private final DecisionProposals proposals;
    private final Opportunities opportunities;
    private final InventoryContextBuilder contexts;
    private final PolicyEvaluations evaluations;
    private final StoreAccess storeAccess;
    private final MetaConnections meta;
    private final AdRepository ads;
    private final MutationStops stops;
    private final ExecutionSettings settings;
    private final AuditTrail audit;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final Onboarding onboarding;

    ExecutionAuthority(DecisionApprovals approvals, DecisionProposals proposals, Opportunities opportunities,
            InventoryContextBuilder contexts, PolicyEvaluations evaluations, StoreAccess storeAccess,
            MetaConnections meta, AdRepository ads, MutationStops stops, ExecutionSettings settings,
            AuditTrail audit, JdbcClient jdbc, Clock clock, Onboarding onboarding) {
        this.onboarding = onboarding;
        this.approvals = approvals;
        this.proposals = proposals;
        this.opportunities = opportunities;
        this.contexts = contexts;
        this.evaluations = evaluations;
        this.storeAccess = storeAccess;
        this.meta = meta;
        this.ads = ads;
        this.stops = stops;
        this.settings = settings;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Authorization authorize(TrustedStoreScope scope, UUID approvalId) {
        // The stored timestamps have microseconds; the returned record equals the stored one.
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        Optional<DecisionApprovals.Approval> found = approvals.find(scope, approvalId);
        if (found.isEmpty()) {
            return Authorization.refused("APPROVAL_NOT_FOUND");
        }
        DecisionApprovals.Approval approval = found.get();
        if (approval.decision() != DecisionApprovals.Decision.APPROVED) {
            return Authorization.refused("NOT_APPROVED");
        }
        if (!now.isBefore(approval.expiresAt())) {
            return Authorization.refused("APPROVAL_EXPIRED");
        }
        Optional<String> stopped = stops.blocker(scope);
        if (stopped.isPresent()) {
            return Authorization.refused(stopped.get());
        }
        if (!onboarding.monitoringStarted(scope)) {
            return Authorization.refused(Onboarding.MONITORING_NOT_STARTED);
        }
        // The approval is the person's: it holds only while they may still approve in this store (ADR-0004).
        boolean stillAllowed = storeAccess.scopeFor(approval.decidedBy(), scope.storeId())
                .filter(person -> person.has(StorePermission.DECISION_APPROVE)).isPresent();
        if (!stillAllowed) {
            return Authorization.refused("APPROVER_ACCESS_REVOKED");
        }
        StoredProposal stored = proposals.find(scope, approval.proposalId()).orElseThrow();
        DecisionProposal proposal = stored.proposal();
        if (!stored.payloadHash().equals(approval.payloadHash())
                || !approval.actionHash().equals(DecisionApprovals.actionHash(proposal))
                || !(proposal.recommendation().action() instanceof PauseAd pause)) {
            return Authorization.refused("ACTION_CHANGED");
        }
        Optional<MetaConnection> connection = meta.find(scope);
        if (connection.isEmpty() || connection.get().status() != MetaConnectionStatus.CONNECTED) {
            return Authorization.refused("META_NOT_CONNECTED");
        }
        if (!connection.get().canWrite()) {
            return Authorization.refused("META_WRITE_PERMISSION_MISSING");
        }
        UUID opportunityId = ((OpportunitySubject) proposal.subject()).opportunityId();
        Opportunity opportunity = opportunities.find(scope, opportunityId).orElseThrow();
        ContextSnapshot fresh = contexts.build(scope, opportunity);
        PolicyEvaluation recheck = evaluations.recheck(scope, proposal.proposalId(), fresh);
        if (recheck.outcome() != PolicyOutcome.REQUIRE_APPROVAL) {
            return Authorization.refused("POLICY_" + recheck.outcome().name()
                    + (recheck.decisiveCode() == null ? "" : ":" + recheck.decisiveCode()));
        }
        InventoryContext context = InventoryContext.of(fresh);
        InventoryContext.AdFact target = context.ads().stream().filter(ad -> ad.adId().equals(pause.adId()))
                .findFirst().orElse(null);
        Optional<Ad> ad = ads.ad(scope, pause.adId());
        if (target == null || ad.isEmpty()) {
            return Authorization.refused("TARGET_NOT_IN_CONTEXT");
        }
        String parameters = CanonicalJson.hash(JsonNodeFactory.instance.objectNode().put("desiredStatus", "PAUSED"));
        String key = AuthorizedAction.key(scope.storeId(), connection.get().id(), proposal.proposalId(),
                proposal.revision(), AuthorizedAction.PAUSE_AD, target.externalId());
        AuthorizedAction action = new AuthorizedAction(UUID.randomUUID(), proposal.proposalId(), proposal.revision(),
                stored.payloadHash(), approval.actionHash(), approval.id(), recheck.id(), AuthorizedAction.PAUSE_AD,
                connection.get().id(), AuthorizedAction.META_AD, pause.adId(), target.externalId(), parameters,
                "PAUSED", target.mappingVersion(), ad.get().creativeFingerprint(), key, now,
                now.plus(settings.authorizationTtl()));
        insert(scope, action);
        audit.record(AuditEntry.inScope(scope, "ACTION_AUTHORIZED", RESOURCE, action.id().toString(),
                Map.of("proposalId", proposal.proposalId().toString(), "approvalId", approval.id().toString(),
                        "target", target.externalId(), "policyEvaluationId", recheck.id().toString())));
        return new Authorization(action, null);
    }

    public Optional<AuthorizedAction> find(TrustedStoreScope scope, UUID actionId) {
        return select(scope, "id = :value", actionId);
    }

    /** The newest authorization issued for the approval, valid or not. */
    public Optional<AuthorizedAction> latest(TrustedStoreScope scope, UUID approvalId) {
        return select(scope, "approval_id = :value ORDER BY issued_at DESC LIMIT 1", approvalId);
    }

    private Optional<AuthorizedAction> select(TrustedStoreScope scope, String condition, UUID value) {
        return jdbc.sql("""
                        SELECT id, proposal_id, revision, payload_hash, action_hash, approval_id,
                               policy_evaluation_id, action_type, connection_id, target_type, target_id,
                               target_external_id, parameters_hash, expected_status, mapping_version,
                               creative_fingerprint, idempotency_key, issued_at, expires_at
                        FROM authorized_action
                        WHERE organization_id = :organizationId AND store_id = :storeId AND\s""" + condition)
                .param("value", value)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .query((rs, n) -> new AuthorizedAction(rs.getObject("id", UUID.class),
                        rs.getObject("proposal_id", UUID.class), rs.getInt("revision"), rs.getString("payload_hash"),
                        rs.getString("action_hash"), rs.getObject("approval_id", UUID.class),
                        rs.getObject("policy_evaluation_id", UUID.class), rs.getString("action_type"),
                        rs.getObject("connection_id", UUID.class), rs.getString("target_type"),
                        rs.getObject("target_id", UUID.class), rs.getString("target_external_id"),
                        rs.getString("parameters_hash"), rs.getString("expected_status"),
                        rs.getInt("mapping_version"), rs.getString("creative_fingerprint"),
                        rs.getString("idempotency_key"), rs.getTimestamp("issued_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant()))
                .optional();
    }

    private void insert(TrustedStoreScope scope, AuthorizedAction action) {
        jdbc.sql("""
                        INSERT INTO authorized_action (id, organization_id, store_id, proposal_id, revision,
                                                       payload_hash, action_hash, approval_id, policy_evaluation_id,
                                                       action_type, connection_id, target_type, target_id,
                                                       target_external_id, parameters_hash, expected_status,
                                                       mapping_version, creative_fingerprint, idempotency_key,
                                                       issued_at, expires_at)
                        VALUES (:id, :organizationId, :storeId, :proposalId, :revision, :payloadHash, :actionHash,
                                :approvalId, :evaluationId, :type, :connectionId, :targetType, :targetId,
                                :externalId, :parametersHash, :expectedStatus, :mappingVersion, :fingerprint, :key,
                                :issuedAt, :expiresAt)
                        """)
                .param("id", action.id())
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("proposalId", action.proposalId())
                .param("revision", action.revision())
                .param("payloadHash", action.payloadHash())
                .param("actionHash", action.actionHash())
                .param("approvalId", action.approvalId())
                .param("evaluationId", action.policyEvaluationId())
                .param("type", action.actionType())
                .param("connectionId", action.connectionId())
                .param("targetType", action.targetType())
                .param("targetId", action.targetId())
                .param("externalId", action.targetExternalId())
                .param("parametersHash", action.parametersHash())
                .param("expectedStatus", action.expectedStatus())
                .param("mappingVersion", action.mappingVersion())
                .param("fingerprint", action.creativeFingerprint(), java.sql.Types.VARCHAR)
                .param("key", action.idempotencyKey())
                .param("issuedAt", Timestamp.from(action.issuedAt()))
                .param("expiresAt", Timestamp.from(action.expiresAt()))
                .update();
    }
}
