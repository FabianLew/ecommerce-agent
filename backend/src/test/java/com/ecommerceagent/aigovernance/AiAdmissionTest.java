package com.ecommerceagent.aigovernance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import com.ecommerceagent.FixtureDb;
import com.ecommerceagent.TestcontainersConfiguration;
import com.ecommerceagent.aigovernance.AiAdmission.Admission;
import com.ecommerceagent.identity.StoreRowGuard;
import com.ecommerceagent.identity.TestScopes;
import com.ecommerceagent.identity.TrustedStoreScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * ADR-0027 stage 1, step 5 (V33) on a real PostgreSQL through the runtime role: run admission per store and for the
 * platform across parallel callers, leases that end or expire, and the platform AI stop, which refuses new runs and
 * every reserved call that was not sent yet. Operations change the stop only with the administration role.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
class AiAdmissionTest {

    private static final Duration TTL = Duration.ofMinutes(2);

    @Autowired
    AiAdmission admission;

    @Autowired
    AiBudgetGovernor governor;

    @Autowired
    AiUsageLedger ledger;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    FixtureDb fixtures;

    UUID organization;
    UUID storeA;
    UUID storeB;
    UUID opportunityA;
    UUID opportunityB;
    String model;

    @BeforeEach
    void setUp() {
        fixtures.registerAutomations("inventory");
        organization = UUID.randomUUID();
        storeA = UUID.randomUUID();
        storeB = UUID.randomUUID();
        fixtures.sql("INSERT INTO organization (id, name) VALUES (?, 'AI admission')").param(organization).update();
        fixtures.sql("""
                        INSERT INTO store (id, organization_id, name, currency, timezone)
                        VALUES (?, ?, 'A', 'PLN', 'UTC'), (?, ?, 'B', 'PLN', 'UTC')
                        """).params(storeA, organization, storeB, organization).update();
        opportunityA = opportunity(storeA);
        opportunityB = opportunity(storeB);
    }

    @AfterEach
    void resume() {
        // The stop is platform-wide: never leave it on for other tests.
        if (admission.stopped()) {
            asAdministrator("INSERT INTO ai_platform_stop_change (stopped, reason, changed_by)"
                    + " VALUES (false, 'test end', 'test')");
        }
    }

