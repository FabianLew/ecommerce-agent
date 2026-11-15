package com.ecommerceagent.automation.guardian;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import com.ecommerceagent.decisions.DecisionDisposition;
import com.ecommerceagent.decisions.DecisionProposal;
import com.ecommerceagent.decisions.DecisionProposals;
import com.ecommerceagent.decisions.Opportunities;
import com.ecommerceagent.decisions.OpportunitySubject;
import com.ecommerceagent.decisions.ProposalJson;
import com.ecommerceagent.decisions.ProposalStatus;
import com.ecommerceagent.decisions.StoredProposal;
import com.ecommerceagent.identity.AuditEntry;
import com.ecommerceagent.identity.AuditTrail;
import com.ecommerceagent.identity.StoreAccess;
import com.ecommerceagent.identity.StorePermission;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.metrics.CanonicalJson;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Approval and rejection of one proposal revision (ADR-0020 point 2). Only a person with {@code DECISION_APPROVE}
 * decides, after a fresh membership check; the approval binds the payload and action hashes and the policy evaluation
 * it was given against. An approval is not an authorization: execution re-evaluates the policy first.
 */
@Service
@NamedInterface("guardian")
public class DecisionApprovals {

    static final Duration MAX_VALIDITY = Duration.ofHours(24);
    private static final String RESOURCE = "decision_approval";

    public enum Decision {
        APPROVED,
        REJECTED
    }

    public record Approval(UUID id, UUID proposalId, int revision, Decision decision, String payloadHash,
            String actionHash, UUID policyEvaluationId, String reason, UUID decidedBy, Instant decidedAt,
            Instant expiresAt) {
    }

    /** The stored decision; {@code replayed} when the same command with the same key was already applied. */
    public record Result(Approval approval, UUID opportunityId, boolean replayed) {
    }

    private final DecisionProposals proposals;
    private final Opportunities opportunities;
    private final PolicyEvaluations evaluations;
    private final InventoryProtectionPolicy policy;
    private final StoreAccess storeAccess;
    private final AuditTrail audit;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final GuardianSignals signals;
    private final Clock clock;

    DecisionApprovals(DecisionProposals proposals, Opportunities opportunities, PolicyEvaluations evaluations,
            InventoryProtectionPolicy policy, StoreAccess storeAccess, AuditTrail audit, JdbcClient jdbc,
            TransactionTemplate transactions, GuardianSignals signals, Clock clock) {
        this.proposals = proposals;
        this.opportunities = opportunities;
        this.evaluations = evaluations;
        this.policy = policy;
        this.storeAccess = storeAccess;
        this.audit = audit;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.signals = signals;
        this.clock = clock;
    }

    /** Whether the proposal can be approved now; the reason code when it cannot (for {@code allowedActions}). */
    public Optional<String> blocker(TrustedStoreScope scope, StoredProposal stored, Decision decision) {
        if (policy.mode() != InventoryProtectionPolicy.Mode.EXECUTE_ON_APPROVAL) {
            return Optional.of("SHADOW_MODE");
        }
        if (!scope.has(StorePermission.DECISION_APPROVE)) {
            return Optional.of("PERMISSION_MISSING");
        }
        return precondition(scope, stored, decision, clock.instant());
    }

    public Result approve(TrustedStoreScope scope, UUID proposalId, int revision, String idempotencyKey) {
        return decide(scope, proposalId, revision, Decision.APPROVED, null, idempotencyKey);
    }

    public Result reject(TrustedStoreScope scope, UUID proposalId, int revision, String reason,
            String idempotencyKey) {
        return decide(scope, proposalId, revision, Decision.REJECTED, reason, idempotencyKey);
    }

    public Optional<Approval> find(TrustedStoreScope scope, UUID approvalId) {
        return query(scope, "id = :value", approvalId);
    }

    /** The decision stored under an idempotency key, to answer a repeated command before its version check. */
    public Optional<Approval> byKey(TrustedStoreScope scope, String idempotencyKey) {
        return idempotencyKey == null || idempotencyKey.isBlank() ? Optional.empty()
                : query(scope, "idempotency_key = :value", idempotencyKey.strip());
    }

    public Optional<Approval> ofProposal(TrustedStoreScope scope, UUID proposalId) {
        return query(scope, "proposal_id = :value", proposalId);
    }

    /** The action a proposal asks for, as a hash: type, target and parameters (ADR-0020 point 2). */
    public static String actionHash(DecisionProposal proposal) {
        JsonNode action = ProposalJson.write(proposal.recommendation()).get("action");
        return action == null || action.isNull() ? null : CanonicalJson.hash(action);
    }

