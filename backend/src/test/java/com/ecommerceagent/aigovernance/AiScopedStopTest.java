package com.ecommerceagent.aigovernance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import com.ecommerceagent.FixtureDb;
import com.ecommerceagent.TestcontainersConfiguration;
import com.ecommerceagent.aigovernance.AiAdmission.Admission;
import com.ecommerceagent.identity.PlatformOperator;
import com.ecommerceagent.identity.PlatformOperatorDeniedException;
import com.ecommerceagent.identity.PlatformOperators;
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
 * ADR-0027 section 12 (E-54.8) on a real PostgreSQL through the runtime role: AI stopped for a store, for an
 * automation on every store, or for an automation on one store. Each stop refuses new runs and every reserved call
 * not yet sent, each is independent of the others and of the platform stop, and only platform operators change them.
 * Each test uses its own automation type, so a platform-wide automation stop never reaches other tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
class AiScopedStopTest {

    private static final Duration TTL = Duration.ofMinutes(2);

    @Autowired
    AiAdmission admission;

    @Autowired
    AiBudgetGovernor governor;

    @Autowired
    AiUsageLedger ledger;

    @Autowired
    AiStops stops;

    @Autowired
    PlatformOperators operators;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    FixtureDb fixtures;

    UUID organization;
    UUID storeA;
    UUID storeB;
    String automation;
    String model;
    UUID operatorUser;
    PlatformOperator operator;

