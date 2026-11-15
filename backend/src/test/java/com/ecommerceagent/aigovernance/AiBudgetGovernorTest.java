package com.ecommerceagent.aigovernance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerceagent.FixtureDb;
import com.ecommerceagent.TestcontainersConfiguration;
import com.ecommerceagent.identity.StoreRowGuard;
import com.ecommerceagent.identity.TestScopes;
import com.ecommerceagent.identity.TrustedStoreScope;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * ADR-0027 stage 1, step 1: budgets, the price catalog and atomic reservations on a real PostgreSQL, through the
 * application's runtime role. Fixtures (prices, policies) are written as the superuser, as an operator would.
 *
 * <p>Every call in these tests costs 0.125 USD: 100,000 estimated input tokens plus the 25% margin at 1.00 USD per
 * million, and a single output token at a zero output price.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
class AiBudgetGovernorTest {

    private static final BigDecimal CALL = new BigDecimal("0.125");

    @Autowired
    AiBudgetGovernor governor;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    FixtureDb fixtures;

    UUID organization;
    UUID storeA;
    UUID storeB;
    String model;
    UUID priceId;

    @BeforeEach
    void setUp() {
        organization = UUID.randomUUID();
        storeA = UUID.randomUUID();
        storeB = UUID.randomUUID();
        fixtures.sql("INSERT INTO organization (id, name) VALUES (?, 'AI budgets')").param(organization).update();
        fixtures.sql("""
                        INSERT INTO store (id, organization_id, name, currency, timezone)
                        VALUES (?, ?, 'A', 'PLN', 'UTC'), (?, ?, 'B', 'PLN', 'UTC')
                        """).params(storeA, organization, storeB, organization).update();
        model = "test-model-" + UUID.randomUUID();
        priceId = price("v1", "1.00", Instant.now().minus(1, ChronoUnit.DAYS), Instant.now());
        // One platform budget, replaced per test, large enough for it and above whatever earlier tests spent.
        supersede("PLATFORM", null);
        policy("PLATFORM", null, null, null, "MONTHLY", platformUsed().add(new BigDecimal("1000")));
        policy("STORE", organization, storeA, null, "MONTHLY", new BigDecimal("10"));
        policy("STORE", organization, storeB, null, "MONTHLY", new BigDecimal("10"));
    }

    @Test
    void reservesTheConservativeMaximumAgainstPlatformAndStoreBudgets() {
        ReservationDecision.Reserved reserved = reserved(reserve(storeA, "call-1", "inventory"));

        assertThat(reserved.amount()).isEqualByComparingTo(CALL);
        assertThat(reserved.state()).isEqualTo("RESERVED");
        assertThat(reserved.repeated()).isFalse();
        assertThat(reserved.priceId()).isEqualTo(priceId);
        assertThat(reserved.maxInputTokens()).isEqualTo(125_000);
        assertThat(storeReserved(storeA)).isEqualByComparingTo(CALL);
        assertThat(fixtures.sql("SELECT count(*) FROM ai_budget_charge WHERE reservation_id = ?")
                .param(reserved.reservationId()).query(Long.class).single()).isEqualTo(2);
        assertThat(fixtures.sql("""
                        SELECT state || ':' || max_input_tokens || ':' || max_output_tokens || ':' || currency
                        FROM ai_budget_reservation WHERE id = ?
                        """).param(reserved.reservationId()).query(String.class).single())
                .isEqualTo("RESERVED:125000:1:USD");
    }

    @Test
    void noPlatformOrStoreBudgetMeansNoPaidCall() {
        UUID storeC = UUID.randomUUID();
        fixtures.sql("INSERT INTO store (id, organization_id, name, currency, timezone) VALUES (?, ?, 'C', 'PLN', 'UTC')")
                .params(storeC, organization).update();
        assertThat(reserve(storeC, "c-1", "inventory")).isEqualTo(new ReservationDecision.Denied("NO_STORE_BUDGET"));

        fixtures.sql("UPDATE ai_budget_policy SET superseded_at = now() WHERE level = 'PLATFORM' AND superseded_at IS NULL")
                .update();
        assertThat(reserve(storeA, "a-1", "inventory"))
                .isEqualTo(new ReservationDecision.Denied("NO_PLATFORM_BUDGET"));
        assertThat(storeReserved(storeA)).isEqualByComparingTo("0");
    }