    private Result decide(TrustedStoreScope scope, UUID proposalId, int revision, Decision decision, String reason,
            String idempotencyKey) {
        String key = requireKey(idempotencyKey);
        String note = reason == null || reason.isBlank() ? null : reason.strip();
        if (note != null && note.length() > 1000) {
            throw new ApprovalException(ApprovalException.Kind.INVALID, "REASON_TOO_LONG");
        }
        if (policy.mode() != InventoryProtectionPolicy.Mode.EXECUTE_ON_APPROVAL) {
            throw new ApprovalException(ApprovalException.Kind.CONFLICT, "SHADOW_MODE");
        }
        // Approving changes what the operator may do to a store's ads: re-check the membership (ADR-0004).
        TrustedStoreScope fresh = storeAccess.reauthorize(scope, StorePermission.DECISION_APPROVE);
        UUID userId = fresh.userId().orElseThrow(() -> new ApprovalException(ApprovalException.Kind.FORBIDDEN,
                "PERSON_REQUIRED"));
        tools.jackson.databind.node.ObjectNode request = tools.jackson.databind.node.JsonNodeFactory.instance
                .objectNode().put("decision", decision.name()).put("proposalId", proposalId.toString())
                .put("revision", revision).put("reason", note);
        String requestHash = CanonicalJson.hash(request);

        Result result = transactions.execute(status -> {
            Optional<Approval> byKey = query(fresh, "idempotency_key = :value", key);
            if (byKey.isPresent()) {
                if (!requestHash.equals(requestHash(fresh, byKey.get().id()))) {
                    throw new ApprovalException(ApprovalException.Kind.CONFLICT, "IDEMPOTENCY_KEY_REUSED");
                }
                return new Result(byKey.get(), opportunityOf(fresh, proposalId), true);
            }
            StoredProposal stored = proposals.find(fresh, proposalId)
                    .orElseThrow(() -> new ApprovalException(ApprovalException.Kind.NOT_FOUND, "NOT_FOUND"));
            if (ofProposal(fresh, proposalId).isPresent()) {
                throw new ApprovalException(ApprovalException.Kind.CONFLICT, "ALREADY_DECIDED");
            }
            if (stored.proposal().revision() != revision) {
                throw new ApprovalException(ApprovalException.Kind.CONFLICT, "REVISION_MISMATCH");
            }
            Instant now = clock.instant();
            precondition(fresh, stored, decision, now).ifPresent(code -> {
                throw new ApprovalException(ApprovalException.Kind.CONFLICT, code);
            });
            DecisionProposal proposal = stored.proposal();
            PolicyEvaluation evaluation = decision == Decision.APPROVED
                    ? evaluations.latest(fresh, proposalId).orElseThrow() : null;
            Instant expires = decision == Decision.APPROVED
                    ? min(proposal.expiresAt(), now.plus(MAX_VALIDITY)) : now;
            Approval approval = new Approval(UUID.randomUUID(), proposalId, revision, decision, stored.payloadHash(),
                    decision == Decision.APPROVED ? actionHash(proposal) : null,
                    evaluation == null ? null : evaluation.id(), note, userId, now, expires);
            insert(fresh, approval, key, requestHash);
            ProposalStatus next = decision == Decision.APPROVED ? ProposalStatus.APPROVED : ProposalStatus.REJECTED;
            if (!proposals.close(fresh, proposalId, next)) {
                throw new ApprovalException(ApprovalException.Kind.CONFLICT, "PROPOSAL_NOT_OPEN");
            }
            Map<String, String> details = new TreeMap<>(Map.of("proposalId", proposalId.toString(), "revision",
                    Integer.toString(revision)));
            if (note != null) {
                details.put("reason", note);
            }
            audit.record(AuditEntry.inScope(fresh, decision == Decision.APPROVED ? "DECISION_APPROVED"
                    : "DECISION_REJECTED", RESOURCE, approval.id().toString(), details));
            return new Result(approval, opportunityOf(fresh, proposalId), false);
        });
        signal(fresh, result);
        return result;
    }