    @BeforeEach
    void setUp() {
        automation = "stop-test-" + UUID.randomUUID().toString().substring(0, 8);
        fixtures.registerAutomations(automation);
        organization = UUID.randomUUID();
        storeA = UUID.randomUUID();
        storeB = UUID.randomUUID();
        fixtures.sql("INSERT INTO organization (id, name) VALUES (?, 'AI stops')").param(organization).update();
        fixtures.sql("""
                        INSERT INTO store (id, organization_id, name, currency, timezone)
                        VALUES (?, ?, 'A', 'PLN', 'UTC'), (?, ?, 'B', 'USD', 'UTC')
                        """).params(storeA, organization, storeB, organization).update();
        model = "stop-model-" + UUID.randomUUID();
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
                        VALUES (gen_random_uuid(), 'STORE', ?, ?, 'MONTHLY', 10, 'USD', 1, 'test', 'test'),
                               (gen_random_uuid(), 'STORE', ?, ?, 'MONTHLY', 10, 'USD', 1, 'test', 'test')
                        """).params(organization, storeA, organization, storeB).update();
        operatorUser = UUID.randomUUID();
        fixtures.sql("INSERT INTO app_user (id, issuer, subject) VALUES (?, 'test', ?)")
                .params(operatorUser, "ops-" + operatorUser).update();
        fixtures.sql("INSERT INTO platform_operator (user_id, granted_by, reason) VALUES (?, 'ops:test', 'test')")
                .param(operatorUser).update();
        operator = operators.require(operatorUser);
    }

    @AfterEach
    void resume() {
        // The platform stop is platform-wide: never leave it on for other tests.
        if (admission.stopped()) {
            asAdministrator("INSERT INTO ai_platform_stop_change (stopped, reason, changed_by)"
                    + " VALUES (false, 'test end', 'test')");
        }
    }

    @Test
    void aStoreStopRefusesNewRunsAndTheNextCallOfARunningOneInThatStoreOnly() {
        UUID running = run(storeA);
        assertThat(admit(storeA, running)).isEqualTo(Admission.GRANTED);
        UUID reserved = reserve(storeA, running, "store-1");

        assertThat(stops.change(operator, storeA, null, true, "Abuse report")).isTrue();

        assertThat(admit(storeA, run(storeA))).isEqualTo(Admission.STORE_STOPPED);
        assertThat(inScope(storeA, () -> ledger.markSent(scope(storeA), reserved)))
                .as("a call reserved before the stop is not sent").isEqualTo(SendDecision.STORE_STOPPED);
        assertThat(stateOf(reserved)).isEqualTo("RELEASED:STORE_STOPPED");
        UUID other = run(storeB);
        assertThat(admit(storeB, other)).as("other stores keep working").isEqualTo(Admission.GRANTED);
        UUID otherCall = reserve(storeB, other, "store-2");
        assertThat(inScope(storeB, () -> ledger.markSent(scope(storeB), otherCall))).isEqualTo(SendDecision.SEND);

        assertThat(stops.change(operator, storeA, null, false, "Resolved")).isTrue();
        assertThat(admit(storeA, run(storeA))).isEqualTo(Admission.GRANTED);
    }

    @Test
    void anAutomationStopAppliesToEveryStoreOrToOneStore() {
        assertThat(stops.change(operator, null, automation, true, "Prompt regression")).isTrue();
        assertThat(admit(storeA, run(storeA))).isEqualTo(Admission.AUTOMATION_STOPPED);
        assertThat(admit(storeB, run(storeB))).isEqualTo(Admission.AUTOMATION_STOPPED);
        assertThat(stops.change(operator, null, automation, false, "Fixed")).isTrue();

        UUID running = run(storeB);
        assertThat(admit(storeB, running)).isEqualTo(Admission.GRANTED);
        UUID reserved = reserve(storeB, running, "auto-1");
        assertThat(stops.change(operator, storeB, automation, true, "Store asked to pause it")).isTrue();
        assertThat(inScope(storeB, () -> ledger.markSent(scope(storeB), reserved)))
                .isEqualTo(SendDecision.AUTOMATION_STOPPED);
        assertThat(admit(storeB, run(storeB))).isEqualTo(Admission.AUTOMATION_STOPPED);
        assertThat(admit(storeA, run(storeA))).isEqualTo(Admission.GRANTED);
    }

    @Test
    void theStopsAreIndependentAndNoneResumesAnother() {
        assertThat(stops.change(operator, storeA, null, true, "Store stop")).isTrue();
        assertThat(stops.change(operator, null, automation, true, "Automation stop")).isTrue();
        asAdministrator("INSERT INTO ai_platform_stop_change (stopped, reason, changed_by)"
                + " VALUES (true, 'platform stop', 'ops:test')");

        assertThat(admit(storeA, run(storeA))).isEqualTo(Admission.AI_STOPPED);
        asAdministrator("INSERT INTO ai_platform_stop_change (stopped, reason, changed_by)"
                + " VALUES (false, 'platform resumed', 'ops:test')");
        assertThat(admit(storeA, run(storeA))).as("the store stop is still on").isEqualTo(Admission.STORE_STOPPED);
        assertThat(stops.change(operator, storeA, null, false, "Store resumed")).isTrue();
        assertThat(admit(storeA, run(storeA))).as("the automation stop is still on")
                .isEqualTo(Admission.AUTOMATION_STOPPED);
        assertThat(stops.change(operator, null, automation, false, "Automation resumed")).isTrue();
        assertThat(admit(storeA, run(storeA))).isEqualTo(Admission.GRANTED);
    }

    @Test
    void everyDecisionIsAppendedWithItsOperatorAndAuditedAndARepeatWritesNothing() {
        assertThat(stops.change(operator, storeA, null, true, "First")).isTrue();
        assertThat(stops.change(operator, storeA, null, true, "Again")).as("already stopped").isFalse();
        assertThat(stops.change(operator, storeA, null, false, "Resumed")).isTrue();
        assertThat(stops.change(operator, storeA, null, false, "Again")).isFalse();
        assertThat(stops.change(operator, null, automation, true, "Platform-wide")).isTrue();

        assertThat(stops.history(operator, 1000)).filteredOn(c -> storeA.equals(c.storeId()))
                .extracting(AiStopChange::stopped, AiStopChange::reason, AiStopChange::changedBy,
                        AiStopChange::level)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(false, "Resumed", operator.recordedAs(), "STORE"),
                        org.assertj.core.groups.Tuple.tuple(true, "First", operator.recordedAs(), "STORE"));
        assertThat(stops.active(operator)).extracting(AiStopChange::automationType, AiStopChange::level)
                .contains(org.assertj.core.groups.Tuple.tuple(automation, "AUTOMATION"));
        assertThat(stops.active(operator)).noneMatch(c -> storeA.equals(c.storeId()));
        assertThat(fixtures.sql("""
                        SELECT action || ':' || actor_user_id || ':' || coalesce(store_id::text, '-') FROM audit_event
                        WHERE resource_type = 'ai_stop' AND (store_id = ? OR resource_id = ?) ORDER BY occurred_at
                        """).params(storeA, "*:" + automation).query(String.class).list())
                .containsExactly("AI_STOPPED_SCOPED:" + operatorUser + ":" + storeA,
                        "AI_RESUMED_SCOPED:" + operatorUser + ":" + storeA,
                        "AI_STOPPED_SCOPED:" + operatorUser + ":-");
        stops.change(operator, null, automation, false, "Clean up");
    }

    @Test
    void onlyAPlatformOperatorChangesAStopAndTheRuntimeCannotWriteTheLog() {
        fixtures.sql("UPDATE platform_operator SET revoked_at = now() WHERE user_id = ?").param(operatorUser)
                .update();
        assertThatThrownBy(() -> stops.change(operator, storeA, null, true, "x"))
                .isInstanceOf(PlatformOperatorDeniedException.class);
        assertThatThrownBy(() -> stops.active(operator)).isInstanceOf(PlatformOperatorDeniedException.class);
        fixtures.sql("UPDATE platform_operator SET revoked_at = NULL WHERE user_id = ?").param(operatorUser)
                .update();

        assertThatThrownBy(() -> stops.change(operator, null, "not-registered", true, "x"))
                .isInstanceOf(AiReconciliationException.class)
                .extracting(e -> ((AiReconciliationException) e).kind())
                .isEqualTo(AiReconciliationException.Kind.NOT_FOUND);
        assertThatThrownBy(() -> stops.change(operator, UUID.randomUUID(), null, true, "x"))
                .isInstanceOf(AiReconciliationException.class)
                .extracting(e -> ((AiReconciliationException) e).kind())
                .isEqualTo(AiReconciliationException.Kind.NOT_FOUND);
        for (String reason : List.of("", " ", "x".repeat(501))) {
            assertThatThrownBy(() -> stops.change(operator, storeA, null, true, reason))
                    .isInstanceOf(AiReconciliationException.class);
        }
        assertThatThrownBy(() -> stops.change(operator, null, null, true, "everything"))
                .as("the platform-wide stop of everything is for the administration role")
                .isInstanceOf(AiReconciliationException.class);

        stops.change(operator, storeA, null, true, "Stop");
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO ai_stop_change (organization_id, store_id, stopped, reason, changed_by)
                        VALUES (?, ?, false, 'sneaky resume', 'app')
                        """).params(organization, storeA).update()).isInstanceOf(DataAccessException.class);
        assertThat(jdbc.sql("UPDATE ai_stop_change SET stopped = false").update()).isZero();
        assertThat(jdbc.sql("DELETE FROM ai_stop_change").update()).isZero();
        assertThatThrownBy(() -> jdbc.sql("SELECT ai_stop_reason(?::uuid, ?)").params(storeA, automation)
                .query(String.class).single())
                .as("the internal check is not executable by the runtime")
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("permission denied");
        asAdministratorCount("UPDATE ai_stop_change SET stopped = false", 0);
        asAdministratorCount("DELETE FROM ai_stop_change", 0);
        assertThat(admit(storeA, run(storeA))).isEqualTo(Admission.STORE_STOPPED);
    }

    private Admission admit(UUID store, UUID run) {
        return inScope(store, () -> admission.admit(scope(store), run, automation, 5, TTL));
    }

    private UUID reserve(UUID store, UUID run, String key) {
        ReservationDecision decision = inScope(store, () -> governor.reserve(scope(store), new ReservationRequest(key,
                automation, run, AiCallEstimate.of("anthropic", model, "global", 100_000, 1_000))));
        assertThat(decision).isInstanceOf(ReservationDecision.Reserved.class);
        return ((ReservationDecision.Reserved) decision).reservationId();
    }

    private String stateOf(UUID reservation) {
        return fixtures.sql("SELECT state || ':' || state_reason FROM ai_budget_reservation WHERE id = ?")
                .param(reservation).query(String.class).single();
    }

    private UUID run(UUID store) {
        UUID opportunity = UUID.randomUUID();
        fixtures.sql("""
                        INSERT INTO opportunity (id, organization_id, store_id, category, subject_type, subject_id,
                                                 status, opened_at, updated_at)
                        VALUES (?, ?, ?, 'INVENTORY_RISK', 'PRODUCT_VARIANT', ?, 'OPEN', now(), now())
                        """).params(opportunity, organization, store, UUID.randomUUID()).update();
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

    private TrustedStoreScope scope(UUID store) {
        return TestScopes.system(organization, store);
    }

    private <T> T inScope(UUID store, Supplier<T> work) {
        try (StoreRowGuard.Binding binding = StoreRowGuard.bind(scope(store))) {
            return work.get();
        }
    }
}