    @Test
    void anExhaustedStoreBudgetBlocksTheNextCall() {
        supersede("STORE", storeA);
        policy("STORE", organization, storeA, null, "MONTHLY", new BigDecimal("0.20"));

        reserved(reserve(storeA, "a-1", "inventory"));
        assertThat(reserve(storeA, "a-2", "inventory"))
                .isEqualTo(new ReservationDecision.Denied("BUDGET_EXHAUSTED:STORE:MONTHLY"));
        assertThat(storeReserved(storeA)).isEqualByComparingTo(CALL);
    }

    @Test
    void anExhaustedOrganizationBudgetBlocksAllItsStores() {
        policy("ORGANIZATION", organization, null, null, "MONTHLY", new BigDecimal("0.20"));

        reserved(reserve(storeA, "a-1", "inventory"));
        assertThat(reserve(storeB, "b-1", "inventory"))
                .isEqualTo(new ReservationDecision.Denied("BUDGET_EXHAUSTED:ORGANIZATION:MONTHLY"));
        assertThat(storeReserved(storeB)).isEqualByComparingTo("0");
    }

    @Test
    void anExhaustedPlatformBudgetBlocksEveryTenant() {
        supersede("PLATFORM", null);
        policy("PLATFORM", null, null, null, "MONTHLY", platformUsed().add(new BigDecimal("0.10")));

        assertThat(reserve(storeA, "a-1", "inventory"))
                .isEqualTo(new ReservationDecision.Denied("BUDGET_EXHAUSTED:PLATFORM:MONTHLY"));
        assertThat(reserve(storeB, "b-1", "inventory"))
                .isEqualTo(new ReservationDecision.Denied("BUDGET_EXHAUSTED:PLATFORM:MONTHLY"));
    }

    @Test
    void anAutomationBudgetAndADailyLimitApplyOnlyWhereTheyBelong() {
        policy("AUTOMATION", organization, storeA, "marketing-diagnosis", "MONTHLY", new BigDecimal("0.10"));
        assertThat(reserve(storeA, "m-1", "marketing-diagnosis"))
                .isEqualTo(new ReservationDecision.Denied("BUDGET_EXHAUSTED:AUTOMATION:MONTHLY"));
        reserved(reserve(storeA, "i-1", "inventory"));

        policy("STORE", organization, storeB, null, "DAILY", new BigDecimal("0.20"));
        reserved(reserve(storeB, "b-1", "inventory"));
        assertThat(reserve(storeB, "b-2", "inventory"))
                .isEqualTo(new ReservationDecision.Denied("BUDGET_EXHAUSTED:STORE:DAILY"));
    }

    @Test
    void parallelReservationsNeverOverspend() throws Exception {
        supersede("STORE", storeA);
        policy("STORE", organization, storeA, null, "MONTHLY", new BigDecimal("1.00"));
        BigDecimal platformBefore = platformUsed();

        List<ReservationDecision> decisions = parallel(24, i -> reserve(storeA, "p-" + i, "inventory"));

        assertThat(decisions.stream().filter(ReservationDecision.Reserved.class::isInstance)).hasSize(8);
        assertThat(decisions.stream().filter(ReservationDecision.Denied.class::isInstance)).hasSize(16)
                .allMatch(d -> ((ReservationDecision.Denied) d).reason().equals("BUDGET_EXHAUSTED:STORE:MONTHLY"));
        assertThat(storeReserved(storeA)).isEqualByComparingTo("1.00");
        assertThat(platformUsed().subtract(platformBefore)).isEqualByComparingTo("1.00");
    }

