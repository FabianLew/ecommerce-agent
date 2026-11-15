package com.ecommerceagent.aigovernance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import com.ecommerceagent.FixtureDb;
import com.ecommerceagent.TestcontainersConfiguration;
import com.ecommerceagent.identity.PlatformOperator;
import com.ecommerceagent.identity.PlatformOperatorDeniedException;
import com.ecommerceagent.identity.PlatformOperators;
import com.ecommerceagent.identity.StoreAccess;
import com.ecommerceagent.identity.StoreRowGuard;
import com.ecommerceagent.identity.TestScopes;
import com.ecommerceagent.identity.TrustedStoreScope;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * ADR-0027 section 4.5 (E-54.6): uncertain AI calls are closed by platform operators or matched with their provider
 * by request id, and a conservative reconciliation is corrected by an adjustment entry, on a real PostgreSQL through
 * the runtime role. Prices and the estimate are those of {@link AiUsageLedgerTest}: each call reserves 0.135 USD.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
class AiReconciliationTest {

    private static final BigDecimal RESERVED = new BigDecimal("0.135");

    @Autowired
    AiBudgetGovernor governor;

    @Autowired
    AiUsageLedger ledger;

    @Autowired
    AiReconciliation reconciliation;

    @Autowired
    AiReservationSweeper sweeper;

    @Autowired
    PlatformOperators operators;

    @Autowired
    StoreAccess storeAccess;

    @Autowired
    AiGovernanceSettings settings;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    FixtureDb fixtures;

    UUID organization;
    UUID store;
    String model;
    UUID operatorUser;
    UUID memberUser;

    @BeforeEach
    void setUp() {
        fixtures.registerAutomations("inventory");
        organization = UUID.randomUUID();
        store = UUID.randomUUID();
        fixtures.sql("INSERT INTO organization (id, name) VALUES (?, 'AI reconciliation')").param(organization)
                .update();
        fixtures.sql("""
                        INSERT INTO store (id, organization_id, name, currency, timezone)
                        VALUES (?, ?, 'R', 'EUR', 'UTC')
                        """).params(store, organization).update();
        model = "reconcile-model-" + UUID.randomUUID();
        fixtures.sql("""
                        INSERT INTO ai_price (id, provider, model, region, price_version, currency, input_per_mtok,
                                              output_per_mtok, effective_from, source, verified_at)
                        VALUES (gen_random_uuid(), 'anthropic', ?, 'global', 'v1', 'USD', 1.00, 10.00,
                                now() - interval '1 day', 'test fixture', now())
                        """).param(model).update();
        fixtures.sql("""
                        UPDATE ai_budget_policy SET superseded_at = now()
                        WHERE level = 'PLATFORM' AND superseded_at IS NULL
                        """).update();
        BigDecimal platformUsed = fixtures.sql("""
                        SELECT coalesce(sum(reserved_amount + spent_amount), 0) FROM ai_budget_balance
                        WHERE level = 'PLATFORM' AND period_type = 'MONTHLY'
                          AND period_start <= now() AND period_end > now()
                        """).query(BigDecimal.class).single();
        policy(null, platformUsed.add(new BigDecimal("1000")));
        policy(store, new BigDecimal("10"));
        operatorUser = user("operator");
        memberUser = user("member");
        fixtures.sql("""
                        INSERT INTO platform_operator (user_id, granted_by, reason) VALUES (?, 'ops:test', 'test')
                        """).param(operatorUser).update();
    }

    @Test
    void theProvidersRequestIdIsKeptOnAnUnknownCall() {
        UUID id = sent("rid-1");
        inScope(() -> {
            ledger.markUnknown(scope(), id, "MODEL_FAILURE:UNAVAILABLE", "req_first");
            ledger.markUnknown(scope(), id, "MODEL_FAILURE:UNAVAILABLE", "req_second");
            return null;
        });
        assertThat(fixtures.sql("SELECT state || ':' || provider_request_id FROM ai_budget_reservation WHERE id = ?")
                .param(id).query(String.class).single()).isEqualTo("UNKNOWN:req_first");
    }

