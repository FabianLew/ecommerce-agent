package com.ecommerceagent.decisions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.ecommerceagent.FixtureDb;
import com.ecommerceagent.TestcontainersConfiguration;
import com.ecommerceagent.commerce.AdData;
import com.ecommerceagent.commerce.AdMappingScope;
import com.ecommerceagent.commerce.AdRepository;
import com.ecommerceagent.commerce.AdSource;
import com.ecommerceagent.commerce.AdStatus;
import com.ecommerceagent.commerce.Backorders;
import com.ecommerceagent.commerce.CatalogRepository;
import com.ecommerceagent.commerce.CommerceSource;
import com.ecommerceagent.commerce.MappedAd;
import com.ecommerceagent.commerce.MappingVerification;
import com.ecommerceagent.commerce.ProductData;
import com.ecommerceagent.commerce.StockTracking;
import com.ecommerceagent.commerce.VariantData;
import com.ecommerceagent.identity.StoreAccess;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.metrics.AdEvidence;
import com.ecommerceagent.metrics.CanonicalJson;
import com.ecommerceagent.metrics.Evidence;
import com.ecommerceagent.metrics.EvidenceDraft;
import com.ecommerceagent.metrics.EvidenceStore;
import com.ecommerceagent.metrics.EvidenceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * IG-10 (03 §12, ADR-0012): a proposal citing evidence it may not rely on, or targeting an ad the snapshot does
 * not map to the episode's variant, is rejected before any policy evaluation and never becomes a proposal.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
class ProposalValidatorTest {

    static final UUID ORG = UUID.fromString("a7000000-0000-4000-8000-000000000001");
    static final UUID STORE_A = UUID.fromString("a7100000-0000-4000-8000-00000000000a");
    static final UUID STORE_B = UUID.fromString("a7100000-0000-4000-8000-00000000000b");
    static final Instant T0 = Instant.parse("2026-11-14T08:00:00Z");
    static final Proposer BASELINE = new Proposer(ProposerKind.BASELINE, "inventory-baseline", "1.0.0");
    static final Duration TTL = Duration.ofHours(24);

    @Autowired
    DecisionProposals proposals;
    @Autowired
    Opportunities opportunities;
    @Autowired
    ContextSnapshots snapshots;
    @Autowired
    EvidenceStore evidence;
    @Autowired
    AdEvidence adEvidence;
    @Autowired
    AdRepository ads;
    @Autowired
    CatalogRepository catalog;
    @Autowired
    StoreAccess storeAccess;
    @Autowired
    FixtureDb jdbc;

    TrustedStoreScope scopeA;
    TrustedStoreScope scopeB;
    UUID serum;
    UUID mug;
    UUID dedicatedAd;
    UUID catalogAd;
    UUID mugAd;
    Opportunity episode;
    Evidence stock;
    Evidence mugStock;
    Evidence outsideSnapshot;
    Evidence foreign;
    AdEvidence.MappedAdEvidence dedicated;
    ContextSnapshot snapshot;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE store_membership, app_user, store, organization CASCADE").update();
        jdbc.sql("INSERT INTO organization (id, name) VALUES (?, 'Proposal org')").param(ORG).update();
        jdbc.sql("""
                INSERT INTO store (id, organization_id, name, currency, timezone)
                VALUES (?, ?, 'A', 'PLN', 'Europe/Warsaw'), (?, ?, 'B', 'PLN', 'Europe/Warsaw')
                """).params(STORE_A, ORG, STORE_B, ORG).update();
        scopeA = storeAccess.systemScope("proposal-test", ORG, STORE_A).orElseThrow();
        scopeB = storeAccess.systemScope("proposal-test", ORG, STORE_B).orElseThrow();

