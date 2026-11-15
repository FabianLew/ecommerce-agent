package com.ecommerceagent.aigovernance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import com.ecommerceagent.FixtureDb;
import com.ecommerceagent.TestcontainersConfiguration;
import com.ecommerceagent.identity.PlatformOperatorDeniedException;
import com.ecommerceagent.identity.PlatformOperators;
import com.ecommerceagent.identity.StoreRowGuard;
import com.ecommerceagent.identity.TestScopes;
import com.ecommerceagent.identity.TrustedStoreScope;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * ADR-0027 section 14 (E-54.7) on a real PostgreSQL: a budget that reaches 70, 90 or 100 percent of its limit is
 * recorded once per threshold and period, however often or in parallel the evaluation runs; only platform operators
 * read the alerts. Prices are those of {@link AiUsageLedgerTest}: each call reserves 0.135 USD; the store's limit is
 * 0.15 USD.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
class AiBudgetAlertsTest {

    @Autowired
    AiBudgetAlerts alerts;

    @Autowired
    AiBudgetGovernor governor;

    @Autowired
    AiUsageLedger ledger;

    @Autowired
    PlatformOperators operators;

    @Autowired
    MeterRegistry meters;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    FixtureDb fixtures;

    UUID organization;
    UUID store;
    String model;
    UUID operatorUser;

    @BeforeEach
    void setUp() {
        fixtures.registerAutomations("inventory");
        organization = UUID.randomUUID();
        store = UUID.randomUUID();
        fixtures.sql("INSERT INTO organization (id, name) VALUES (?, 'AI alerts')").param(organization).update();
        fixtures.sql("""
                        INSERT INTO store (id, organization_id, name, currency, timezone)
                        VALUES (?, ?, 'Alerts', 'EUR', 'Europe/Warsaw')
                        """).params(store, organization).update();
        model = "alert-model-" + UUID.randomUUID();
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
        fixtures.sql("""
                        INSERT INTO ai_budget_policy (id, level, organization_id, store_id, period_type, amount_limit,
                                                      currency, policy_version, reason, created_by)
                        SELECT gen_random_uuid(), 'PLATFORM', NULL, NULL, 'MONTHLY',
                               coalesce((SELECT sum(reserved_amount + spent_amount) FROM ai_budget_balance
                                         WHERE level = 'PLATFORM' AND period_type = 'MONTHLY'
                                           AND period_start <= now() AND period_end > now()), 0) + 1000,
                               'USD', coalesce(max(p.policy_version), 0) + 1, 'test', 'test'
                        FROM ai_budget_policy p WHERE p.level = 'PLATFORM' AND p.period_type = 'MONTHLY'
                        """).update();
        fixtures.sql("""
                        INSERT INTO ai_budget_policy (id, level, organization_id, store_id, period_type, amount_limit,
                                                      currency, policy_version, reason, created_by)
                        VALUES (gen_random_uuid(), 'STORE', ?, ?, 'MONTHLY', 0.15, 'USD', 1, 'test', 'test')
                        """).params(organization, store).update();
        operatorUser = UUID.randomUUID();
        fixtures.sql("INSERT INTO app_user (id, issuer, subject) VALUES (?, 'test', ?)")
                .params(operatorUser, "ops-" + operatorUser).update();
        fixtures.sql("INSERT INTO platform_operator (user_id, granted_by, reason) VALUES (?, 'ops:test', 'test')")
                .param(operatorUser).update();
    }