    @Test
    void onlyAPlatformOperatorSeesAndClosesUncertainCallsAndEachDecisionIsAudited() {
        UUID id = unknown("op-1", "req_op_1");
        assertThat(operators.find(memberUser)).isEmpty();
        PlatformOperator operator = operators.require(operatorUser);

        assertThat(reconciliation.uncertain(operator, 1000)).filteredOn(r -> r.storeId().equals(store))
                .extracting(UncertainReservation::reservationId, UncertainReservation::state,
                        UncertainReservation::providerRequestId, UncertainReservation::provider)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(id, "UNKNOWN", "req_op_1", "anthropic"));

        Settlement settlement = reconciliation.reconcile(operator, id, ReconciliationOutcome.NOT_CHARGED, null,
                "No request in the provider console");
        assertThat(settlement.outcome()).isEqualTo("RECONCILED_NOT_CHARGED");
        assertThat(storeBalance()).isEqualTo("reserved=0.00000000 spent=0.00000000");
        assertThat(fixtures.sql("SELECT recorded_by FROM ai_usage_event WHERE reservation_id = ?").param(id)
                .query(String.class).single()).isEqualTo("PLATFORM_OPERATOR:" + operatorUser);
        assertThat(audit(id)).containsExactly("AI_RESERVATION_RECONCILED:" + operatorUser + ":RECONCILED_NOT_CHARGED");

