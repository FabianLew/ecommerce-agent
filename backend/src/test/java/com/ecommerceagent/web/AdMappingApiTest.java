package com.ecommerceagent.web;

import static com.ecommerceagent.web.IntegrationApiTest.ANALYST;
import static com.ecommerceagent.web.IntegrationApiTest.ISSUER;
import static com.ecommerceagent.web.IntegrationApiTest.ORG;
import static com.ecommerceagent.web.IntegrationApiTest.OWNER;
import static com.ecommerceagent.web.IntegrationApiTest.OWNER_B;
import static com.ecommerceagent.web.IntegrationApiTest.STORE_A;
import static com.ecommerceagent.web.IntegrationApiTest.STORE_B;
import static com.ecommerceagent.web.IntegrationApiTest.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import com.ecommerceagent.TestcontainersConfiguration;
import com.ecommerceagent.commerce.AdData;
import com.ecommerceagent.commerce.AdRepository;
import com.ecommerceagent.commerce.AdSource;
import com.ecommerceagent.commerce.AdStatus;
import com.ecommerceagent.commerce.Backorders;
import com.ecommerceagent.commerce.CatalogRepository;
import com.ecommerceagent.commerce.CommerceSource;
import com.ecommerceagent.commerce.ProductData;
import com.ecommerceagent.commerce.StockTracking;
import com.ecommerceagent.commerce.VariantData;
import com.ecommerceagent.identity.StoreAccess;
import com.ecommerceagent.identity.TrustedStoreScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Ad mapping confirmation (B-32). Runtime safety: only STORE_MANAGE confirms, variants of another store are refused,
 * a stale version is a conflict, and a confirmation holds only for the creative it was made for (IG-09).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class AdMappingApiTest {

    static final Instant T0 = Instant.parse("2026-11-10T10:00:00Z");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    AdRepository ads;

    @Autowired
    CatalogRepository catalog;

    @Autowired
    StoreAccess storeAccess;

    TrustedStoreScope scopeA;
    UUID ad;
    UUID serum;
    UUID cream;
    UUID foreignVariant;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE store_membership, app_user, store, organization, audit_event CASCADE").update();
        jdbc.sql("INSERT INTO organization (id, name) VALUES (?, 'Org')").params(ORG).update();
        jdbc.sql("""
                INSERT INTO store (id, organization_id, name, currency, timezone) VALUES
                  (?, ?, 'Store A', 'PLN', 'Europe/Warsaw'), (?, ?, 'Store B', 'PLN', 'Europe/Warsaw')
                """).params(STORE_A, ORG, STORE_B, ORG).update();
        jdbc.sql("""
                INSERT INTO app_user (id, issuer, subject, email) VALUES
                  (?, ?, 'owner', 'owner@shop.test'), (?, ?, 'analyst', 'analyst@shop.test'),
                  (?, ?, 'owner-b', 'owner-b@shop.test')
                """).params(OWNER, ISSUER, ANALYST, ISSUER, OWNER_B, ISSUER).update();
        jdbc.sql("""
                INSERT INTO store_membership (organization_id, store_id, user_id, role) VALUES
                  (?, ?, ?, 'OWNER'), (?, ?, ?, 'ANALYST'), (?, ?, ?, 'OWNER')
                """).params(ORG, STORE_A, OWNER, ORG, STORE_A, ANALYST, ORG, STORE_B, OWNER_B).update();
        scopeA = storeAccess.systemScope("test", ORG, STORE_A).orElseThrow();
        TrustedStoreScope scopeB = storeAccess.systemScope("test", ORG, STORE_B).orElseThrow();
        serum = variant(scopeA, "826", "SERUM-30");
        cream = variant(scopeA, "827", "CREAM-50");
        foreignVariant = variant(scopeB, "900", "OTHER");
        ad = ads.upsertAd(scopeA, adData("c-1"));
    }

    private UUID variant(TrustedStoreScope scope, String id, String sku) {
        catalog.upsertProduct(scope, CommerceSource.WOOCOMMERCE, new ProductData("p" + id, "Product " + sku, null,
                "simple", "publish", T0));
        return catalog.upsertVariant(scope, CommerceSource.WOOCOMMERCE, new VariantData(id, "p" + id, sku, sku,
                "publish", StockTracking.VARIANT, Backorders.NO, T0));
    }

    private static AdData adData(String creative) {
        return new AdData("9001", "act_123", "Serum 30 ml", AdStatus.ACTIVE, "ACTIVE", "meta-creative:" + creative,
                Instant.now(), AdSource.META_API);
    }

    private ResultActions confirm(String subject, UUID store, UUID adId, String scope, UUID variant, boolean verified,
            int version) throws Exception {
        return mvc.perform(put("/api/v1/stores/{storeId}/ads/{adId}/mapping", store, adId).with(user(subject))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope\":\"" + scope + "\",\"variantIds\":[\"" + variant + "\"],\"verified\":" + verified
                        + ",\"expectedVersion\":" + version + "}"));
    }

    @Test
    void anOwnerConfirmsAMappingForTheCurrentCreative() throws Exception {
        mvc.perform(get("/api/v1/stores/{storeId}/ads", STORE_A).with(user("owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].mappingState").value("NONE"))
                .andExpect(jsonPath("$.items[0].allowedActions[0]").value("CONFIRM_MAPPING"));

        confirm("owner", STORE_A, ad, "SINGLE_VARIANT", serum, true, 0).andExpect(status().isOk())
                .andExpect(jsonPath("$.mappingState").value("VERIFIED"))
                .andExpect(jsonPath("$.mapping.version").value(1))
                .andExpect(jsonPath("$.mapping.variants[0].sku").value("SERUM-30"))
                .andExpect(jsonPath("$.allowedActions[1]").value("REVOKE_MAPPING"));
        assertThat(jdbc.sql("SELECT action FROM audit_event WHERE action LIKE 'AD_MAPPING%'").query(String.class).list())
                .containsExactly("AD_MAPPING_VERIFIED");

        // Confirming the same mapping again refreshes the verification time but keeps the version.
        Instant first = jdbc.sql("SELECT verified_at FROM ad_mapping").query(java.sql.Timestamp.class).single()
                .toInstant();
        confirm("owner", STORE_A, ad, "SINGLE_VARIANT", serum, true, 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.mapping.version").value(1));
        assertThat(jdbc.sql("SELECT verified_at FROM ad_mapping").query(java.sql.Timestamp.class).single()
                .toInstant()).isAfterOrEqualTo(first);

        // A new creative invalidates the confirmation (IG-09); confirming again creates a new version.
        ads.upsertAd(scopeA, adData("c-2"));
        mvc.perform(get("/api/v1/stores/{storeId}/ads/{adId}", STORE_A, ad).with(user("analyst")))
                .andExpect(jsonPath("$.mappingState").value("CREATIVE_CHANGED"))
                .andExpect(jsonPath("$.allowedActions").isEmpty());
        confirm("owner", STORE_A, ad, "SINGLE_VARIANT", serum, true, 1)
                .andExpect(jsonPath("$.mappingState").value("VERIFIED"))
                .andExpect(jsonPath("$.mapping.version").value(2));
    }

    @Test
    void aStaleVersionIsAConflictAndRevokingNeedsTheCurrentVersion() throws Exception {
        confirm("owner", STORE_A, ad, "SINGLE_VARIANT", serum, true, 0).andExpect(status().isOk());
        confirm("owner", STORE_A, ad, "SINGLE_VARIANT", cream, true, 0).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MAPPING_CHANGED"));
        mvc.perform(post("/api/v1/stores/{storeId}/ads/{adId}/mapping/revocation", STORE_A, ad).with(user("owner"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mappingState").value("REVOKED"))
                .andExpect(jsonPath("$.mapping.version").value(2));
    }

    @Test
    void validatesTheScopeAndRefusesVariantsOfAnotherStore() throws Exception {
        confirm("owner", STORE_A, ad, "SINGLE_VARIANT", foreignVariant, true, 0)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].code").value("UNKNOWN_VARIANT"));
        mvc.perform(put("/api/v1/stores/{storeId}/ads/{adId}/mapping", STORE_A, ad).with(user("owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"SINGLE_VARIANT\",\"variantIds\":[\"" + serum + "\",\"" + cream
                                + "\"],\"verified\":true,\"expectedVersion\":0}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].code").value("SINGLE_VARIANT_NEEDS_ONE"));
        confirm("owner", STORE_A, ad, "MULTI_PRODUCT", serum, false, 0).andExpect(status().isOk())
                .andExpect(jsonPath("$.mappingState").value("UNVERIFIED"));
    }

    @Test
    void onlyStoreManageConfirmsAndAnotherStoreSeesNothing() throws Exception {
        confirm("analyst", STORE_A, ad, "SINGLE_VARIANT", serum, true, 0).andExpect(status().isForbidden());
        confirm("owner-b", STORE_B, ad, "SINGLE_VARIANT", serum, true, 0).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/stores/{storeId}/ads", STORE_B).with(user("owner-b")))
                .andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get("/api/v1/stores/{storeId}/ads/{adId}", STORE_B, ad).with(user("owner-b")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/stores/{storeId}/variants", STORE_B).with(user("owner-b")))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].sku").value("OTHER"));
        assertThat(jdbc.sql("SELECT count(*) FROM ad_mapping").query(Integer.class).single()).isZero();
    }
}
