package com.ecommerceagent.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.ecommerceagent.TestcontainersConfiguration;
import com.ecommerceagent.identity.StoreAccess;
import com.ecommerceagent.identity.TrustedStoreScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** B-20: ads and SKU mappings with a version and verification bound to the creative. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
class AdRepositoryTest {

    static final UUID ORG = UUID.fromString("e0000000-0000-4000-8000-000000000001");
    static final UUID STORE_A = UUID.fromString("e1000000-0000-4000-8000-00000000000a");
    static final UUID STORE_B = UUID.fromString("e1000000-0000-4000-8000-00000000000b");
    static final Instant T0 = Instant.parse("2026-11-14T08:00:00Z");
    static final CommerceSource WOO = CommerceSource.WOOCOMMERCE;

    @Autowired
    AdRepository ads;
    @Autowired
    AdFixtures fixtures;
    @Autowired
    CatalogRepository catalog;
    @Autowired
    StoreAccess storeAccess;
    @Autowired
    JdbcClient jdbc;

    TrustedStoreScope scopeA;
    TrustedStoreScope scopeB;
    UUID serum;
    UUID mug;

    @BeforeEach
    void stores() {
        jdbc.sql("TRUNCATE store_membership, app_user, store, organization CASCADE").update();
        jdbc.sql("INSERT INTO organization (id, name) VALUES (?, 'Ads org')").param(ORG).update();
        jdbc.sql("""
                INSERT INTO store (id, organization_id, name, currency, timezone)
                VALUES (?, ?, 'A', 'PLN', 'Europe/Warsaw'), (?, ?, 'B', 'PLN', 'Europe/Warsaw')
                """).params(STORE_A, ORG, STORE_B, ORG).update();
        scopeA = storeAccess.systemScope("ads-test", ORG, STORE_A).orElseThrow();
        scopeB = storeAccess.systemScope("ads-test", ORG, STORE_B).orElseThrow();
        catalog.upsertProduct(scopeA, WOO, new ProductData("812", "Serum", null, "variable", "publish", T0));
        serum = catalog.upsertVariant(scopeA, WOO, new VariantData("826", "812", "Serum 30 ml", "SERUM-30",
                "publish", StockTracking.VARIANT, Backorders.NO, T0));
        catalog.upsertProduct(scopeA, WOO, new ProductData("14", "Kubek", "MUG", "simple", "publish", T0));
        mug = catalog.upsertVariant(scopeA, WOO, new VariantData("14", "14", "Kubek", "MUG", "publish",
                StockTracking.VARIANT, Backorders.NO, T0));
    }

    @Test
    void mappingVersionChangesOnlyWithTheMapping() {
        UUID ad = ads.upsertAd(scopeA, ad("ad-9001", "fp-44"));
        AdMapping first = ads.saveMapping(scopeA, ad, AdMappingScope.SINGLE_VARIANT, List.of(serum),
                MappingVerification.VERIFIED, T0);
        AdMapping same = ads.saveMapping(scopeA, ad, AdMappingScope.SINGLE_VARIANT, List.of(serum),
                MappingVerification.VERIFIED, T0.plusSeconds(60));
        AdMapping revoked = ads.saveMapping(scopeA, ad, AdMappingScope.SINGLE_VARIANT, List.of(serum),
                MappingVerification.REVOKED, null);

        assertThat(first.version()).isEqualTo(1);
        assertThat(first.verifiedCreativeFingerprint()).isEqualTo("fp-44");
        assertThat(same.version()).isEqualTo(1);
        assertThat(revoked.version()).isEqualTo(2);
        assertThat(revoked.verifiedAt()).isNull();
        assertThat(ads.adsForVariant(scopeA, serum)).extracting(item -> item.mapping().version()).containsExactly(2);
    }

    @Test
    void verificationHoldsOnlyForTheCreativeItWasMadeFor() {
        UUID ad = ads.upsertAd(scopeA, ad("ad-9001", "fp-44"));
        ads.saveMapping(scopeA, ad, AdMappingScope.SINGLE_VARIANT, List.of(serum), MappingVerification.VERIFIED, T0);
        MappedAd before = ads.adsForVariant(scopeA, serum).getFirst();
        assertThat(before.mapping().currentFor(before.ad())).isTrue();

        // The creative changed in the platform (IG-09): the old verification no longer applies.
        ads.upsertAd(scopeA, ad("ad-9001", "fp-45"));
        MappedAd after = ads.adsForVariant(scopeA, serum).getFirst();
        assertThat(after.ad().creativeFingerprint()).isEqualTo("fp-45");
        assertThat(after.mapping().currentFor(after.ad())).isFalse();
    }