    @Test
    void theSameCommandChargesTheBudgetOnceEvenInParallel() throws Exception {
        ReservationDecision.Reserved first = reserved(reserve(storeA, "same", "inventory"));
        ReservationDecision.Reserved again = reserved(reserve(storeA, "same", "inventory"));
        assertThat(again.reservationId()).isEqualTo(first.reservationId());
        assertThat(again.repeated()).isTrue();
        assertThat(storeReserved(storeA)).isEqualByComparingTo(CALL);

        List<ReservationDecision> decisions = parallel(12, i -> reserve(storeB, "same-parallel", "inventory"));
        assertThat(decisions).allMatch(ReservationDecision.Reserved.class::isInstance);
        assertThat(decisions.stream().map(d -> ((ReservationDecision.Reserved) d).reservationId()).distinct())
                .hasSize(1);
        assertThat(storeReserved(storeB)).isEqualByComparingTo(CALL);
        assertThat(fixtures.sql("SELECT count(*) FROM ai_budget_reservation WHERE store_id = ?").param(storeB)
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void aStricterPolicyBlocksTheNextCallAndKeepsTheHistory() {
        reserved(reserve(storeA, "a-1", "inventory"));
        supersede("STORE", storeA);
        policy("STORE", organization, storeA, null, "MONTHLY", new BigDecimal("0.20"));

        assertThat(reserve(storeA, "a-2", "inventory"))
                .isEqualTo(new ReservationDecision.Denied("BUDGET_EXHAUSTED:STORE:MONTHLY"));
        assertThat(fixtures.sql("SELECT count(*) FROM ai_budget_policy WHERE store_id = ? AND level = 'STORE'")
                .param(storeA).query(Long.class).single()).as("both versions kept").isEqualTo(2);
        assertThatThrownBy(() -> fixtures.sql("UPDATE ai_budget_policy SET amount_limit = 99 WHERE store_id = ?")
                .param(storeA).update()).isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("immutable");
    }

    @Test
    void theBudgetStartsAgainInANewPeriod() {
        Instant now = Instant.now();
        assertThat(window("MONTHLY", "2026-11-30T23:59:59Z")).isEqualTo("2026-11-01T00:00Z/2026-12-01T00:00Z");
        assertThat(window("MONTHLY", "2026-12-01T00:00:00Z")).isEqualTo("2026-12-01T00:00Z/2027-01-01T00:00Z");
        assertThat(window("DAILY", "2026-11-15T23:30:00+01:00")).isEqualTo("2026-11-15T00:00Z/2026-11-16T00:00Z");

        // Last month's store balance was spent to the limit; it does not count in this month.
        String scopeKey = "STORE:" + organization + ":" + storeA + ":-";
        Timestamp lastMonthStart = fixtures.sql("""
                        SELECT (date_trunc('month', now() AT TIME ZONE 'UTC') - interval '1 month') AT TIME ZONE 'UTC'
                        """).query(Timestamp.class).single();
        fixtures.sql("""
                        INSERT INTO ai_budget_balance (id, level, scope_key, organization_id, store_id, period_type,
                                                       period_start, period_end, currency, spent_amount)
                        VALUES (gen_random_uuid(), 'STORE', ?, ?, ?, 'MONTHLY', ?, ?::timestamptz + interval '1 month', 'USD', 10)
                        """).params(scopeKey, organization, storeA, lastMonthStart, lastMonthStart).update();

        reserved(reserve(storeA, "a-1", "inventory"));
        assertThat(fixtures.sql("""
                        SELECT reserved_amount FROM ai_budget_balance
                        WHERE scope_key = ? AND period_start <= ? AND period_end > ?
                        """).params(scopeKey, Timestamp.from(now), Timestamp.from(now))
                .query(BigDecimal.class).single()).isEqualByComparingTo(CALL);
    }

    @Test
    void releaseReturnsTheBudgetOnceAndOnlyForACallNeverSent() {
        ReservationDecision.Reserved reserved = reserved(reserve(storeA, "a-1", "inventory"));
        BigDecimal platformBefore = platformUsed();

        inScope(storeA, () -> {
            governor.release(scope(storeA), reserved.reservationId(), "deadline passed before sending");
            governor.release(scope(storeA), reserved.reservationId(), "repeated");
            return null;
        });
        assertThat(storeReserved(storeA)).isEqualByComparingTo("0");
        assertThat(platformBefore.subtract(platformUsed())).isEqualByComparingTo(CALL);
        assertThat(reservationState(reserved.reservationId())).isEqualTo("RELEASED");

        ReservationDecision.Reserved sent = reserved(reserve(storeA, "a-2", "inventory"));
        fixtures.sql("UPDATE ai_budget_reservation SET state = 'IN_PROGRESS' WHERE id = ?")
                .param(sent.reservationId()).update();
        assertThatThrownBy(() -> inScope(storeA, () -> {
            governor.release(scope(storeA), sent.reservationId(), "maybe sent");
            return null;
        })).isInstanceOf(DataAccessException.class).hasMessageContaining("AI_RESERVATION_NOT_RELEASABLE");
        assertThat(storeReserved(storeA)).isEqualByComparingTo(CALL);
    }

    @Test
    void aStoreCannotTouchAnotherStoresReservation() {
        ReservationDecision.Reserved reserved = reserved(reserve(storeA, "a-1", "inventory"));

        assertThatThrownBy(() -> inScope(storeB, () -> {
            governor.release(scope(storeB), reserved.reservationId(), "not mine");
            return null;
        })).isInstanceOf(DataAccessException.class).hasMessageContaining("AI_RESERVATION_NOT_FOUND");
        assertThat(inScope(storeB, () -> jdbc.sql("SELECT count(*) FROM ai_budget_reservation")
                .query(Long.class).single())).isZero();
        assertThat(reservationState(reserved.reservationId())).isEqualTo("RESERVED");
    }

    @Test
    void governanceIsBoundToTheScopeItIsGivenAndFailsClosedWithoutOne() {
        AiCallEstimate estimate = estimate();
        // The proxy binds the scope the governor receives (ADR-0022 point 3), so the reservation belongs to that store.
        ReservationDecision.Reserved own = reserved(governor.reserve(scope(storeA),
                new ReservationRequest("self-bound", "inventory", null, estimate)));
        assertThat(fixtures.sql("SELECT store_id FROM ai_budget_reservation WHERE id = ?")
                .param(own.reservationId()).query(UUID.class).single()).isEqualTo(storeA);
        // Another store's scope while a store is bound is refused before any SQL runs.
        assertThatThrownBy(() -> inScope(storeB, () -> governor.reserve(scope(storeA),
                new ReservationRequest("mismatch", "inventory", null, estimate))))
                .isInstanceOf(IllegalStateException.class);
        // The function itself refuses an unbound connection: it takes the store only from the session settings.
        assertThatThrownBy(() -> jdbc.sql("""
                        SELECT outcome FROM ai_reserve('raw', 'inventory', NULL, ?::uuid, 1, 1, 1, 'USD', now() + interval '1 hour')
                        """).param(priceId).query(String.class).single())
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("AI_SCOPE_NOT_BOUND");
        assertThatThrownBy(() -> jdbc.sql("SELECT ai_release(?::uuid, 'raw')").param(own.reservationId())
                .query(String.class).single())
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("AI_SCOPE_NOT_BOUND");
        assertThat(fixtures.sql("SELECT count(*) FROM ai_budget_reservation WHERE organization_id = ?")
                .param(organization).query(Long.class).single()).isOne();
    }

    @Test
    void theRuntimeCannotWriteBudgetsOrReadPlatformEconomics() {
        reserved(reserve(storeA, "a-1", "inventory"));
        inScope(storeA, () -> {
            assertThat(jdbc.sql("SELECT count(*) FROM ai_budget_balance").query(Long.class).single()).isZero();
            assertThat(jdbc.sql("SELECT count(*) FROM ai_budget_policy").query(Long.class).single()).isZero();
            assertThat(jdbc.sql("SELECT count(*) FROM ai_budget_charge").query(Long.class).single()).isZero();
            assertThat(jdbc.sql("SELECT count(*) FROM ai_budget_reservation").query(Long.class).single()).isOne();
            assertThat(jdbc.sql("UPDATE ai_budget_reservation SET reserved_amount = 0.00000001")
                    .update()).as("no update policy").isZero();
            assertThat(jdbc.sql("UPDATE ai_price SET verified_at = now()").update()).isZero();
            assertThatThrownBy(() -> jdbc.sql("""
                            INSERT INTO ai_budget_policy (id, level, organization_id, store_id, period_type,
                                                          amount_limit, currency, policy_version, reason, created_by)
                            VALUES (gen_random_uuid(), 'STORE', ?, ?, 'MONTHLY', 1000, 'USD', 99, 'raise', 'me')
                            """).params(organization, storeA).update())
                    .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("row-level security");
            assertThatThrownBy(() -> jdbc.sql("""
                            INSERT INTO ai_price (id, provider, model, region, price_version, currency, input_per_mtok,
                                                  output_per_mtok, effective_from, source, verified_at)
                            VALUES (gen_random_uuid(), 'anthropic', ?, 'global', 'cheap', 'USD', 0, 0, now(), 'me', now())
                            """).param(model).update())
                    .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("row-level security");
            return null;
        });
        assertThat(storeReserved(storeA)).isEqualByComparingTo(CALL);
    }

    @Test
    void onlyAVerifiedRecentCurrentPriceAdmitsACall() {
        String unpriced = "unpriced-" + UUID.randomUUID();
        assertThat(inScope(storeA, () -> governor.reserve(scope(storeA), new ReservationRequest("n-1", "inventory",
                null, AiCallEstimate.of("anthropic", unpriced, "global", 100_000, 1)))))
                .isEqualTo(new ReservationDecision.Denied("NOT_PRICED"));

        // A newer version takes over once it is in effect; an unverified one is never used.
        price("v2", "2.00", Instant.now().minus(1, ChronoUnit.HOURS), null);
        assertThat(reserve(storeA, "u-1", "inventory")).isEqualTo(new ReservationDecision.Denied("PRICE_UNVERIFIED"));
        UUID v3 = price("v3", "2.00", Instant.now().minus(30, ChronoUnit.MINUTES), Instant.now());
        ReservationDecision.Reserved reserved = reserved(reserve(storeA, "v-1", "inventory"));
        assertThat(reserved.priceId()).isEqualTo(v3);
        assertThat(reserved.priceVersion()).isEqualTo("v3");
        assertThat(reserved.amount()).isEqualByComparingTo("0.25");
        // No cache prices on v3: a call that may write the cache has no upper bound, so it is refused.
        assertThat(inScope(storeA, () -> governor.reserve(scope(storeA), new ReservationRequest("c-1", "inventory",
                null, new AiCallEstimate("anthropic", model, "global", 100_000, 1, true, Map.of())))))
                .isEqualTo(new ReservationDecision.Denied("CACHE_NOT_PRICED"));

        price("v4", "1.00", Instant.now().minus(10, ChronoUnit.MINUTES), Instant.now().minus(91, ChronoUnit.DAYS));
        assertThat(reserve(storeA, "s-1", "inventory")).isEqualTo(new ReservationDecision.Denied("PRICE_STALE"));

        assertThatThrownBy(() -> fixtures.sql("UPDATE ai_price SET input_per_mtok = 0 WHERE id = ?").param(v3).update())
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("append-only");
    }

    private ReservationDecision reserve(UUID store, String key, String automation) {
        return inScope(store, () -> governor.reserve(scope(store), new ReservationRequest(key, automation, null,
                estimate())));
    }

    private AiCallEstimate estimate() {
        return AiCallEstimate.of("anthropic", model, "global", 100_000, 1);
    }

    private TrustedStoreScope scope(UUID store) {
        return TestScopes.system(organization, store);
    }

    private <T> T inScope(UUID store, Supplier<T> work) {
        try (StoreRowGuard.Binding ignored = StoreRowGuard.bind(scope(store))) {
            return work.get();
        }
    }

    private List<ReservationDecision> parallel(int calls, java.util.function.IntFunction<ReservationDecision> call)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<ReservationDecision>> futures = new ArrayList<>();
            for (int i = 0; i < calls; i++) {
                int n = i;
                Callable<ReservationDecision> task = () -> {
                    start.await();
                    return call.apply(n);
                };
                futures.add(pool.submit(task));
            }
            start.countDown();
            List<ReservationDecision> decisions = new ArrayList<>();
            for (Future<ReservationDecision> future : futures) {
                decisions.add(future.get());
            }
            return decisions;
        } finally {
            pool.shutdownNow();
        }
    }

