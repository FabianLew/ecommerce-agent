package com.ecommerceagent.aigovernance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

import com.ecommerceagent.FixtureDb;
import com.ecommerceagent.TestcontainersConfiguration;
import com.ecommerceagent.identity.StoreRowGuard;
import com.ecommerceagent.identity.TestScopes;
import com.ecommerceagent.identity.TrustedStoreScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * ADR-0027 stage 1, step 2: settlement, uncertain outcomes and the usage ledger on a real PostgreSQL, through the
 * runtime role. Each scenario drives a reservation the way a governed model call does (reserve, mark sent, then
 * settle or mark unknown) and breaks it where a worker, the network or a retry can break it.
 *
 * <p>Prices: 1.00 USD per million input tokens, 10.00 per million output tokens, cache writes 1.25 and cache reads
 * 0.10. The call estimate (100,000 input tokens plus the 25% margin, at most 1,000 output tokens) reserves 0.135.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
class AiUsageLedgerTest {

    private static final BigDecimal RESERVED = new BigDecimal("0.135");
    /** 40,000 input and 500 output tokens: 0.04 + 0.005. */
    private static final BigDecimal ACTUAL = new BigDecimal("0.045");

    @Autowired
    AiBudgetGovernor governor;

    @Autowired
    AiUsageLedger ledger;

    @Autowired
    AiReservationSweeper sweeper;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    FixtureDb fixtures;

    UUID organization;
    UUID storeA;
    UUID storeB;
    String model;

