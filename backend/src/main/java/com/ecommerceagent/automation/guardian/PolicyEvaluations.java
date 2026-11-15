package com.ecommerceagent.automation.guardian;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import com.ecommerceagent.decisions.ContextSnapshot;
import com.ecommerceagent.decisions.ContextSnapshots;
import com.ecommerceagent.decisions.DecisionProposals;
import com.ecommerceagent.decisions.PauseAd;
import com.ecommerceagent.decisions.StoredProposal;
import com.ecommerceagent.identity.AuditEntry;
import com.ecommerceagent.identity.AuditTrail;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.intelligence.inventory.InventoryContext;
import com.ecommerceagent.metrics.CanonicalJson;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Evaluates the policy of a stored proposal and records the evaluation (ADR-0012). In the pilot the mode is
 * {@code EVALUATION_ONLY}: nothing here authorizes or executes an action.
 */
@Service
@NamedInterface("guardian")
public class PolicyEvaluations {

    private final InventoryProtectionPolicy policy;
    private final DecisionProposals proposals;
    private final ContextSnapshots snapshots;
    private final StoreIntegrationHealth health;
    private final JdbcClient jdbc;
    private final AuditTrail audit;
    private final Clock clock;

    PolicyEvaluations(InventoryProtectionPolicy policy, DecisionProposals proposals, ContextSnapshots snapshots,
            StoreIntegrationHealth health, JdbcClient jdbc, AuditTrail audit, Clock clock) {
        this.policy = policy;
        this.proposals = proposals;
        this.snapshots = snapshots;
        this.health = health;
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public PolicyEvaluation evaluate(TrustedStoreScope scope, UUID proposalId) {
        StoredProposal stored = proposals.find(scope, proposalId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown proposal " + proposalId));
        if (!(stored.proposal().recommendation().action() instanceof PauseAd pause)) {
            throw new IllegalArgumentException("Proposal " + proposalId + " has no action to evaluate");
        }
        InventoryContext context = InventoryContext.of(snapshots.find(scope, stored.proposal().contextSnapshotId())
                .orElseThrow());
        return record(scope, stored, pause, context, null);
    }

    /**
     * Evaluates the proposal's action again on a fresh snapshot of the same episode (ADR-0020 point 3), before an
     * authorization is issued. The evaluation is recorded like any other, with the snapshot it used.
     */
    @Transactional
    public PolicyEvaluation recheck(TrustedStoreScope scope, UUID proposalId, ContextSnapshot fresh) {
        StoredProposal stored = proposals.find(scope, proposalId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown proposal " + proposalId));
        if (!(stored.proposal().recommendation().action() instanceof PauseAd pause)) {
            throw new IllegalArgumentException("Proposal " + proposalId + " has no action to evaluate");
        }
        if (!fresh.opportunityId().equals(stored.proposal().subject() instanceof
                com.ecommerceagent.decisions.OpportunitySubject subject ? subject.opportunityId() : null)) {
            throw new IllegalArgumentException("Snapshot " + fresh.id() + " belongs to another episode");
        }
        return record(scope, stored, pause, InventoryContext.of(fresh), fresh.id());
    }

    private PolicyEvaluation record(TrustedStoreScope scope, StoredProposal stored, PauseAd pause,
            InventoryContext context, UUID recheckSnapshotId) {
        UUID proposalId = stored.proposal().proposalId();
        Instant now = clock.instant();
        PauseAdPolicy.Evaluation result = PauseAdPolicy.evaluate(policy, pause, context, health.status(scope), now);
        PolicyEvaluation evaluation = new PolicyEvaluation(UUID.randomUUID(), proposalId,
                stored.proposal().revision(), InventoryProtectionPolicy.KEY, policy.version(), policy.mode(),
                result.outcome(), result.checks(), now);
        jdbc.sql("""
                        INSERT INTO policy_evaluation (id, organization_id, store_id, proposal_id, revision,
                                                       policy_key, policy_version, parameters, mode, outcome, checks,
                                                       evaluated_at)
                        VALUES (:id, :organizationId, :storeId, :proposalId, :revision, :key, :version,
                                CAST(:parameters AS jsonb), :mode, :outcome, CAST(:checks AS jsonb), :at)
                        """)
                .param("id", evaluation.id())
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("proposalId", proposalId)
                .param("revision", evaluation.revision())
                .param("key", evaluation.policyKey())
                .param("version", evaluation.policyVersion())
                .param("parameters", CanonicalJson.write(recheckSnapshotId == null ? parameters()
                        : parameters().put("contextSnapshotId", recheckSnapshotId.toString())))
                .param("mode", evaluation.mode().name())
                .param("outcome", evaluation.outcome().name())
                .param("checks", CanonicalJson.write(checks(evaluation.checks())))
                .param("at", Timestamp.from(now))
                .update();
        Map<String, String> details = new TreeMap<>(Map.of("proposalId", proposalId.toString(), "outcome",
                evaluation.outcome().name(), "policy", evaluation.policyKey() + "@" + evaluation.policyVersion(),
                "mode", evaluation.mode().name()));
        if (evaluation.decisiveCode() != null) {
            details.put("code", evaluation.decisiveCode());
        }
        audit.record(AuditEntry.inScope(scope, "POLICY_EVALUATED", "policy_evaluation", evaluation.id().toString(),
                details));
        return evaluation;
    }

    /** Evaluations of a proposal, oldest first. */
    public List<PolicyEvaluation> ofProposal(TrustedStoreScope scope, UUID proposalId) {
        return jdbc.sql("""
                        SELECT id, proposal_id, revision, policy_key, policy_version, mode, outcome, checks,
                               evaluated_at
                        FROM policy_evaluation
                        WHERE proposal_id = :proposalId AND organization_id = :organizationId AND store_id = :storeId
                        ORDER BY evaluated_at, id
                        """)
                .param("proposalId", proposalId)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .query((rs, n) -> new PolicyEvaluation(rs.getObject("id", UUID.class),
                        rs.getObject("proposal_id", UUID.class), rs.getInt("revision"), rs.getString("policy_key"),
                        rs.getString("policy_version"), InventoryProtectionPolicy.Mode.valueOf(rs.getString("mode")),
                        PolicyOutcome.valueOf(rs.getString("outcome")),
                        readChecks(CanonicalJson.read(rs.getString("checks"))),
                        rs.getTimestamp("evaluated_at").toInstant()))
                .list();
    }

    public Optional<PolicyEvaluation> latest(TrustedStoreScope scope, UUID proposalId) {
        List<PolicyEvaluation> all = ofProposal(scope, proposalId);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.getLast());
    }

    private ObjectNode parameters() {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("version", policy.version());
        node.put("mode", policy.mode().name());
        node.put("maxStockAge", policy.maxStockAge().toString());
        node.put("maxAdStateAge", policy.maxAdStateAge().toString());
        node.put("maxVerificationAge", policy.maxVerificationAge().toString());
        node.put("autonomy", policy.autonomy().name());
        node.put("l2Enabled", policy.l2Enabled());
        return node;
    }

    private static ArrayNode checks(List<PolicyCheck> checks) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        for (PolicyCheck check : checks) {
            ObjectNode node = array.addObject();
            node.put("name", check.name());
            node.put("result", check.result().name());
            node.put("outcome", check.outcome() == null ? null : check.outcome().name());
            node.put("code", check.code());
            ObjectNode facts = node.putObject("facts");
            new TreeMap<>(check.facts()).forEach(facts::put);
        }
        return array;
    }

    private static List<PolicyCheck> readChecks(JsonNode array) {
        List<PolicyCheck> checks = new ArrayList<>();
        for (JsonNode node : array) {
            Map<String, String> facts = new TreeMap<>();
            node.get("facts").properties().forEach(entry -> facts.put(entry.getKey(), entry.getValue().asString()));
            JsonNode outcome = node.get("outcome");
            JsonNode code = node.get("code");
            checks.add(new PolicyCheck(node.get("name").asString(),
                    PolicyCheck.Result.valueOf(node.get("result").asString()),
                    outcome.isNull() ? null : PolicyOutcome.valueOf(outcome.asString()),
                    code.isNull() ? null : code.asString(), facts));
        }
        return checks;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({InventoryProtectionPolicy.class, GuardianSettings.class})
    static class Settings {
    }
}