    /** The checks shared by {@link #blocker} and a command, in the order a person can fix them. */
    private Optional<String> precondition(TrustedStoreScope scope, StoredProposal stored, Decision decision,
            Instant now) {
        DecisionProposal proposal = stored.proposal();
        if (stored.status() != ProposalStatus.PROPOSED) {
            return Optional.of("PROPOSAL_NOT_OPEN");
        }
        if (!now.isBefore(proposal.expiresAt())) {
            return Optional.of("PROPOSAL_EXPIRED");
        }
        UUID opportunityId = opportunityOf(scope, proposal.proposalId());
        List<StoredProposal> revisions = proposals.ofOpportunity(scope, opportunityId);
        if (!revisions.isEmpty() && !revisions.getLast().proposal().proposalId().equals(proposal.proposalId())) {
            return Optional.of("PROPOSAL_SUPERSEDED");
        }
        if (decision == Decision.REJECTED) {
            return Optional.empty();
        }
        if (proposal.recommendation().disposition() != DecisionDisposition.RECOMMEND_ACTION
                || proposal.recommendation().action() == null) {
            return Optional.of("NOTHING_TO_APPROVE");
        }
        Optional<PolicyEvaluation> evaluation = evaluations.latest(scope, proposal.proposalId());
        if (evaluation.isEmpty() || evaluation.get().revision() != proposal.revision()
                || evaluation.get().outcome() != PolicyOutcome.REQUIRE_APPROVAL) {
            return Optional.of("POLICY_NOT_APPROVABLE");
        }
        return Optional.empty();
    }

    private UUID opportunityOf(TrustedStoreScope scope, UUID proposalId) {
        DecisionProposal proposal = proposals.find(scope, proposalId).orElseThrow().proposal();
        if (proposal.subject() instanceof OpportunitySubject subject) {
            return subject.opportunityId();
        }
        throw new ApprovalException(ApprovalException.Kind.NOT_FOUND, "NOT_FOUND");
    }

    private void signal(TrustedStoreScope scope, Result result) {
        opportunities.find(scope, result.opportunityId())
                .ifPresent(opportunity -> signals.approvalDecided(scope.storeId(), opportunity,
                        result.approval().id()));
    }

    private void insert(TrustedStoreScope scope, Approval approval, String key, String requestHash) {
        jdbc.sql("""
                        INSERT INTO decision_approval (id, organization_id, store_id, proposal_id, revision, decision,
                                                       payload_hash, action_hash, policy_evaluation_id, reason,
                                                       decided_by, decided_at, expires_at, idempotency_key,
                                                       request_hash)
                        VALUES (:id, :organizationId, :storeId, :proposalId, :revision, :decision, :payloadHash,
                                :actionHash, :evaluationId, :reason, :decidedBy, :decidedAt, :expiresAt, :key,
                                :requestHash)
                        """)
                .param("id", approval.id())
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("proposalId", approval.proposalId())
                .param("revision", approval.revision())
                .param("decision", approval.decision().name())
                .param("payloadHash", approval.payloadHash())
                .param("actionHash", approval.actionHash(), java.sql.Types.VARCHAR)
                .param("evaluationId", approval.policyEvaluationId(), java.sql.Types.OTHER)
                .param("reason", approval.reason(), java.sql.Types.VARCHAR)
                .param("decidedBy", approval.decidedBy())
                .param("decidedAt", Timestamp.from(approval.decidedAt()))
                .param("expiresAt", Timestamp.from(approval.expiresAt()))
                .param("key", key)
                .param("requestHash", requestHash)
                .update();
    }

    private String requestHash(TrustedStoreScope scope, UUID approvalId) {
        return jdbc.sql("""
                        SELECT request_hash FROM decision_approval
                        WHERE id = :id AND organization_id = :organizationId AND store_id = :storeId
                        """)
                .param("id", approvalId)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .query(String.class).single();
    }

    private Optional<Approval> query(TrustedStoreScope scope, String condition, Object value) {
        return jdbc.sql("""
                        SELECT id, proposal_id, revision, decision, payload_hash, action_hash, policy_evaluation_id,
                               reason, decided_by, decided_at, expires_at
                        FROM decision_approval
                        WHERE organization_id = :organizationId AND store_id = :storeId AND\s""" + condition)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("value", value)
                .query((rs, n) -> new Approval(rs.getObject("id", UUID.class), rs.getObject("proposal_id", UUID.class),
                        rs.getInt("revision"), Decision.valueOf(rs.getString("decision")),
                        rs.getString("payload_hash"), rs.getString("action_hash"),
                        rs.getObject("policy_evaluation_id", UUID.class), rs.getString("reason"),
                        rs.getObject("decided_by", UUID.class), rs.getTimestamp("decided_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant()))
                .optional();
    }

    private static String requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > 200) {
            throw new ApprovalException(ApprovalException.Kind.INVALID, "IDEMPOTENCY_KEY_REQUIRED");
        }
        return key.strip();
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