    private static ReservationDecision.Reserved reserved(ReservationDecision decision) {
        assertThat(decision).isInstanceOf(ReservationDecision.Reserved.class);
        return (ReservationDecision.Reserved) decision;
    }

    private UUID price(String version, String inputPerMillion, Instant effectiveFrom, Instant verifiedAt) {
        UUID id = UUID.randomUUID();
        fixtures.sql("""
                        INSERT INTO ai_price (id, provider, model, region, price_version, currency, input_per_mtok,
                                              output_per_mtok, effective_from, source, verified_at)
                        VALUES (?, 'anthropic', ?, 'global', ?, 'USD', ?, 0, ?, 'test fixture', ?)
                        """).params(id, model, version, new BigDecimal(inputPerMillion), Timestamp.from(effectiveFrom),
                        verifiedAt == null ? null : Timestamp.from(verifiedAt)).update();
        return id;
    }

    private void policy(String level, UUID org, UUID store, String automation, String period, BigDecimal limit) {
        fixtures.sql("""
                        INSERT INTO ai_budget_policy (id, level, organization_id, store_id, automation_type,
                                                      period_type, amount_limit, currency, policy_version, reason,
                                                      created_by)
                        SELECT gen_random_uuid(), ?, ?::uuid, ?::uuid, ?, ?, ?, 'USD',
                               coalesce(max(p.policy_version), 0) + 1, 'test', 'test'
                        FROM ai_budget_policy p
                        WHERE p.level = ? AND p.organization_id IS NOT DISTINCT FROM ?::uuid
                          AND p.store_id IS NOT DISTINCT FROM ?::uuid
                          AND p.automation_type IS NOT DISTINCT FROM ? AND p.period_type = ?
                        """).params(level, org, store, automation, period, limit, level, org, store, automation,
                        period).update();
    }