        catalog.upsertProduct(scopeA, CommerceSource.WOOCOMMERCE, new ProductData("812", "Serum", null, "variable",
                "publish", T0));
        serum = catalog.upsertVariant(scopeA, CommerceSource.WOOCOMMERCE, new VariantData("826", "812",
                "Serum 30 ml", "SERUM-30", "publish", StockTracking.VARIANT, Backorders.NO, T0));
        catalog.upsertProduct(scopeA, CommerceSource.WOOCOMMERCE, new ProductData("14", "Kubek", "MUG", "simple",
                "publish", T0));
        mug = catalog.upsertVariant(scopeA, CommerceSource.WOOCOMMERCE, new VariantData("14", "14", "Kubek", "MUG",
                "publish", StockTracking.VARIANT, Backorders.NO, T0));
        dedicatedAd = ad("ad-9001", AdMappingScope.SINGLE_VARIANT, List.of(serum), MappingVerification.VERIFIED);
        catalogAd = ad("ad-9002", AdMappingScope.MULTI_PRODUCT, List.of(serum, mug), MappingVerification.UNVERIFIED);
        mugAd = ad("ad-9003", AdMappingScope.SINGLE_VARIANT, List.of(mug), MappingVerification.VERIFIED);

        episode = opportunities.open(scopeA, OpportunityCategory.INVENTORY_RISK, Opportunity.PRODUCT_VARIANT, serum)
                .opportunity();
        stock = stockEvidence(scopeA, serum, 18);
        mugStock = stockEvidence(scopeA, mug, 40);
        outsideSnapshot = stockEvidence(scopeA, serum, 17);
        foreign = stockEvidence(scopeB, serum, 18);
        List<UUID> ids = new ArrayList<>(List.of(stock.id(), mugStock.id()));
        for (MappedAd mapped : ads.adsForVariant(scopeA, serum)) {
            AdEvidence.MappedAdEvidence recorded = adEvidence.record(scopeA, mapped);
            ids.add(recorded.state().id());
            ids.add(recorded.mapping().id());
            if (mapped.ad().id().equals(dedicatedAd)) {
                dedicated = recorded;
            }
        }
        for (MappedAd mapped : ads.adsForVariant(scopeA, mug)) {
            if (mapped.ad().id().equals(mugAd)) {
                AdEvidence.MappedAdEvidence recorded = adEvidence.record(scopeA, mapped);
                ids.add(recorded.state().id());
                ids.add(recorded.mapping().id());
            }
        }
        snapshot = snapshots.create(scopeA, episode.id(), "inventory-context/1", T0,
                CanonicalJson.read("{\"variantId\":\"" + serum + "\"}"), ids);
    }

    @Test
    void validRecommendationBecomesARevisionAndSupersedesThePreviousOne() {
        ProposalSubmission first = submit(pause(dedicatedAd, stock, dedicated.state(), dedicated.mapping()));
        assertThat(first.errors()).isEmpty();
        assertThat(first.proposal().proposal().revision()).isEqualTo(1);
        assertThat(first.proposal().status()).isEqualTo(ProposalStatus.PROPOSED);
        assertThat(first.proposal().payloadHash())
                .isEqualTo(CanonicalJson.hash(ProposalJson.write(first.proposal().proposal())));

        ProposalSubmission second = submit(pause(dedicatedAd, stock));
        assertThat(second.proposal().proposal().revision()).isEqualTo(2);
        assertThat(proposals.ofOpportunity(scopeA, episode.id())).extracting(StoredProposal::status)
                .containsExactly(ProposalStatus.SUPERSEDED, ProposalStatus.PROPOSED);
        assertThat(proposals.find(scopeB, second.proposal().proposal().proposalId())).isEmpty();

        // Content is immutable and a final status stays final.
        UUID id = second.proposal().proposal().proposalId();
        assertThatThrownBy(() -> jdbc.sql("UPDATE decision_proposal SET content = '{}' WHERE id = ?").param(id)
                .update()).hasMessageContaining("content is immutable");
        assertThat(proposals.close(scopeA, id, ProposalStatus.EXPIRED)).isTrue();
        assertThat(proposals.close(scopeA, id, ProposalStatus.APPROVED)).isFalse();
        assertThatThrownBy(() -> jdbc.sql("UPDATE decision_proposal SET status = 'PROPOSED' WHERE id = ?")
                .param(id).update()).hasMessageContaining("is final");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM decision_proposal WHERE id = ?").param(id).update())
                .hasMessageContaining("immutable");
    }

    @Test
    void ig10_evidenceTheProposalMayNotRelyOnIsRejected() {
        assertRejected(pause(dedicatedAd, stock, ref(UUID.randomUUID(), 1)), "EVIDENCE_UNKNOWN");
        assertRejected(pause(dedicatedAd, stock, outsideSnapshot), "EVIDENCE_NOT_IN_SNAPSHOT");
        // Another store's evidence does not exist for this store, even with its id.
        assertRejected(pause(dedicatedAd, stock, foreign), "EVIDENCE_UNKNOWN");
        assertRejected(pause(dedicatedAd, ref(stock.id(), 2)), "EVIDENCE_VERSION_MISMATCH");
        // In the snapshot, but about another variant than the episode's.
        assertRejected(pause(dedicatedAd, stock, mugStock), "EVIDENCE_UNRELATED");

        assertThat(proposals.ofOpportunity(scopeA, episode.id())).isEmpty();
        assertThat(proposals.rejections(scopeA, episode.id())).hasSize(5);
    }

    @Test
    void ig10_targetMustBeAnAdTheSnapshotMapsToTheVariant() {
        assertRejected(pause(UUID.randomUUID(), stock), "TARGET_UNKNOWN");
        assertRejected(pause(mugAd, stock), "TARGET_NOT_MAPPED");
        ObjectNode campaign = pause(dedicatedAd, stock);
        campaign.withObject("action").put("targetType", "META_CAMPAIGN");
        assertRejected(campaign, ProposalSchema.SCHEMA_VIOLATION);
        // The catalog ad is mapped to the variant, so it is a valid target here; the policy decides about it.
        assertThat(submit(pause(catalogAd, stock)).accepted()).isTrue();
    }

    @Test
    void ig10_domainRulesOfTheProcess() {
        ObjectNode unknownReason = pause(dedicatedAd, stock);
        unknownReason.withArray("reasonCodes").add("BECAUSE_I_SAID_SO");
        assertRejected(unknownReason, "REASON_CODE_UNKNOWN");

        ObjectNode monitor = recommendation("MONITOR", stock);
        monitor.putObject("monitorPlan").put("nextReviewAt", Instant.now().plus(Duration.ofDays(30)).toString())
                .putArray("conditions").add("COVER_BELOW_THRESHOLD");
        assertRejected(monitor, "MONITOR_REVIEW_OUT_OF_RANGE");

        ObjectNode insufficient = recommendation("INSUFFICIENT_DATA", stock);
        insufficient.putArray("missingRequirements").add("A_CRYSTAL_BALL");
        assertRejected(insufficient, "MISSING_REQUIREMENT_UNKNOWN");

        // Already expired when validated.
        ProposalSubmission expired = proposals.submit(scopeA, episode.id(), snapshot.id(), BASELINE, null,
                pause(dedicatedAd, stock), Duration.ZERO);
        assertThat(expired.codes()).contains("PROPOSAL_EXPIRED");

        ObjectNode decimal = pause(dedicatedAd, stock);
        decimal.put("summary", 2.57);
        assertRejected(decimal, ProposalSchema.SCHEMA_VIOLATION);
        assertThat(proposals.ofOpportunity(scopeA, episode.id())).isEmpty();
    }

    @Test
    void ig10_episodeAndSnapshotMustBelongToThisStoreAndEpisode() {
        // Store B submits for store A's episode: unknown there, and the attempt keeps no link to it.
        ProposalSubmission foreignEpisode = proposals.submit(scopeB, episode.id(), snapshot.id(), BASELINE, null,
                pause(dedicatedAd, stock), TTL);
        assertThat(foreignEpisode.codes()).containsExactly("OPPORTUNITY_UNKNOWN");
        assertThat(jdbc.sql("SELECT count(*) FROM proposal_rejection WHERE store_id = ? AND opportunity_id IS NULL")
                .param(STORE_B).query(Long.class).single()).isEqualTo(1);

        ContextSnapshot unrelated = snapshots.create(scopeA, null, "inventory-context/1", T0,
                CanonicalJson.read("{}"), List.of(stock.id()));
        ProposalSubmission otherSnapshot = proposals.submit(scopeA, episode.id(), unrelated.id(), BASELINE, null,
                pause(dedicatedAd, stock), TTL);
        assertThat(otherSnapshot.codes()).contains("SNAPSHOT_OTHER_OPPORTUNITY");

        ProposalSubmission noSnapshot = proposals.submit(scopeA, episode.id(), UUID.randomUUID(), BASELINE, null,
                pause(dedicatedAd, stock), TTL);
        assertThat(noSnapshot.codes()).contains("SNAPSHOT_UNKNOWN");

        opportunities.transition(scopeA, episode.id(), OpportunityStatus.CLOSED, "TEST");
        assertThat(submit(pause(dedicatedAd, stock)).codes()).contains("OPPORTUNITY_NOT_ACTIVE");
    }

    private void assertRejected(ObjectNode recommendation, String code) {
        ProposalSubmission submission = submit(recommendation);
        assertThat(submission.accepted()).as(code).isFalse();
        assertThat(submission.codes()).as(submission.errors().toString()).contains(code);
        assertThat(submission.rejectionId()).isNotNull();
    }

    private ProposalSubmission submit(ObjectNode recommendation) {
        return proposals.submit(scopeA, episode.id(), snapshot.id(), BASELINE, null, recommendation, TTL);
    }

    private ObjectNode pause(UUID target, Object... evidenceItems) {
        ObjectNode node = recommendation("RECOMMEND_ACTION", evidenceItems);
        ObjectNode action = node.putObject("action");
        action.put("type", "PAUSE_AD");
        action.put("targetType", "META_AD");
        action.put("targetId", target.toString());
        action.putObject("parameters").put("desiredStatus", "PAUSED");
        node.withArray("reasonCodes").add("DEDICATED_AD_ACTIVE");
        return node;
    }

    private static ObjectNode recommendation(String disposition, Object... evidenceItems) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("disposition", disposition);
        node.putNull("action");
        node.putNull("monitorPlan");
        var refs = node.putArray("evidence");
        for (Object item : evidenceItems) {
            ObjectNode ref = item instanceof Evidence stored ? ref(stored.id(), stored.version()) : (ObjectNode) item;
            refs.add(ref);
        }
        node.putArray("missingRequirements");
        node.putArray("reasonCodes").add("COVER_BELOW_THRESHOLD");
        node.put("summary", "Serum 30 ml covers 2.57 days of sales.");
        node.putArray("uncertainties");
        return node;
    }

    private static ObjectNode ref(UUID id, int version) {
        return JsonNodeFactory.instance.objectNode().put("evidenceId", id.toString()).put("version", version);
    }

    private UUID ad(String externalId, AdMappingScope scope, List<UUID> variants, MappingVerification verification) {
        UUID id = ads.upsertAd(scopeA, new AdData(externalId, "act-01", externalId, AdStatus.ACTIVE, "ACTIVE",
                "fp-" + externalId, T0, AdSource.FIXTURE));
        ads.saveMapping(scopeA, id, scope, variants, verification,
                verification == MappingVerification.VERIFIED ? T0 : null);
        return id;
    }

    private Evidence stockEvidence(TrustedStoreScope scope, UUID variant, int available) {
        ObjectNode content = JsonNodeFactory.instance.objectNode();
        content.put("availableToSell", available);
        return evidence.record(scope, new EvidenceDraft(EvidenceType.STOCK_READING, EvidenceDraft.variantRef(variant),
                null, content, List.of(), T0, T0, T0));
    }
}