    /** ADR-0027 section 10: an automation that is not registered is never admitted, and the runtime cannot add one. */
    @Test
    void anUnregisteredAutomationIsRefused() {
        UUID first = run(storeA, opportunityA);

        assertThat(inScope(storeA, () -> admission.admit(scope(storeA), first, "not-registered", 1, TTL)))
                .isEqualTo(Admission.UNKNOWN_AUTOMATION);
        assertThat(inScope(storeA, () -> jdbc.sql("SELECT count(*) FROM ai_run_lease").query(Integer.class).single()))
                .isZero();
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO ai_automation_type (automation_type, description)"
                + " VALUES ('runtime-made', 'x')").update()).as("the runtime cannot register an automation")
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void aStoreRunsAtMostItsLimitAndAReleasedLeaseFreesTheSlot() {
        UUID first = run(storeA, opportunityA);
        UUID second = run(storeA, opportunityA);
        UUID other = run(storeB, opportunityB);

        assertThat(admit(storeA, first, 1)).isEqualTo(Admission.GRANTED);
        assertThat(admit(storeA, first, 1)).as("a repeated admission of the same run").isEqualTo(Admission.GRANTED);
        assertThat(admit(storeA, second, 1)).isEqualTo(Admission.STORE_LIMIT);
        assertThat(admit(storeB, other, 1)).as("another store has its own slots").isEqualTo(Admission.GRANTED);

        inScope(storeA, () -> {
            admission.release(scope(storeA), first);
            admission.release(scope(storeA), first);
            return null;
        });
        assertThat(admit(storeA, second, 1)).isEqualTo(Admission.GRANTED);
        assertThat(admit(storeA, first, 1)).as("a run is admitted once").isEqualTo(Admission.LEASE_ENDED);
        release(storeA, second);
        release(storeB, other);
    }

    @Test
    void anExpiredLeaseOfALostWorkerDoesNotBlockTheStore() {
        UUID lost = run(storeA, opportunityA);
        UUID next = run(storeA, opportunityA);
        assertThat(admit(storeA, lost, 1)).isEqualTo(Admission.GRANTED);
        fixtures.sql("""
                        UPDATE ai_run_lease SET acquired_at = now() - interval '10 minutes',
                                                expires_at = now() - interval '1 minute'
                        WHERE agent_run_id = ?
                        """).param(lost).update();

        assertThat(admit(storeA, next, 1)).isEqualTo(Admission.GRANTED);
        release(storeA, next);
    }

    @Test
    void parallelAdmissionsNeverExceedTheStoreLimit() throws Exception {
        List<UUID> runs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            runs.add(run(storeA, opportunityA));
        }
        List<Admission> answers = parallel(runs.size(), index -> admit(storeA, runs.get(index), 2));

        assertThat(answers).filteredOn(Admission::granted).hasSize(2);
        assertThat(answers).filteredOn(answer -> !answer.granted()).containsOnly(Admission.STORE_LIMIT);
        runs.forEach(id -> release(storeA, id));
    }

    @Test
    void thePlatformLimitHoldsAcrossStores() {
        int open = fixtures.sql("SELECT count(*) FROM ai_run_lease WHERE released_at IS NULL AND expires_at > now()")
                .query(Integer.class).single();
        UUID a = run(storeA, opportunityA);
        UUID b = run(storeB, opportunityB);
        int global = open + 1;

        assertThat(inScope(storeA, () -> acquire(a, 5, global))).isEqualTo("GRANTED");
        assertThat(inScope(storeB, () -> acquire(b, 5, global))).isEqualTo("GLOBAL_LIMIT");
        release(storeA, a);
    }

    @Test
    void theStopRefusesNewRunsAndReservedCallsAndOnlyOperationsCanChangeIt() {
        price();
        budgets();
        UUID running = run(storeA, opportunityA);
        UUID reservation = reserve(storeA, running, "stop-1");
        assertThat(admission.stopped()).isFalse();

        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO ai_platform_stop_change (stopped, reason, changed_by) VALUES (false, 'x', 'app')
                        """).update()).as("the runtime cannot change the stop").isInstanceOf(DataAccessException.class);
        asAdministrator("INSERT INTO ai_platform_stop_change (stopped, reason, changed_by)"
                + " VALUES (true, 'provider incident', 'ops:test')");

        assertThat(admission.stopped()).isTrue();
        assertThat(admit(storeA, run(storeA, opportunityA), 5)).isEqualTo(Admission.AI_STOPPED);
        assertThat(inScope(storeA, () -> ledger.markSent(scope(storeA), reservation)))
                .as("a reservation granted before the stop does not allow the call").isEqualTo(SendDecision.STOPPED);
        assertThat(fixtures.sql("SELECT state || ':' || state_reason FROM ai_budget_reservation WHERE id = ?")
                .param(reservation).query(String.class).single()).isEqualTo("RELEASED:AI_STOPPED");
        assertThat(fixtures.sql("""
                        SELECT reserved_amount FROM ai_budget_balance
                        WHERE level = 'STORE' AND store_id = ? AND period_type = 'MONTHLY'
                          AND period_start <= now() AND period_end > now()
                        """).param(storeA).query(BigDecimal.class).single()).isEqualByComparingTo("0");
        // The history is append-only for operations: a change or a delete touches no row.
        asAdministratorCount("UPDATE ai_platform_stop_change SET reason = 'rewritten'", 0);
        asAdministratorCount("DELETE FROM ai_platform_stop_change", 0);

        asAdministrator("INSERT INTO ai_platform_stop_change (stopped, reason, changed_by)"
                + " VALUES (false, 'resolved', 'ops:test')");
        assertThat(admission.stopped()).isFalse();
        UUID after = run(storeA, opportunityA);
        assertThat(admit(storeA, after, 5)).isEqualTo(Admission.GRANTED);
        release(storeA, after);
    }

    @Test
    void aLeaseOfAnotherStoreCannotBeTakenOrReleased() {
        UUID id = run(storeA, opportunityA);
        assertThat(admit(storeA, id, 1)).isEqualTo(Admission.GRANTED);

        assertThatThrownBy(() -> admit(storeB, id, 1)).isInstanceOf(DataAccessException.class);
        assertThat(inScope(storeB, () -> jdbc.sql("SELECT ai_release_run_lease(?::uuid)").param(id)
                .query(Boolean.class).single())).isFalse();
        assertThat(inScope(storeB, () -> jdbc.sql("SELECT count(*) FROM ai_run_lease").query(Integer.class)
                .single())).as("a store sees only its own leases").isZero();
        release(storeA, id);
    }

    private Admission admit(UUID store, UUID run, int maxStoreRuns) {
        return inScope(store, () -> admission.admit(scope(store), run, "inventory", maxStoreRuns, TTL));
    }

    private String acquire(UUID run, int maxStore, int maxGlobal) {
        return jdbc.sql("SELECT ai_acquire_run_lease(?::uuid, 'inventory', ?, ?, interval '2 minutes')")
                .params(run, maxStore, maxGlobal).query(String.class).single();
    }

    private void release(UUID store, UUID run) {
        inScope(store, () -> {
            admission.release(scope(store), run);
            return null;
        });
    }

    private void asAdministrator(String sql) {
        fixtures.inTransaction(tx -> {
            tx.sql("SET LOCAL ROLE operator_migrator").update();
            tx.sql(sql).update();
        });
    }

    private void asAdministratorCount(String sql, int expected) {
        fixtures.rolledBack(tx -> {
            tx.sql("SET LOCAL ROLE operator_migrator").update();
            assertThat(tx.sql(sql).update()).as(sql).isEqualTo(expected);
        });
    }

    private UUID opportunity(UUID store) {
        UUID id = UUID.randomUUID();
        fixtures.sql("""
                        INSERT INTO opportunity (id, organization_id, store_id, category, subject_type, subject_id,
                                                 status, opened_at, updated_at)
                        VALUES (?, ?, ?, 'INVENTORY_RISK', 'PRODUCT_VARIANT', ?, 'OPEN', now(), now())
                        """).params(id, organization, store, UUID.randomUUID()).update();
        return id;
    }

    private UUID run(UUID store, UUID opportunity) {
        UUID id = UUID.randomUUID();
        fixtures.inTransaction(tx -> {
            tx.sql("SET LOCAL session_replication_role = replica").update();
            tx.sql("""
                            INSERT INTO agent_run (id, organization_id, store_id, analysis_key, attempt, opportunity_id,
                                                   context_snapshot_id, agent_name, agent_version, skill_ref,
                                                   knowledge_hash, prompt_hash, tools_hash, output_schema_version,
                                                   model_policy, provider, model, price_list, status, started_at)
                            VALUES (?, ?, ?, ?, 1, ?, ?, 'a', '1', 's', 'k', 'p', 't', 'v1', 'm', 'fake', 'm', 'p',
                                    'RUNNING', now())
                            """).params(id, organization, store, id.toString(), opportunity, UUID.randomUUID())
                    .update();
        });
        return id;
    }

    private void price() {
        model = "admission-model-" + UUID.randomUUID();
        fixtures.sql("""
                        INSERT INTO ai_price (id, provider, model, region, price_version, currency, input_per_mtok,
                                              output_per_mtok, cache_write_per_mtok, cache_read_per_mtok, extras,
                                              effective_from, source, verified_at)
                        VALUES (gen_random_uuid(), 'anthropic', ?, 'global', 'v1', 'USD', 1.00, 10.00, 1.25, 0.10,
                                '{}', now() - interval '1 day', 'test fixture', now())
                        """).param(model).update();
    }

    private void budgets() {
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
                        VALUES (gen_random_uuid(), 'STORE', ?, ?, 'MONTHLY', 10, 'USD', 1, 'test', 'test')
                        """).params(organization, storeA).update();
    }

    private UUID reserve(UUID store, UUID run, String key) {
        ReservationDecision decision = inScope(store, () -> governor.reserve(scope(store), new ReservationRequest(key,
                "inventory", run, AiCallEstimate.of("anthropic", model, "global", 100_000, 1_000))));
        assertThat(decision).isInstanceOf(ReservationDecision.Reserved.class);
        return ((ReservationDecision.Reserved) decision).reservationId();
    }

    private TrustedStoreScope scope(UUID store) {
        return TestScopes.system(organization, store);
    }

    private <T> T inScope(UUID store, Supplier<T> work) {
        try (StoreRowGuard.Binding ignored = StoreRowGuard.bind(scope(store))) {
            return work.get();
        }
    }

    private interface IndexedCall<T> {
        T call(int index) throws Exception;
    }

    private static <T> List<T> parallel(int calls, IndexedCall<T> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < calls; i++) {
                int index = i;
                Callable<T> task = () -> {
                    start.await();
                    return call.call(index);
                };
                futures.add(pool.submit(task));
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
