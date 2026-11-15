package com.ecommerceagent.automation.execution;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.ecommerceagent.commerce.CommerceSource;
import com.ecommerceagent.commerce.InventoryObservation;
import com.ecommerceagent.commerce.InventoryRepository;
import com.ecommerceagent.commerce.OrderCoverage;
import com.ecommerceagent.commerce.OrderRepository;
import com.ecommerceagent.decisions.DecisionProposals;
import com.ecommerceagent.decisions.Opportunities;
import com.ecommerceagent.decisions.Opportunity;
import com.ecommerceagent.decisions.OpportunitySubject;
import com.ecommerceagent.identity.AuditEntry;
import com.ecommerceagent.identity.AuditTrail;
import com.ecommerceagent.identity.StoreAccess;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.integration.MetaAdActions;
import com.ecommerceagent.metrics.CanonicalJson;
import com.ecommerceagent.metrics.MetricDefinition;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The technical outcome of an execution and its observation plan (ADR-0021 points 4–5). A paused ad is a technical
 * result; what happened to stock and sales afterwards is observed, with its limitations, and never called success.
 */
@Service
@NamedInterface("execution")
public class ActionOutcomes {

    private final MeterRegistry meters;

    public enum Authorship {
        /** We sent the write and a read confirmed it. */
        OPERATOR,
        /** The effect was found during reconciliation, or it is not confirmed: someone else may have done it. */
        UNCERTAIN,
        /** Nothing of ours changed the ad. */
        NONE
    }

    public enum Checkpoint {
        H24(Duration.ofHours(24)),
        D7(Duration.ofDays(7));

        private final Duration after;

        Checkpoint(Duration after) {
            this.after = after;
        }

        public Duration after() {
            return after;
        }
    }

    public enum ObservationStatus {
        PENDING,
        OBSERVED,
        INCONCLUSIVE
    }

    public record Observation(Checkpoint checkpoint, Instant dueAt, ObservationStatus status, JsonNode values,
            List<String> limitations, Instant observedAt) {
    }

    public record Outcome(UUID id, UUID executionId, ExecutionStatus technicalResult, String resultCode,
            Authorship authorship, JsonNode preState, JsonNode postState, String verificationSource,
            Instant verifiedAt, Instant recordedAt, UUID variantId, List<Observation> observations) {
    }

    static final String META_READ = "META_READ";
    static final String SERVICE = "outcome-observer";
    private static final String RESOURCE = "action_outcome";