        assertThat(reconciliation.reconcile(operator, id, ReconciliationOutcome.NOT_CHARGED, null,
                "No request in the provider console").repeated()).isTrue();
        assertThat(audit(id)).as("a repeat records nothing").hasSize(1);
        assertThatThrownBy(() -> reconciliation.reconcile(operator, id, ReconciliationOutcome.CONSERVATIVE, null,
                "changed my mind"))
                .isInstanceOf(AiReconciliationException.class)
                .extracting(e -> ((AiReconciliationException) e).kind())
                .isEqualTo(AiReconciliationException.Kind.CONFLICT);
        assertThatThrownBy(() -> reconciliation.reconcile(operator, UUID.randomUUID(),
                ReconciliationOutcome.NOT_CHARGED, null, "x"))
                .isInstanceOf(AiReconciliationException.class)
                .extracting(e -> ((AiReconciliationException) e).kind())
                .isEqualTo(AiReconciliationException.Kind.NOT_FOUND);
    }

    @Test
    void aRevokedOperatorCanNoLongerDecide() {
        UUID id = unknown("rev-1", null);
        PlatformOperator operator = operators.require(operatorUser);
        fixtures.sql("UPDATE platform_operator SET revoked_at = now() WHERE user_id = ?").param(operatorUser)
                .update();

        assertThatThrownBy(() -> reconciliation.reconcile(operator, id, ReconciliationOutcome.NOT_CHARGED, null,
                "x")).isInstanceOf(PlatformOperatorDeniedException.class);
        assertThatThrownBy(() -> reconciliation.uncertain(operator, 10))
                .isInstanceOf(PlatformOperatorDeniedException.class);
        assertThat(state(id)).isEqualTo("UNKNOWN");
    }

    @Test
    void aConservativeReconciliationIsCorrectedByAnAdjustmentAndTheUsageRowStays() {
        UUID id = unknown("cons-1", "req_cons_1");
        fixtures.sql("UPDATE ai_budget_reservation SET unknown_at = now() - interval '25 hours' WHERE id = ?")
                .param(id).update();
        sweeper.sweep();
        assertThat(storeBalance()).isEqualTo("reserved=0.00000000 spent=0.13500000");
        PlatformOperator operator = operators.require(operatorUser);
        assertThat(reconciliation.uncertain(operator, 1000)).filteredOn(r -> r.reservationId().equals(id))
                .extracting(UncertainReservation::state).containsExactly("RECONCILED_CONSERVATIVE");

        Adjustment adjustment = reconciliation.correct(operator, id, ReconciliationOutcome.CHARGED,
                new BigDecimal("0.02"), "Invoice line for req_cons_1");

        assertThat(adjustment.outcome()).isEqualTo("CORRECTED_CHARGED");
        assertThat(adjustment.cost()).isEqualByComparingTo("0.02");
        assertThat(adjustment.delta()).isEqualByComparingTo("-0.115");
        assertThat(storeBalance()).isEqualTo("reserved=0.00000000 spent=0.02000000");
        assertThat(fixtures.sql("SELECT outcome || ':' || cost FROM ai_usage_event WHERE reservation_id = ?")
                .param(id).query(String.class).single())
                .as("the usage ledger row is never changed").isEqualTo("RECONCILED_CONSERVATIVE:0.13500000");
        assertThat(fixtures.sql("""
                        SELECT outcome || ':' || previous_cost || ':' || corrected_cost || ':' || delta || ':'
                               || recorded_by
                        FROM ai_usage_adjustment WHERE reservation_id = ?
                        """).param(id).query(String.class).single())
                .isEqualTo("CORRECTED_CHARGED:0.13500000:0.02000000:-0.11500000:PLATFORM_OPERATOR:" + operatorUser);
        assertThat(audit(id)).containsExactly("AI_USAGE_CORRECTED:" + operatorUser + ":CORRECTED_CHARGED");
        assertThat(reconciliation.uncertain(operator, 1000)).extracting(UncertainReservation::reservationId)
                .doesNotContain(id);

        assertThat(reconciliation.correct(operator, id, ReconciliationOutcome.CHARGED, new BigDecimal("0.02"),
                "Invoice line for req_cons_1").repeated()).isTrue();
        assertThat(storeBalance()).as("a repeat moves nothing").isEqualTo("reserved=0.00000000 spent=0.02000000");
        assertThatThrownBy(() -> reconciliation.correct(operator, id, ReconciliationOutcome.NOT_CHARGED, null,
                "second thoughts"))
                .isInstanceOf(AiReconciliationException.class)
                .extracting(e -> ((AiReconciliationException) e).kind())
                .isEqualTo(AiReconciliationException.Kind.CONFLICT);
    }

    @Test
    void onlyAConservativeReconciliationCanBeCorrected() {
        UUID unknown = unknown("corr-1", null);
        UUID settled = sent("corr-2");
        inScope(() -> ledger.settle(scope(), settled, ProviderUsage.tokens("msg_corr_2", 40_000, 500)));
        PlatformOperator operator = operators.require(operatorUser);

        for (UUID id : List.of(unknown, settled)) {
            assertThatThrownBy(() -> reconciliation.correct(operator, id, ReconciliationOutcome.NOT_CHARGED, null,
                    "x"))
                    .isInstanceOf(AiReconciliationException.class)
                    .extracting(e -> ((AiReconciliationException) e).kind())
                    .isEqualTo(AiReconciliationException.Kind.CONFLICT);
        }
        assertThatThrownBy(() -> reconciliation.correct(operator, unknown, ReconciliationOutcome.CONSERVATIVE, null,
                "x")).isInstanceOf(AiReconciliationException.class);
    }

    @Test
    void theProviderSourceClosesOnlyCallsItMatchesByRequestId() {
        UUID charged = unknown("src-1", "req_src_1");
        UUID mismatch = unknown("src-2", "req_src_2");
        UUID notCharged = unknown("src-3", "req_src_3");
        UUID unconfirmed = unknown("src-4", "req_src_4");
        UUID withoutId = unknown("src-5", null);
        List<String> lookups = new ArrayList<>();
        ProviderUsageSource source = new ProviderUsageSource() {
            @Override
            public String provider() {
                return "anthropic";
            }

            @Override
            public Lookup lookup(String model, String providerRequestId) {
                lookups.add(providerRequestId);
                return switch (providerRequestId) {
                    case "req_src_1" -> new Lookup.Charged(ProviderUsage.tokens("req_src_1", 40_000, 500));
                    case "req_src_2" -> new Lookup.Charged(ProviderUsage.tokens("req_other", 40_000, 500));
                    case "req_src_3" -> new Lookup.NotCharged("request rejected before processing");
                    default -> new Lookup.Unconfirmed("not found yet");
                };
            }
        };
        AiUnknownReconciler reconciler = new AiUnknownReconciler(reconciliation, ledger, storeAccess,
                List.of(source), wideBatch(), new SimpleMeterRegistry(), Clock.systemUTC());

        reconciler.run();

        assertThat(lookups).contains("req_src_1", "req_src_2", "req_src_3", "req_src_4").doesNotContainNull();
        assertThat(state(charged)).isEqualTo("SETTLED");
        assertThat(fixtures.sql("SELECT outcome || ':' || cost FROM ai_usage_event WHERE reservation_id = ?")
                .param(charged).query(String.class).single()).isEqualTo("SETTLED:0.04500000");
        assertThat(state(mismatch)).as("an answer about another request is ignored").isEqualTo("UNKNOWN");
        assertThat(state(notCharged)).isEqualTo("RECONCILED");
        assertThat(fixtures.sql("SELECT outcome || ':' || recorded_by FROM ai_usage_event WHERE reservation_id = ?")
                .param(notCharged).query(String.class).single())
                .isEqualTo("RECONCILED_NOT_CHARGED:SYSTEM:ai-reconciliation");
        assertThat(state(unconfirmed)).isEqualTo("UNKNOWN");
        assertThat(state(withoutId)).isEqualTo("UNKNOWN");
        // Two calls still count in full; one was charged 0.045 and one returned.
        assertThat(storeBalance()).isEqualTo("reserved=0.40500000 spent=0.04500000");
    }

    @Test
    void withoutAProviderSourceNothingIsLookedUp() {
        UUID id = unknown("none-1", "req_none_1");
        AiUnknownReconciler reconciler = new AiUnknownReconciler(reconciliation, ledger, storeAccess, List.of(),
                settings, new SimpleMeterRegistry(), Clock.systemUTC());

        assertThat(reconciler.run()).isEqualTo(new AiUnknownReconciler.RunResult(0, 0, 0, false));
        assertThat(state(id)).isEqualTo("UNKNOWN");
    }

    @Test
    void theRuntimeCannotListOperatorsOrWriteAdjustments() {
        assertThat(jdbc.sql("INSERT INTO platform_operator (user_id, granted_by, reason) VALUES (?, 'me', 'me')")
                .param(memberUser)).satisfies(statement -> assertThatThrownBy(statement::update)
                .isInstanceOf(DataAccessException.class));
        assertThat(jdbc.sql("UPDATE platform_operator SET revoked_at = NULL").update()).isZero();
        assertThat(jdbc.sql("DELETE FROM platform_operator").update()).isZero();
        assertThat(fixtures.sql("SELECT count(*) FROM platform_operator WHERE user_id = ? AND revoked_at IS NULL")
                .param(operatorUser).query(Long.class).single()).isOne();

        UUID id = unknown("rls-1", null);
        inScope(() -> {
            assertThatThrownBy(() -> jdbc.sql("""
                            INSERT INTO ai_usage_adjustment (id, organization_id, store_id, reservation_id,
                                                             usage_event_id, outcome, currency, previous_cost,
                                                             corrected_cost, delta, recorded_by, reason)
                            VALUES (gen_random_uuid(), ?, ?, ?, gen_random_uuid(), 'CORRECTED_NOT_CHARGED', 'USD',
                                    0.135, 0, -0.135, 'me', 'me')
                            """).params(organization, store, id).update()).isInstanceOf(DataAccessException.class);
            return null;
        });
        // The correction function takes the store only from the bound connection.
        assertThatThrownBy(() -> jdbc.sql("SELECT * FROM ai_adjust_reconciliation(?::uuid, 'NOT_CHARGED', NULL, 'me', 'x')")
                .param(id).query(String.class).list())
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("AI_SCOPE_NOT_BOUND");
    }

    /** Other tests leave uncertain calls too; a batch that holds them all keeps this one deterministic. */
    private AiGovernanceSettings wideBatch() {
        return new AiGovernanceSettings(settings.inputTokenMargin(), settings.priceMaxVerificationAge(),
                settings.reservationTimeout(), settings.sweepInterval(), settings.sweepBatchSize(),
                settings.unknownConservativeSettlementEnabled(), settings.unknownConservativeSettlementAfter(),
                settings.maxConcurrentRuns(), settings.reconciliationInterval(), 1000, java.time.Duration.ofMinutes(2));
    }

    private UUID user(String name) {
        UUID id = UUID.randomUUID();
        fixtures.sql("INSERT INTO app_user (id, issuer, subject) VALUES (?, 'test', ?)").params(id, name + "-" + id)
                .update();
        return id;
    }

    private UUID sent(String key) {
        ReservationDecision decision = inScope(() -> governor.reserve(scope(), new ReservationRequest(key,
                "inventory", null, AiCallEstimate.of("anthropic", model, "global", 100_000, 1_000))));
        assertThat(decision).isInstanceOf(ReservationDecision.Reserved.class);
        UUID id = ((ReservationDecision.Reserved) decision).reservationId();
        assertThat(((ReservationDecision.Reserved) decision).amount()).isEqualByComparingTo(RESERVED);
        assertThat(inScope(() -> ledger.markSent(scope(), id))).isEqualTo(SendDecision.SEND);
        return id;
    }

    private UUID unknown(String key, String requestId) {
        UUID id = sent(key);
        inScope(() -> {
            ledger.markUnknown(scope(), id, "CONNECTION_RESET", requestId);
            return null;
        });
        return id;
    }

    private List<String> audit(UUID reservationId) {
        return fixtures.sql("""
                        SELECT action || ':' || actor_user_id || ':' || (details ->> 'outcome') FROM audit_event
                        WHERE resource_type = 'ai_budget_reservation' AND resource_id = ? AND store_id = ?
                        ORDER BY occurred_at
                        """).params(reservationId.toString(), store).query(String.class).list();
    }

    private String state(UUID id) {
        return fixtures.sql("SELECT state FROM ai_budget_reservation WHERE id = ?").param(id).query(String.class)
                .single();
    }

    private String storeBalance() {
        return fixtures.sql("""
                        SELECT 'reserved=' || reserved_amount || ' spent=' || spent_amount FROM ai_budget_balance
                        WHERE level = 'STORE' AND store_id = ? AND period_type = 'MONTHLY'
                          AND period_start <= now() AND period_end > now()
                        """).param(store).query(String.class).single();
    }

    private void policy(UUID storeId, BigDecimal limit) {
        String level = storeId == null ? "PLATFORM" : "STORE";
        UUID org = storeId == null ? null : organization;
        fixtures.sql("""
                        INSERT INTO ai_budget_policy (id, level, organization_id, store_id, period_type, amount_limit,
                                                      currency, policy_version, reason, created_by)
                        SELECT gen_random_uuid(), ?, ?::uuid, ?::uuid, 'MONTHLY', ?, 'USD',
                               coalesce(max(p.policy_version), 0) + 1, 'test', 'test'
                        FROM ai_budget_policy p
                        WHERE p.level = ? AND p.organization_id IS NOT DISTINCT FROM ?::uuid
                          AND p.store_id IS NOT DISTINCT FROM ?::uuid AND p.automation_type IS NULL
                          AND p.period_type = 'MONTHLY'
                        """).params(level, org, storeId, limit, level, org, storeId).update();
    }

    private TrustedStoreScope scope() {
        return TestScopes.system(organization, store);
    }

    private <T> T inScope(Supplier<T> work) {
        try (StoreRowGuard.Binding binding = StoreRowGuard.bind(scope())) {
            return work.get();
        }
    }
}