    @BeforeEach
    void setUp() {
        organization = UUID.randomUUID();
        storeA = UUID.randomUUID();
        storeB = UUID.randomUUID();
        fixtures.sql("INSERT INTO organization (id, name) VALUES (?, 'AI ledger')").param(organization).update();
        fixtures.sql("""
                        INSERT INTO store (id, organization_id, name, currency, timezone)
                        VALUES (?, ?, 'A', 'PLN', 'UTC'), (?, ?, 'B', 'PLN', 'UTC')
                        """).params(storeA, organization, storeB, organization).update();
        model = "ledger-model-" + UUID.randomUUID();
        fixtures.sql("""
                        INSERT INTO ai_price (id, provider, model, region, price_version, currency, input_per_mtok,
                                              output_per_mtok, cache_write_per_mtok, cache_read_per_mtok, extras,
                                              effective_from, source, verified_at)
                        VALUES (gen_random_uuid(), 'anthropic', ?, 'global', 'v1', 'USD', 1.00, 10.00, 1.25, 0.10,
                                '{"web_search": 0.01}', now() - interval '1 day', 'test fixture', now())
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
        policy("PLATFORM", null, null, platformUsed.add(new BigDecimal("1000")));
        policy("STORE", organization, storeA, new BigDecimal("10"));
        policy("STORE", organization, storeB, new BigDecimal("10"));
    }

    @Test
    void aSettledCallRecordsItsActualCostAndReturnsTheRestOfTheReservation() {
        UUID id = reserve(storeA, "ok-1");
        assertThat(inScope(storeA, () -> ledger.markSent(scope(storeA), id))).isEqualTo(SendDecision.SEND);
        assertThat(storeBalance(storeA)).isEqualTo("reserved=0.13500000 spent=0.00000000");

        Settlement settlement = inScope(storeA, () -> ledger.settle(scope(storeA), id,
                ProviderUsage.tokens("msg_ok_1", 40_000, 500)));

        assertThat(settlement.outcome()).isEqualTo("SETTLED");
        assertThat(settlement.cost()).isEqualByComparingTo(ACTUAL);
        assertThat(settlement.overran()).isFalse();
        assertThat(settlement.repeated()).isFalse();
        assertThat(storeBalance(storeA)).isEqualTo("reserved=0.00000000 spent=0.04500000");
        assertThat(state(id)).isEqualTo("SETTLED");
        assertThat(fixtures.sql("""
                        SELECT outcome || ':' || provider_call_id || ':' || input_tokens || ':' || output_tokens || ':'
                               || price_version || ':' || reserved_amount || ':' || recorded_by
                        FROM ai_usage_event WHERE reservation_id = ?
                        """).param(id).query(String.class).single())
                .isEqualTo("SETTLED:msg_ok_1:40000:500:v1:0.13500000:PROVIDER_USAGE");
    }

    @Test
    void cacheAndExtraUnitsArePricedAndUnpricedUsageIsNeverRecordedAsFree() {
        UUID id = sent(storeA, "cache-1");
        Settlement settlement = inScope(storeA, () -> ledger.settle(scope(storeA), id,
                new ProviderUsage("msg_cache_1", 10_000, 100, 20_000, 50_000, Map.of("web_search", 2L))));
        // 0.01 input + 0.001 output + 0.025 cache write + 0.005 cache read + 0.02 searches
        assertThat(settlement.cost()).isEqualByComparingTo("0.061");

        UUID unpriced = sent(storeA, "cache-2");
        assertThatThrownBy(() -> inScope(storeA, () -> ledger.settle(scope(storeA), unpriced,
                new ProviderUsage("msg_cache_2", 1, 1, 0, 0, Map.of("code_execution", 1L)))))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("AI_USAGE_NOT_PRICED");
        assertThat(state(unpriced)).as("left open for reconciliation, still counting").isEqualTo("IN_PROGRESS");
    }

    @Test
    void settlingTheSameUsageAgainChargesOnceEvenInParallel() throws Exception {
        UUID id = sent(storeA, "dup-1");
        ProviderUsage usage = ProviderUsage.tokens("msg_dup_1", 40_000, 500);

        List<Settlement> results = parallel(8, () -> inScope(storeA, () -> ledger.settle(scope(storeA), id, usage)));

        assertThat(results).extracting(Settlement::repeated).containsOnlyOnce(false);
        assertThat(results).allSatisfy(s -> assertThat(s.cost()).isEqualByComparingTo(ACTUAL));
        assertThat(storeBalance(storeA)).isEqualTo("reserved=0.00000000 spent=0.04500000");
        assertThat(fixtures.sql("SELECT count(*) FROM ai_usage_event WHERE reservation_id = ?").param(id)
                .query(Long.class).single()).isOne();
        // Different usage for a closed reservation is a conflict, not a second charge.
        assertThatThrownBy(() -> inScope(storeA, () -> ledger.settle(scope(storeA), id,
                ProviderUsage.tokens("msg_dup_1", 41_000, 500))))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("AI_SETTLEMENT_CONFLICT");
        assertThat(storeBalance(storeA)).isEqualTo("reserved=0.00000000 spent=0.04500000");
    }

    @Test
    void anOverrunIsRecordedInFullAndBlocksWhatTheBudgetNoLongerCovers() {
        policy("STORE", organization, storeA, new BigDecimal("0.40"));
        UUID id = sent(storeA, "over-1");

        Settlement settlement = inScope(storeA, () -> ledger.settle(scope(storeA), id,
                ProviderUsage.tokens("msg_over_1", 300_000, 1_000)));

        assertThat(settlement.cost()).isEqualByComparingTo("0.31");
        assertThat(settlement.overrun()).isEqualByComparingTo("0.175");
        assertThat(storeBalance(storeA)).isEqualTo("reserved=0.00000000 spent=0.31000000");
        assertThat(fixtures.sql("SELECT settled_amount || ':' || overrun_amount FROM ai_budget_reservation WHERE id = ?")
                .param(id).query(String.class).single()).isEqualTo("0.31000000:0.17500000");
        assertThat(jdbc.sql("SELECT operational_metric('operator.ai.overruns.recent')").query(Double.class).single())
                .isGreaterThanOrEqualTo(1.0);
        // 0.31 spent of 0.40: the next call's 0.135 no longer fits.
        assertThat(inScope(storeA, () -> governor.reserve(scope(storeA), request("over-2"))))
                .isEqualTo(new ReservationDecision.Denied("BUDGET_EXHAUSTED:STORE:MONTHLY"));
    }

    @Test
    void aWorkerThatDiesBeforeSendingLeavesAReservationThatIsReleasedAndNeverSent() {
        UUID id = reserve(storeA, "crash-before-1");
        expire(id);

        // The retried activity finds it expired: no request leaves.
        assertThat(inScope(storeA, () -> ledger.markSent(scope(storeA), id))).isEqualTo(SendDecision.EXPIRED);
        sweeper.sweep();

        assertThat(state(id)).isEqualTo("RELEASED");
        assertThat(storeBalance(storeA)).isEqualTo("reserved=0.00000000 spent=0.00000000");
        assertThat(inScope(storeA, () -> ledger.markSent(scope(storeA), id))).isEqualTo(SendDecision.RELEASED);
        assertThat(fixtures.sql("SELECT count(*) FROM ai_usage_event WHERE reservation_id = ?").param(id)
                .query(Long.class).single()).as("nothing was charged").isZero();
    }

    @Test
    void aWorkerThatDiesAfterSendingLeavesAnUnknownThatKeepsCountingUntilTheResultArrives() {
        UUID id = sent(storeA, "crash-after-1");
        expire(id);

        sweeper.sweep();

        assertThat(state(id)).isEqualTo("UNKNOWN");
        assertThat(storeBalance(storeA)).as("still reserved").isEqualTo("reserved=0.13500000 spent=0.00000000");
        assertThat(jdbc.sql("SELECT operational_metric('operator.ai.reservations.unknown')").query(Double.class)
                .single()).isGreaterThanOrEqualTo(1.0);
        assertThatThrownBy(() -> inScope(storeA, () -> {
            governor.release(scope(storeA), id, "try to give it back");
            return null;
        })).isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("AI_RESERVATION_NOT_RELEASABLE");

        // The provider's usage arrives later (a late answer, or the provider's usage report).
        Settlement late = inScope(storeA, () -> ledger.settle(scope(storeA), id,
                ProviderUsage.tokens("msg_late_1", 40_000, 500)));
        assertThat(late.cost()).isEqualByComparingTo(ACTUAL);
        assertThat(state(id)).isEqualTo("SETTLED");
        assertThat(storeBalance(storeA)).isEqualTo("reserved=0.00000000 spent=0.04500000");
    }

    @Test
    void aTimeoutOrALostResponseIsUnknownNotFreeAndNotRetried() {
        UUID timedOut = sent(storeA, "timeout-1");
        inScope(storeA, () -> {
            ledger.markUnknown(scope(storeA), timedOut, "READ_TIMEOUT");
            ledger.markUnknown(scope(storeA), timedOut, "READ_TIMEOUT");
            return null;
        });
        assertThat(state(timedOut)).isEqualTo("UNKNOWN");
        assertThat(storeBalance(storeA)).isEqualTo("reserved=0.13500000 spent=0.00000000");

        // The activity is retried with the same command: the first reservation comes back, and it may not be sent.
        ReservationDecision.Reserved again = reserved(inScope(storeA,
                () -> governor.reserve(scope(storeA), request("timeout-1"))));
        assertThat(again.reservationId()).isEqualTo(timedOut);
        assertThat(again.repeated()).isTrue();
        assertThat(again.state()).isEqualTo("UNKNOWN");
        assertThat(inScope(storeA, () -> ledger.markSent(scope(storeA), timedOut)))
                .isEqualTo(SendDecision.ALREADY_ATTEMPTED);
        assertThat(storeBalance(storeA)).as("not charged twice").isEqualTo("reserved=0.13500000 spent=0.00000000");

        // A call that was never sent is not uncertain.
        UUID unsent = reserve(storeA, "timeout-2");
        assertThatThrownBy(() -> inScope(storeA, () -> {
            ledger.markUnknown(scope(storeA), unsent, "READ_TIMEOUT");
            return null;
        })).isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("AI_RESERVATION_NOT_SENT");
    }

    @Test
    void parallelAttemptsSendACallAtMostOnce() throws Exception {
        UUID id = reserve(storeA, "race-1");

        List<SendDecision> decisions = parallel(10, () -> inScope(storeA, () -> ledger.markSent(scope(storeA), id)));

        assertThat(decisions).containsOnlyOnce(SendDecision.SEND);
        assertThat(decisions).filteredOn(d -> d != SendDecision.SEND).containsOnly(SendDecision.ALREADY_ATTEMPTED);
        assertThat(state(id)).isEqualTo("IN_PROGRESS");
    }

    @Test
    void aPersonReconcilesAnUnknownWithTheProvidersAnswerAndIsRecorded() {
        TrustedStoreScope operator = TestScopes.system(organization, storeA, "ai-cost-operator");

        UUID notCharged = unknown(storeA, "rec-1");
        Settlement freed = inScope(storeA, () -> ledger.reconcile(operator, notCharged,
                ReconciliationOutcome.NOT_CHARGED, null, "Provider console shows no request"));
        assertThat(freed.outcome()).isEqualTo("RECONCILED_NOT_CHARGED");
        assertThat(freed.cost()).isEqualByComparingTo("0");
        assertThat(storeBalance(storeA)).isEqualTo("reserved=0.00000000 spent=0.00000000");

        UUID charged = unknown(storeA, "rec-2");
        Settlement billed = inScope(storeA, () -> ledger.reconcile(operator, charged,
                ReconciliationOutcome.CHARGED, new BigDecimal("0.2"), "Usage report, request req_2"));
        assertThat(billed.overrun()).as("recorded in full above the reservation").isEqualByComparingTo("0.065");
        assertThat(storeBalance(storeA)).isEqualTo("reserved=0.00000000 spent=0.20000000");
        Settlement repeated = inScope(storeA, () -> ledger.reconcile(operator, charged,
                ReconciliationOutcome.CHARGED, new BigDecimal("0.2"), "Usage report, request req_2"));
        assertThat(repeated.repeated()).isTrue();
        assertThatThrownBy(() -> inScope(storeA, () -> ledger.reconcile(operator, charged,
                ReconciliationOutcome.NOT_CHARGED, null, "changed my mind")))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("AI_SETTLEMENT_CONFLICT");
        assertThat(storeBalance(storeA)).isEqualTo("reserved=0.00000000 spent=0.20000000");

        assertThat(fixtures.sql("""
                        SELECT outcome || ':' || recorded_by || ':' || reason FROM ai_usage_event
                        WHERE reservation_id = ?
                        """).param(charged).query(String.class).single())
                .isEqualTo("RECONCILED_CHARGED:SYSTEM:ai-cost-operator:Usage report, request req_2");

        // Only UNKNOWN is reconciled: a call that is still in progress waits for its result or the sweep.
        UUID inProgress = sent(storeA, "rec-3");
        assertThatThrownBy(() -> inScope(storeA, () -> ledger.reconcile(operator, inProgress,
                ReconciliationOutcome.NOT_CHARGED, null, "too early")))
                .isInstanceOf(DataAccessException.class).rootCause()
                .hasMessageContaining("AI_RESERVATION_NOT_RECONCILABLE");
    }

    @Test
    void anUnknownWithoutProviderDataIsSettledConservativelyAfterTheConfiguredAge() {
        UUID id = unknown(storeA, "old-1");
        UUID recent = unknown(storeA, "old-2");
        fixtures.sql("UPDATE ai_budget_reservation SET unknown_at = now() - interval '25 hours' WHERE id = ?")
                .param(id).update();

        sweeper.sweep();

        assertThat(state(id)).isEqualTo("RECONCILED");
        assertThat(state(recent)).isEqualTo("UNKNOWN");
        assertThat(fixtures.sql("SELECT outcome || ':' || cost || ':' || recorded_by FROM ai_usage_event WHERE reservation_id = ?")
                .param(id).query(String.class).single())
                .isEqualTo("RECONCILED_CONSERVATIVE:0.13500000:SYSTEM:ai-reservation-sweep");
        // The full reserved amount is now spent; the recent UNKNOWN still holds its reservation.
        assertThat(storeBalance(storeA)).isEqualTo("reserved=0.13500000 spent=0.13500000");
    }

    @Test
    void aStoreCannotSettleReadOrWriteAnotherStoresUsage() {
        UUID id = sent(storeA, "iso-1");
        assertThatThrownBy(() -> inScope(storeB, () -> ledger.settle(scope(storeB), id,
                ProviderUsage.tokens("msg_iso", 1, 1))))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("AI_RESERVATION_NOT_FOUND");
        assertThatThrownBy(() -> inScope(storeB, () -> ledger.markSent(scope(storeB), id)))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("AI_RESERVATION_NOT_FOUND");
        inScope(storeA, () -> ledger.settle(scope(storeA), id, ProviderUsage.tokens("msg_iso", 40_000, 500)));

        inScope(storeB, () -> {
            assertThat(jdbc.sql("SELECT count(*) FROM ai_usage_event WHERE reservation_id = ?").param(id)
                    .query(Long.class).single()).isZero();
            return null;
        });
        inScope(storeA, () -> {
            assertThat(jdbc.sql("SELECT count(*) FROM ai_usage_event WHERE reservation_id = ?").param(id)
                    .query(Long.class).single()).isOne();
            assertThat(jdbc.sql("UPDATE ai_usage_event SET cost = 0 WHERE reservation_id = ?").param(id).update())
                    .as("no update policy").isZero();
            assertThatThrownBy(() -> jdbc.sql("""
                            INSERT INTO ai_usage_event (id, organization_id, store_id, reservation_id, automation_type,
                                                        price_id, provider, model, region, price_version, currency,
                                                        cost, reserved_amount, overrun_amount, outcome, recorded_by)
                            SELECT gen_random_uuid(), organization_id, store_id, reservation_id, automation_type,
                                   price_id, provider, model, region, price_version, currency, 0, reserved_amount, 0,
                                   'SETTLED', 'me'
                            FROM ai_usage_event WHERE reservation_id = ?
                            """).param(id).update()).isInstanceOf(DataAccessException.class);
            return null;
        });
        assertThatThrownBy(() -> fixtures.sql("DELETE FROM ai_usage_event WHERE reservation_id = ?").param(id)
                .update()).isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("append-only");
        // The ledger functions take the store only from the bound connection.
        assertThatThrownBy(() -> jdbc.sql("SELECT ai_mark_sent(?::uuid)").param(id).query(String.class).single())
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("AI_SCOPE_NOT_BOUND");
        assertThatThrownBy(() -> jdbc.sql("SELECT * FROM ai_close_reservation(?::uuid, 0, 'RELEASED', 'x')")
                .param(id).query(String.class).single())
                .as("the internal helper is not executable by the runtime")
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("permission denied");
    }

    private UUID reserve(UUID store, String key) {
        return reserved(inScope(store, () -> governor.reserve(scope(store), request(key)))).reservationId();
    }

    private UUID sent(UUID store, String key) {
        UUID id = reserve(store, key);
        assertThat(inScope(store, () -> ledger.markSent(scope(store), id))).isEqualTo(SendDecision.SEND);
        return id;
    }

    private UUID unknown(UUID store, String key) {
        UUID id = sent(store, key);
        inScope(store, () -> {
            ledger.markUnknown(scope(store), id, "CONNECTION_RESET");
            return null;
        });
        return id;
    }

    private ReservationRequest request(String key) {
        return new ReservationRequest(key, "inventory", null,
                AiCallEstimate.of("anthropic", model, "global", 100_000, 1_000));
    }

    private static ReservationDecision.Reserved reserved(ReservationDecision decision) {
        assertThat(decision).isInstanceOf(ReservationDecision.Reserved.class);
        return (ReservationDecision.Reserved) decision;
    }

    private void expire(UUID id) {
        fixtures.sql("UPDATE ai_budget_reservation SET expires_at = now() - interval '1 minute' WHERE id = ?")
                .param(id).update();
    }

    private String state(UUID id) {
        return fixtures.sql("SELECT state FROM ai_budget_reservation WHERE id = ?").param(id).query(String.class)
                .single();
    }

    private String storeBalance(UUID store) {
        return fixtures.sql("""
                        SELECT 'reserved=' || reserved_amount || ' spent=' || spent_amount FROM ai_budget_balance
                        WHERE level = 'STORE' AND store_id = ? AND period_type = 'MONTHLY'
                          AND period_start <= now() AND period_end > now()
                        """).param(store).query(String.class).single();
    }

    private void policy(String level, UUID org, UUID store, BigDecimal limit) {
        fixtures.sql("""
                        UPDATE ai_budget_policy SET superseded_at = now()
                        WHERE level = ? AND store_id IS NOT DISTINCT FROM ?::uuid AND superseded_at IS NULL
                        """).params(level, store).update();
        fixtures.sql("""
                        INSERT INTO ai_budget_policy (id, level, organization_id, store_id, period_type, amount_limit,
                                                      currency, policy_version, reason, created_by)
                        SELECT gen_random_uuid(), ?, ?::uuid, ?::uuid, 'MONTHLY', ?, 'USD',
                               coalesce(max(p.policy_version), 0) + 1, 'test', 'test'
                        FROM ai_budget_policy p
                        WHERE p.level = ? AND p.organization_id IS NOT DISTINCT FROM ?::uuid
                          AND p.store_id IS NOT DISTINCT FROM ?::uuid AND p.automation_type IS NULL
                          AND p.period_type = 'MONTHLY'
                        """).params(level, org, store, limit, level, org, store).update();
    }

    private TrustedStoreScope scope(UUID store) {
        return TestScopes.system(organization, store);
    }

    private <T> T inScope(UUID store, Supplier<T> work) {
        try (StoreRowGuard.Binding ignored = StoreRowGuard.bind(scope(store))) {
            return work.get();
        }
    }

    private static <T> List<T> parallel(int calls, Callable<T> call) throws Exception {
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