    private final ExecutionLedger ledger;
    private final ExecutionAuthority authority;
    private final DecisionProposals proposals;
    private final Opportunities opportunities;
    private final MetaAdActions meta;
    private final InventoryRepository inventory;
    private final OrderRepository orders;
    private final StoreAccess storeAccess;
    private final AuditTrail audit;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    ActionOutcomes(ExecutionLedger ledger, ExecutionAuthority authority, DecisionProposals proposals,
            Opportunities opportunities, MetaAdActions meta, InventoryRepository inventory, OrderRepository orders,
            StoreAccess storeAccess, AuditTrail audit, JdbcClient jdbc, TransactionTemplate transactions,
            Clock clock, MeterRegistry meters) {
        this.meters = meters;
        this.ledger = ledger;
        this.authority = authority;
        this.proposals = proposals;
        this.opportunities = opportunities;
        this.meta = meta;
        this.inventory = inventory;
        this.orders = orders;
        this.storeAccess = storeAccess;
        this.audit = audit;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** Records the outcome of a final execution once; a repeated call returns the stored one. */
    public Outcome record(TrustedStoreScope scope, UUID executionId) {
        Optional<Outcome> stored = ofExecution(scope, executionId);
        if (stored.isPresent()) {
            return stored.get();
        }
        ActionExecution execution = ledger.find(scope, executionId).orElseThrow();
        if (!List.of(ExecutionStatus.VERIFIED, ExecutionStatus.NOOP, ExecutionStatus.UNKNOWN, ExecutionStatus.FAILED,
                ExecutionStatus.CONFLICT).contains(execution.status())) {
            throw new IllegalStateException("Execution " + executionId + " is not final: " + execution.status());
        }
        AuthorizedAction action = authority.find(scope, execution.authorizedActionId()).orElseThrow();
        UUID opportunityId = ((OpportunitySubject) proposals.find(scope, action.proposalId()).orElseThrow()
                .proposal().subject()).opportunityId();
        UUID variantId = opportunities.find(scope, opportunityId)
                .filter(o -> Opportunity.PRODUCT_VARIANT.equals(o.subjectType())).map(Opportunity::subjectId)
                .orElse(null);
        // A read, never a write: allowed while mutations are stopped.
        MetaAdActions.Read read = meta.read(scope, action.targetExternalId());
        ObjectNode post = JsonNodeFactory.instance.objectNode();
        if (read.ok()) {
            post.put("status", read.state().status()).put("effectiveStatus", read.state().effectiveStatus())
                    .put("creative", read.state().creativeFingerprint());
        } else {
            post.put("readError", read.errorCode());
        }
        Instant now = clock.instant();
        Instant verifiedAt = execution.status() == ExecutionStatus.VERIFIED ? execution.updatedAt() : null;
        UUID id = UUID.randomUUID();
        transactions.executeWithoutResult(status -> {
            int inserted = jdbc.sql("""
                            INSERT INTO action_outcome (id, organization_id, store_id, execution_id,
                                                        authorized_action_id, proposal_id, revision, approval_id,
                                                        policy_evaluation_id, variant_id, ad_id, technical_result,
                                                        result_code, authorship, pre_state, post_state,
                                                        verification_source, verified_at, recorded_at)
                            VALUES (:id, :organizationId, :storeId, :executionId, :actionId, :proposalId, :revision,
                                    :approvalId, :evaluationId, :variantId, :adId, :result, :code, :authorship,
                                    CAST(:preState AS jsonb), CAST(:postState AS jsonb), :source, :verifiedAt, :now)
                            ON CONFLICT (execution_id) DO NOTHING
                            """)
                    .param("id", id)
                    .param("organizationId", scope.organizationId())
                    .param("storeId", scope.storeId())
                    .param("executionId", executionId)
                    .param("actionId", action.id())
                    .param("proposalId", action.proposalId())
                    .param("revision", action.revision())
                    .param("approvalId", action.approvalId())
                    .param("evaluationId", action.policyEvaluationId())
                    .param("variantId", variantId, java.sql.Types.OTHER)
                    .param("adId", action.targetId())
                    .param("result", execution.status().name())
                    .param("code", execution.resultCode(), java.sql.Types.VARCHAR)
                    .param("authorship", authorship(execution).name())
                    .param("preState", execution.preState() == null ? null : CanonicalJson.write(execution.preState()),
                            java.sql.Types.VARCHAR)
                    .param("postState", CanonicalJson.write(post))
                    .param("source", META_READ)
                    .param("verifiedAt", verifiedAt == null ? null : Timestamp.from(verifiedAt),
                            java.sql.Types.TIMESTAMP)
                    .param("now", Timestamp.from(now))
                    .update();
            if (inserted == 0) {
                return;
            }
            if (verifiedAt != null && variantId != null) {
                for (Checkpoint checkpoint : Checkpoint.values()) {
                    jdbc.sql("""
                                    INSERT INTO outcome_observation (id, organization_id, store_id, outcome_id,
                                                                     checkpoint, due_at, status)
                                    VALUES (:id, :organizationId, :storeId, :outcomeId, :checkpoint, :dueAt, 'PENDING')
                                    """)
                            .param("id", UUID.randomUUID())
                            .param("organizationId", scope.organizationId())
                            .param("storeId", scope.storeId())
                            .param("outcomeId", id)
                            .param("checkpoint", checkpoint.name())
                            .param("dueAt", Timestamp.from(verifiedAt.plus(checkpoint.after())))
                            .update();
                }
            }
            audit.record(AuditEntry.inScope(scope, "ACTION_OUTCOME_RECORDED", RESOURCE, id.toString(),
                    Map.of("executionId", executionId.toString(), "technicalResult", execution.status().name(),
                            "authorship", authorship(execution).name())));
            // ADR-0024: one count per final execution, by technical result and authorship.
            meters.counter("operator.action.outcomes", "result", execution.status().name(),
                    "authorship", authorship(execution).name()).increment();
        });
        return ofExecution(scope, executionId).orElseThrow();
    }

    public Optional<Outcome> ofExecution(TrustedStoreScope scope, UUID executionId) {
        return jdbc.sql("""
                        SELECT id, execution_id, technical_result, result_code, authorship, pre_state, post_state,
                               verification_source, verified_at, recorded_at, variant_id
                        FROM action_outcome
                        WHERE organization_id = :organizationId AND store_id = :storeId AND execution_id = :executionId
                        """)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("executionId", executionId)
                .query((rs, n) -> new Outcome(rs.getObject("id", UUID.class), rs.getObject("execution_id", UUID.class),
                        ExecutionStatus.valueOf(rs.getString("technical_result")), rs.getString("result_code"),
                        Authorship.valueOf(rs.getString("authorship")), json(rs.getString("pre_state")),
                        json(rs.getString("post_state")), rs.getString("verification_source"),
                        instant(rs.getTimestamp("verified_at")), rs.getTimestamp("recorded_at").toInstant(),
                        rs.getObject("variant_id", UUID.class), List.of()))
                .optional()
                .map(outcome -> new Outcome(outcome.id(), outcome.executionId(), outcome.technicalResult(),
                        outcome.resultCode(), outcome.authorship(), outcome.preState(), outcome.postState(),
                        outcome.verificationSource(), outcome.verifiedAt(), outcome.recordedAt(), outcome.variantId(),
                        observations(scope, outcome.id())));
    }

    /**
     * Observes every due checkpoint (worker profile). Each one records what the read models say since the
     * verification, with the limitations that keep it from being read as proof.
     */
    public int observeDue(int limit) {
        List<Object[]> due = jdbc.sql("""
                        SELECT o.organization_id, o.store_id, o.id, o.checkpoint, o.due_at, a.variant_id, a.verified_at
                        FROM outcome_observation o
                        JOIN action_outcome a ON a.id = o.outcome_id AND a.store_id = o.store_id
                        WHERE o.status = 'PENDING' AND o.due_at <= :now
                        ORDER BY o.due_at
                        LIMIT :limit
                        """)
                .param("now", Timestamp.from(clock.instant()))
                .param("limit", limit)
                .query((rs, n) -> new Object[] {rs.getObject("organization_id", UUID.class),
                        rs.getObject("store_id", UUID.class), rs.getObject("id", UUID.class),
                        rs.getString("checkpoint"), rs.getTimestamp("due_at").toInstant(),
                        rs.getObject("variant_id", UUID.class), rs.getTimestamp("verified_at").toInstant()})
                .list();
        int observed = 0;
        for (Object[] row : due) {
            Optional<TrustedStoreScope> scope = storeAccess.systemScope(SERVICE, (UUID) row[0], (UUID) row[1]);
            if (scope.isPresent()) {
                observe(scope.get(), (UUID) row[2], (Instant) row[6], (Instant) row[4], (UUID) row[5]);
                observed++;
            }
        }
        return observed;
    }

    private void observe(TrustedStoreScope scope, UUID observationId, Instant from, Instant until, UUID variantId) {
        ObjectNode values = JsonNodeFactory.instance.objectNode();
        List<String> limitations = new ArrayList<>();
        // A pause does not prove what happened next: every observation carries this limitation.
        limitations.add("ATTRIBUTION_NOT_CAUSAL");
        Optional<InventoryObservation> start = inventory.latestAt(scope, variantId, from);
        Optional<InventoryObservation> end = inventory.latestAt(scope, variantId, until);
        Integer startQuantity = start.map(o -> o.reading().stockQuantity()).orElse(null);
        Integer endQuantity = end.map(o -> o.reading().stockQuantity()).orElse(null);
        boolean stockKnown = startQuantity != null && endQuantity != null;
        if (stockKnown) {
            values.put("stockAtVerification", startQuantity).put("stockAtCheckpoint", endQuantity);
            if (stockIncreased(scope, variantId, from, until, startQuantity)) {
                // A delivery or a correction in the window censors the stock comparison (document 05 §F).
                limitations.add("STOCK_INCREASED_IN_WINDOW");
            }
        } else {
            limitations.add("STOCK_NOT_KNOWN");
        }
        Optional<OrderCoverage> coverage = orders.coverage(scope, CommerceSource.WOOCOMMERCE);
        boolean salesKnown = coverage.filter(c -> c.covers(from, until)).isPresent();
        if (salesKnown) {
            values.put("unitsSold", orders.unitsSold(scope, variantId, from, until,
                    MetricDefinition.QUALIFYING_ORDER_STATUSES_V1));
        } else {
            limitations.add("ORDERS_NOT_COVERED");
        }
        // Meta insights are not imported in the pilot (F5): spend is never estimated from the paused ad.
        values.putNull("metaSpend");
        limitations.add("SPEND_NOT_AVAILABLE");
        ObservationStatus status = stockKnown && salesKnown ? ObservationStatus.OBSERVED
                : ObservationStatus.INCONCLUSIVE;
        ArrayNode codes = JsonNodeFactory.instance.arrayNode();
        limitations.forEach(codes::add);
        jdbc.sql("""
                        UPDATE outcome_observation
                        SET status = :status, observed_values = CAST(:values AS jsonb),
                            limitations = CAST(:limitations AS jsonb), observed_at = :now
                        WHERE id = :id AND organization_id = :organizationId AND store_id = :storeId
                          AND status = 'PENDING'
                        """)
                .param("status", status.name())
                .param("values", CanonicalJson.write(values))
                .param("limitations", CanonicalJson.write(codes))
                .param("now", Timestamp.from(clock.instant()))
                .param("id", observationId)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .update();
    }

    private boolean stockIncreased(TrustedStoreScope scope, UUID variantId, Instant from, Instant until,
            int startQuantity) {
        int previous = startQuantity;
        for (InventoryObservation reading : inventory.observed(scope, variantId, from, until.plusNanos(1000))) {
            Integer quantity = reading.reading().stockQuantity();
            if (quantity != null && quantity > previous) {
                return true;
            }
            if (quantity != null) {
                previous = quantity;
            }
        }
        return false;
    }

    private List<Observation> observations(TrustedStoreScope scope, UUID outcomeId) {
        return jdbc.sql("""
                        SELECT checkpoint, due_at, status, observed_values, limitations, observed_at
                        FROM outcome_observation
                        WHERE organization_id = :organizationId AND store_id = :storeId AND outcome_id = :outcomeId
                        ORDER BY due_at
                        """)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("outcomeId", outcomeId)
                .query((rs, n) -> {
                    List<String> limitations = new ArrayList<>();
                    json(rs.getString("limitations")).forEach(node -> limitations.add(node.asString()));
                    return new Observation(Checkpoint.valueOf(rs.getString("checkpoint")),
                            rs.getTimestamp("due_at").toInstant(), ObservationStatus.valueOf(rs.getString("status")),
                            json(rs.getString("observed_values")), List.copyOf(limitations),
                            instant(rs.getTimestamp("observed_at")));
                })
                .list();
    }

    static Authorship authorship(ActionExecution execution) {
        return switch (execution.status()) {
            case VERIFIED -> "EFFECT_FOUND_ON_RECONCILIATION".equals(execution.resultCode()) ? Authorship.UNCERTAIN
                    : Authorship.OPERATOR;
            case UNKNOWN -> Authorship.UNCERTAIN;
            default -> Authorship.NONE;
        };
    }

    private static JsonNode json(String value) {
        return value == null ? null : CanonicalJson.read(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