    @Test
    void eachThresholdIsRecordedOncePerPeriodAsTheBudgetFills() throws Exception {
        UUID first = sent("alert-1");

        // 0.135 of 0.15 reserved: 90 percent.
        assertThat(storeAlerts(alerts.evaluate())).extracting(AiBudgetAlert::thresholdPercent).containsExactly(70, 90);
        assertThat(storeAlerts(alerts.evaluate())).as("nothing twice").isEmpty();
        List<List<AiBudgetAlert>> parallel = parallel(4, () -> alerts.evaluate());
        assertThat(parallel).allSatisfy(list -> assertThat(storeAlerts(list)).isEmpty());

        // An overrun: 200,000 input and 1,000 output tokens cost 0.21, above the limit.
        inScope(() -> ledger.settle(scope(), first, ProviderUsage.tokens("msg_alert_1", 200_000, 1_000)));
        List<AiBudgetAlert> full = storeAlerts(alerts.evaluate());
        assertThat(full).singleElement().satisfies(alert -> {
            assertThat(alert.thresholdPercent()).isEqualTo(100);
            assertThat(alert.level()).isEqualTo("STORE");
            assertThat(alert.periodType()).isEqualTo("MONTHLY");
            assertThat(alert.amountLimit()).isEqualByComparingTo("0.15");
            assertThat(alert.usedAmount()).isEqualByComparingTo("0.21");
            assertThat(alert.currency()).as("the technical settlement currency, not the store's").isEqualTo("USD");
        });
        assertThat(fixtures.sql("""
                        SELECT threshold_percent || ':' || parameter_version FROM ai_budget_alert
                        WHERE store_id = ? ORDER BY threshold_percent
                        """).param(store).query(String.class).list())
                .containsExactly("70:ai-budget-alerts/1", "90:ai-budget-alerts/1", "100:ai-budget-alerts/1");
        assertThat(meters.find("operator.ai.budget.alerts").tag("level", "STORE").tag("threshold", "100").counter())
                .isNotNull();

        // The hard limit is enforced at reservation, whatever the alerts say.
        assertThat(inScope(() -> governor.reserve(scope(), request("alert-2"))))
                .isInstanceOf(ReservationDecision.Denied.class);
    }

    @Test
    void onlyPlatformOperatorsReadTheAlertsAndStoresNeverSeeThem() {
        sent("read-1");
        alerts.evaluate();

        var operator = operators.require(operatorUser);
        assertThat(alerts.recent(operator, null, 1000)).filteredOn(a -> store.equals(a.storeId()))
                .extracting(AiBudgetAlert::thresholdPercent).containsExactly(90, 70);
        assertThat(alerts.recent(operator, java.time.Instant.now().plusSeconds(60), 10)).isEmpty();

        inScope(() -> {
            assertThat(jdbc.sql("SELECT count(*) FROM ai_budget_alert WHERE store_id = ?").param(store)
                    .query(Long.class).single()).as("platform economics are not store data").isZero();
            return null;
        });
        assertThat(jdbc.sql("UPDATE ai_budget_alert SET threshold_percent = 1").update()).isZero();
        assertThatThrownBy(() -> fixtures.sql("DELETE FROM ai_budget_alert WHERE store_id = ?").param(store).update())
                .rootCause().hasMessageContaining("append-only");

        fixtures.sql("UPDATE platform_operator SET revoked_at = now() WHERE user_id = ?").param(operatorUser)
                .update();
        assertThatThrownBy(() -> alerts.recent(operator, null, 10))
                .isInstanceOf(PlatformOperatorDeniedException.class);
    }

    @Test
    void theThresholdsComeFromTheVersionedParameterSet() {
        assertThat(AiBudgetAlertSettings.defaults().thresholdPercents()).containsExactly(70, 90, 100);
        assertThat(new AiBudgetAlertSettings("ai-budget-alerts/2", List.of(100, 50, 50)).thresholdPercents())
                .containsExactly(50, 100);
        assertThatThrownBy(() -> new AiBudgetAlertSettings("ai-budget-alerts/2", List.of(0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiBudgetAlertSettings("alerts-2", List.of(70)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> jdbc.sql("SELECT * FROM ai_record_budget_alerts(ARRAY[0], 'x')").query(String.class)
                .list()).rootCause().hasMessageContaining("AI_BUDGET_ALERT_INVALID");
    }

    private List<AiBudgetAlert> storeAlerts(List<AiBudgetAlert> recorded) {
        return recorded.stream().filter(a -> store.equals(a.storeId())).toList();
    }

    private UUID sent(String key) {
        ReservationDecision decision = inScope(() -> governor.reserve(scope(), request(key)));
        assertThat(decision).isInstanceOf(ReservationDecision.Reserved.class);
        UUID id = ((ReservationDecision.Reserved) decision).reservationId();
        assertThat(inScope(() -> ledger.markSent(scope(), id))).isEqualTo(SendDecision.SEND);
        return id;
    }

    private ReservationRequest request(String key) {
        return new ReservationRequest(key, "inventory", null,
                AiCallEstimate.of("anthropic", model, "global", 100_000, 1_000));
    }

    private TrustedStoreScope scope() {
        return TestScopes.system(organization, store);
    }

    private <T> T inScope(Supplier<T> work) {
        try (StoreRowGuard.Binding binding = StoreRowGuard.bind(scope())) {
            return work.get();
        }
    }

    private static <T> List<T> parallel(int calls, java.util.concurrent.Callable<T> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < calls; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
