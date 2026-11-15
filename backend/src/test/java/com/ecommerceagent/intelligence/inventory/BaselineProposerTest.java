package com.ecommerceagent.intelligence.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;

import com.ecommerceagent.decisions.DecisionDisposition;
import com.ecommerceagent.decisions.PauseAd;
import com.ecommerceagent.decisions.ProposalJson;
import com.ecommerceagent.decisions.ProposalSchema;
import com.ecommerceagent.decisions.Recommendation;
import com.ecommerceagent.decisions.RiskDetectorSettings;
import org.junit.jupiter.api.Test;

/** ADR-0012 point 4: the deterministic baseline's rules, in order. */
class BaselineProposerTest {

    final BaselineProposer baseline = new BaselineProposer(new RiskDetectorSettings(
            RiskDetectorSettings.DEFAULT_VERSION, new BigDecimal("3"), new BigDecimal("5"), 2,
            Duration.ofMinutes(5), Duration.ofSeconds(120)));

    @Test
    void scenarioPausesTheDedicatedAdAndExcludesTheCatalogAd() {
        InventoryContextFixture fixture = new InventoryContextFixture();
        InventoryContext context = fixture.build();
        Recommendation rec = baseline.propose(context);

        assertThat(rec.disposition()).isEqualTo(DecisionDisposition.RECOMMEND_ACTION);
        assertThat(rec.action()).isEqualTo(new PauseAd(InventoryContextFixture.AD_9001));
        assertThat(rec.reasonCodes()).containsExactly("COVER_BELOW_THRESHOLD", "DEDICATED_AD_ACTIVE",
                "MULTI_PRODUCT_AD_EXCLUDED", "REPLENISHMENT_UNKNOWN");
        assertThat(rec.summary()).contains("2.6", "ad-9001", "ad-9002");
        assertThat(rec.evidence()).contains(context.stock().evidence(), context.daysOfCover().evidence(),
                context.ads().get(0).stateEvidence(), context.ads().get(0).mappingEvidence(),
                context.ads().get(1).mappingEvidence(), context.replenishmentEvidence());
        assertThat(rec.uncertainties()).isNotEmpty();
    }

    @Test
    void staleStockOrMissingForecastIsInsufficientData() {
        InventoryContextFixture stale = new InventoryContextFixture();
        stale.stockConfirmedAt = InventoryContextFixture.AS_OF.minusSeconds(600);
        Recommendation rec = baseline.propose(stale.build());
        assertThat(rec.disposition()).isEqualTo(DecisionDisposition.INSUFFICIENT_DATA);
        assertThat(rec.missingRequirements()).containsExactly("CURRENT_STOCK");
        assertThat(rec.action()).isNull();

        InventoryContextFixture noForecast = new InventoryContextFixture();
        noForecast.daysOfCover = null;
        assertThat(baseline.propose(noForecast.build()).missingRequirements())
                .containsExactly("SALES_HISTORY_COMPLETE");
    }

    @Test
    void enoughCoverOrNoAdsIsNoAction() {
        InventoryContextFixture enough = new InventoryContextFixture();
        enough.daysOfCover = new BigDecimal("3.0000");
        assertThat(baseline.propose(enough.build()).reasonCodes()).containsExactly("COVER_ABOVE_THRESHOLD");

        InventoryContextFixture noAds = new InventoryContextFixture();
        noAds.ads.clear();
        Recommendation rec = baseline.propose(noAds.build());
        assertThat(rec.disposition()).isEqualTo(DecisionDisposition.NO_ACTION);
        assertThat(rec.reasonCodes()).contains("NO_MAPPED_ADS");

        InventoryContextFixture paused = new InventoryContextFixture();
        paused.ads.set(0, InventoryContextFixture.ad(InventoryContextFixture.AD_9001, "ad-9001", "SINGLE_VARIANT", 1,
                "VERIFIED", true, "PAUSED"));
        assertThat(baseline.propose(paused.build()).reasonCodes()).contains("AD_NOT_ACTIVE");
    }

    @Test
    void onlyCatalogOrUnverifiedAdsEscalate() {
        InventoryContextFixture catalogOnly = new InventoryContextFixture();
        catalogOnly.ads.removeFirst();
        Recommendation rec = baseline.propose(catalogOnly.build());
        assertThat(rec.disposition()).isEqualTo(DecisionDisposition.ESCALATE);
        assertThat(rec.reasonCodes()).contains("ONLY_MULTI_PRODUCT_ADS");

        // A creative changed after verification (IG-09): the mapping no longer counts as verified.
        InventoryContextFixture changed = new InventoryContextFixture();
        changed.ads.set(0, InventoryContextFixture.ad(InventoryContextFixture.AD_9001, "ad-9001", "SINGLE_VARIANT", 1,
                "VERIFIED", false, "ACTIVE"));
        assertThat(baseline.propose(changed.build()).reasonCodes()).contains("MAPPING_UNVERIFIED");
    }

    @Test
    void everyRecommendationFitsTheContractSchema() {
        InventoryContextFixture fixture = new InventoryContextFixture();
        var envelope = (tools.jackson.databind.node.ObjectNode) com.ecommerceagent.metrics.CanonicalJson.read("""
                {"schemaVersion":"1","proposalId":"7a1c0f9e-2b4d-4c3a-9e8f-0d1b2c3d4e5f","revision":1,
                 "subject":{"type":"OPPORTUNITY","opportunityId":"3f2e1d0c-9b8a-4765-8432-10fedcba9876"},
                 "proposer":{"kind":"BASELINE","name":"inventory-baseline","version":"1.0.0"},"agentRunId":null,
                 "contextSnapshotId":"5b6c7d8e-9f0a-4b1c-8d2e-3f4a5b6c7d8e","createdAt":"2026-11-14T08:06:00Z",
                 "expiresAt":"2026-11-15T08:06:00Z"}
                """);
        envelope.set("recommendation", ProposalJson.write(baseline.propose(fixture.build())));
        assertThat(ProposalSchema.validate(envelope)).isEmpty();
    }
}