    private void supersede(String level, UUID store) {
        fixtures.sql("""
                        UPDATE ai_budget_policy SET superseded_at = now()
                        WHERE level = ? AND store_id IS NOT DISTINCT FROM ?::uuid AND superseded_at IS NULL
                        """).params(level, store).update();
    }

    private BigDecimal storeReserved(UUID store) {
        return fixtures.sql("""
                        SELECT coalesce(sum(reserved_amount), 0) FROM ai_budget_balance
                        WHERE level = 'STORE' AND store_id = ? AND period_start <= now() AND period_end > now()
                        """).param(store).query(BigDecimal.class).single();
    }

    private BigDecimal platformUsed() {
        return fixtures.sql("""
                        SELECT coalesce(sum(reserved_amount + spent_amount), 0) FROM ai_budget_balance
                        WHERE level = 'PLATFORM' AND period_type = 'MONTHLY'
                          AND period_start <= now() AND period_end > now()
                        """).query(BigDecimal.class).single();
    }

    private String reservationState(UUID id) {
        return fixtures.sql("SELECT state FROM ai_budget_reservation WHERE id = ?").param(id).query(String.class)
                .single();
    }

    private String window(String period, String at) {
        return fixtures.sql("""
                        SELECT to_char(window_start AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI"Z"') || '/'
                            || to_char(window_end AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI"Z"')
                        FROM ai_budget_window(?, ?::timestamptz)
                        """).params(period, at).query(String.class).single();
    }
}
