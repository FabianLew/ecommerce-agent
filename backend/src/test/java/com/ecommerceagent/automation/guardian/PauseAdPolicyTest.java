package com.ecommerceagent.automation.guardian;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.ecommerceagent.decisions.PauseAd;
import com.ecommerceagent.integration.IntegrationHealth;
import com.ecommerceagent.intelligence.inventory.InventoryContextFixture;
import org.junit.jupiter.api.Test;

/** ADR-0012 point 5: every check of PauseAdPolicy in order; L1 means approval is required. */
class PauseAdPolicyTest {

    static final InventoryProtectionPolicy L1 = policy(InventoryProtectionPolicy.Autonomy.L1, false);
    static final Instant NOW = InventoryContextFixture.AS_OF.plusSeconds(5);
    static final PauseAd PAUSE_9001 = new PauseAd(InventoryContextFixture.AD_9001);

    @Test
    void scenarioRequiresApprovalInL1() {
        PauseAdPolicy.Evaluation result = evaluate(new InventoryContextFixture(), PAUSE_9001,
                IntegrationHealth.Status.HEALTHY, L1);
        assertThat(result.outcome()).isEqualTo(PolicyOutcome.REQUIRE_APPROVAL);
        assertThat(result.checks()).extracting(PolicyCheck::name).containsExactly("ISOLATION", "INTEGRATION_HEALTH",
                "FRESHNESS", "RISK_AND_QUALITY", "MAPPING_AND_CREATIVE", "AD_STATE", "AUTONOMY");
        assertThat(result.checks()).allMatch(check -> check.result() == PolicyCheck.Result.PASSED);
        assertThat(result.checks().getLast().code()).isEqualTo("AUTONOMY_L1");
    }

    /** ADR-0026 point 4: L2 is not available, whatever is configured. */
    @Test
    void l2IsNotAvailable() {
        for (boolean flag : new boolean[] {false, true}) {
            PauseAdPolicy.Evaluation result = evaluate(new InventoryContextFixture(), PAUSE_9001,
                    IntegrationHealth.Status.HEALTHY, policy(InventoryProtectionPolicy.Autonomy.L2, flag));
            assertThat(result.outcome()).isEqualTo(PolicyOutcome.REQUIRE_APPROVAL);
            assertThat(result.checks().getLast().code()).isEqualTo("L2_NOT_AVAILABLE");
        }
        assertThat(evaluate(new InventoryContextFixture(), PAUSE_9001, IntegrationHealth.Status.HEALTHY,
                policy(InventoryProtectionPolicy.Autonomy.L1, true)).outcome())
                .isEqualTo(PolicyOutcome.REQUIRE_APPROVAL);
    }

    static InventoryProtectionPolicy policy(InventoryProtectionPolicy.Autonomy autonomy, boolean l2Enabled) {
        return new InventoryProtectionPolicy("1.1.0", InventoryProtectionPolicy.Mode.EVALUATION_ONLY,
                Duration.ofSeconds(120), Duration.ofSeconds(60), Duration.ofHours(24), 3, Duration.ofHours(24),
                autonomy, l2Enabled);
    }