    @Test
    void mappingRulesAndStoreIsolation() {
        UUID ad = ads.upsertAd(scopeA, ad("ad-9001", "fp-44"));
        assertThatThrownBy(() -> ads.saveMapping(scopeA, ad, AdMappingScope.SINGLE_VARIANT, List.of(serum, mug),
                MappingVerification.UNVERIFIED, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ads.saveMapping(scopeA, ad, AdMappingScope.MULTI_PRODUCT, List.of(),
                MappingVerification.UNVERIFIED, null)).isInstanceOf(IllegalArgumentException.class);
        UUID foreignVariant = UUID.randomUUID();
        assertThatThrownBy(() -> ads.saveMapping(scopeA, ad, AdMappingScope.SINGLE_VARIANT, List.of(foreignVariant),
                MappingVerification.UNVERIFIED, null)).isInstanceOf(IllegalArgumentException.class);
        ads.saveMapping(scopeA, ad, AdMappingScope.SINGLE_VARIANT, List.of(serum), MappingVerification.VERIFIED, T0);

        // Store B neither sees the ad nor can map it, even with its id.
        assertThat(ads.ad(scopeB, ad)).isEmpty();
        assertThat(ads.adsForVariant(scopeB, serum)).isEmpty();
        assertThatThrownBy(() -> ads.saveMapping(scopeB, ad, AdMappingScope.SINGLE_VARIANT, List.of(serum),
                MappingVerification.VERIFIED, T0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fixtureLoadsDedicatedAndCatalogAdsOnceAndSkipsUnknownSkus() {
        AdFixture fixture = new AdFixture(ORG, STORE_A, List.of(
                new AdFixture.FixtureAd("ad-9001", "act-01", "Serum – dedykowana", AdStatus.ACTIVE, "ACTIVE",
                        "fp-creative-44", AdMappingScope.SINGLE_VARIANT, List.of("SERUM-30"), true),
                new AdFixture.FixtureAd("ad-9002", "act-01", "Katalog", AdStatus.ACTIVE, "ACTIVE", "fp-catalog-07",
                        AdMappingScope.MULTI_PRODUCT, List.of("SERUM-30", "MUG"), false),
                new AdFixture.FixtureAd("ad-9003", "act-01", "Nieznany SKU", AdStatus.ACTIVE, "ACTIVE", "fp-x",
                        AdMappingScope.SINGLE_VARIANT, List.of("NOT-IMPORTED"), true)));

        assertThat(fixtures.load(scopeA, fixture)).hasSize(2);
        assertThat(fixtures.load(scopeA, fixture)).hasSize(2);

        List<MappedAd> forSerum = ads.adsForVariant(scopeA, serum);
        assertThat(forSerum).extracting(item -> item.ad().externalId()).containsExactly("ad-9001", "ad-9002");
        assertThat(forSerum.get(0).mapping().currentFor(forSerum.get(0).ad())).isTrue();
        assertThat(forSerum.get(1).mapping().scope()).isEqualTo(AdMappingScope.MULTI_PRODUCT);
        assertThat(forSerum.get(1).mapping().verification()).isEqualTo(MappingVerification.UNVERIFIED);
        assertThat(forSerum).allMatch(item -> item.mapping().version() == 1);
        assertThat(ads.adsForVariant(scopeA, mug)).extracting(item -> item.ad().externalId())
                .containsExactly("ad-9002");
        assertThatThrownBy(() -> fixtures.load(scopeB, fixture)).isInstanceOf(IllegalArgumentException.class);
    }

    private static AdData ad(String externalId, String fingerprint) {
        return new AdData(externalId, "act-01", "Serum – dedykowana", AdStatus.ACTIVE, "ACTIVE", fingerprint, T0,
                AdSource.FIXTURE);
    }
}