    @Test
    void eachCheckDecidesWhenItFailsAndLaterChecksAreNotEvaluated() {
        PauseAdPolicy.Evaluation foreign = evaluate(new InventoryContextFixture(), new PauseAd(UUID.randomUUID()),
                IntegrationHealth.Status.HEALTHY, L1);
        assertThat(foreign.outcome()).isEqualTo(PolicyOutcome.DENY);
        assertThat(foreign.checks().getFirst().code()).isEqualTo("TARGET_NOT_IN_CONTEXT");
        assertThat(foreign.checks().subList(1, 7)).allMatch(check -> check.result() == PolicyCheck.Result.NOT_EVALUATED);

        assertThat(code(new InventoryContextFixture(), PAUSE_9001, IntegrationHealth.Status.STALE))
                .isEqualTo(PolicyOutcome.DEFER + ":INTEGRATION_STALE");
        assertThat(code(new InventoryContextFixture(), PAUSE_9001, null))
                .isEqualTo(PolicyOutcome.DEFER + ":INTEGRATION_MISSING");
        assertThat(code(new InventoryContextFixture(), PAUSE_9001, IntegrationHealth.Status.DEGRADED))
                .isEqualTo(PolicyOutcome.REQUIRE_APPROVAL + ":AUTONOMY_L1");

        InventoryContextFixture staleStock = new InventoryContextFixture();
        staleStock.stockConfirmedAt = NOW.minusSeconds(121);
        assertThat(code(staleStock, PAUSE_9001, IntegrationHealth.Status.HEALTHY))
                .isEqualTo(PolicyOutcome.DEFER + ":STOCK_NOT_CURRENT");

        InventoryContextFixture staleAd = new InventoryContextFixture();
        staleAd.ads.set(0, withObservedAt(InventoryContextFixture.dedicated(), NOW.minusSeconds(61)));
        assertThat(code(staleAd, PAUSE_9001, IntegrationHealth.Status.HEALTHY))
                .isEqualTo(PolicyOutcome.DEFER + ":AD_STATE_NOT_CURRENT");

        InventoryContextFixture estimated = new InventoryContextFixture();
        estimated.demandQuality = "ESTIMATED";
        assertThat(code(estimated, PAUSE_9001, IntegrationHealth.Status.HEALTHY))
                .isEqualTo(PolicyOutcome.DEFER + ":DEMAND_WINDOW_INCOMPLETE");

        InventoryContextFixture notConfirmed = new InventoryContextFixture();
        notConfirmed.lastRiskOutcome = "UNKNOWN";
        assertThat(code(notConfirmed, PAUSE_9001, IntegrationHealth.Status.HEALTHY))
                .isEqualTo(PolicyOutcome.DEFER + ":RISK_NOT_CONFIRMED");

        assertThat(code(new InventoryContextFixture(), new PauseAd(InventoryContextFixture.AD_9002),
                IntegrationHealth.Status.HEALTHY)).isEqualTo(PolicyOutcome.DENY + ":MULTI_PRODUCT_AD");

        InventoryContextFixture oldVerification = new InventoryContextFixture();
        InventoryContextFixture.AdFactBuilder.verifiedAt(oldVerification, NOW.minus(Duration.ofHours(25)));
        assertThat(code(oldVerification, PAUSE_9001, IntegrationHealth.Status.HEALTHY))
                .isEqualTo(PolicyOutcome.DENY + ":MAPPING_VERIFICATION_EXPIRED");

        InventoryContextFixture changedCreative = new InventoryContextFixture();
        changedCreative.ads.set(0, InventoryContextFixture.ad(InventoryContextFixture.AD_9001, "ad-9001",
                "SINGLE_VARIANT", 1, "VERIFIED", false, "ACTIVE"));
        assertThat(code(changedCreative, PAUSE_9001, IntegrationHealth.Status.HEALTHY))
                .isEqualTo(PolicyOutcome.DENY + ":CREATIVE_CHANGED");

        InventoryContextFixture paused = new InventoryContextFixture();
        paused.ads.set(0, InventoryContextFixture.ad(InventoryContextFixture.AD_9001, "ad-9001", "SINGLE_VARIANT", 1,
                "VERIFIED", true, "PAUSED"));
        assertThat(code(paused, PAUSE_9001, IntegrationHealth.Status.HEALTHY))
                .isEqualTo(PolicyOutcome.DENY + ":ALREADY_PAUSED");

        InventoryContextFixture disapproved = new InventoryContextFixture();
        disapproved.ads.set(0, InventoryContextFixture.ad(InventoryContextFixture.AD_9001, "ad-9001",
                "SINGLE_VARIANT", 1, "VERIFIED", true, "DISAPPROVED"));
        assertThat(code(disapproved, PAUSE_9001, IntegrationHealth.Status.HEALTHY))
                .isEqualTo(PolicyOutcome.DENY + ":CONFLICT");
    }

    private static com.ecommerceagent.intelligence.inventory.InventoryContext.AdFact withObservedAt(
            com.ecommerceagent.intelligence.inventory.InventoryContext.AdFact ad, Instant observedAt) {
        return new com.ecommerceagent.intelligence.inventory.InventoryContext.AdFact(ad.adId(), ad.externalId(),
                ad.name(), ad.ownStatus(), ad.effectiveStatus(), observedAt, ad.mappingScope(), ad.mappingVersion(),
                ad.verification(), ad.verifiedAt(), ad.currentForCreative(), ad.variantCount(), ad.stateEvidence(),
                ad.mappingEvidence());
    }

    private static String code(InventoryContextFixture fixture, PauseAd action, IntegrationHealth.Status health) {
        PauseAdPolicy.Evaluation result = evaluate(fixture, action, health, L1);
        String decisive = result.checks().stream().filter(check -> check.result() == PolicyCheck.Result.FAILED)
                .findFirst().map(PolicyCheck::code).orElse(result.checks().getLast().code());
        return result.outcome() + ":" + decisive;
    }

    private static PolicyOutcome outcome(InventoryContextFixture fixture, PauseAd action,
            IntegrationHealth.Status health, InventoryProtectionPolicy policy) {
        return evaluate(fixture, action, health, policy).outcome();
    }

    private static PauseAdPolicy.Evaluation evaluate(InventoryContextFixture fixture, PauseAd action,
            IntegrationHealth.Status health, InventoryProtectionPolicy policy) {
        return PauseAdPolicy.evaluate(policy, action, fixture.build(), health, NOW);
    }
}
