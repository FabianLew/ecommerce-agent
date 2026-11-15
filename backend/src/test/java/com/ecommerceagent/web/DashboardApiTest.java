package com.ecommerceagent.web;

import static com.ecommerceagent.web.IntegrationApiTest.ISSUER;
import static com.ecommerceagent.web.IntegrationApiTest.ORG;
import static com.ecommerceagent.web.IntegrationApiTest.OWNER;
import static com.ecommerceagent.web.IntegrationApiTest.OWNER_B;
import static com.ecommerceagent.web.IntegrationApiTest.STORE_A;
import static com.ecommerceagent.web.IntegrationApiTest.STORE_B;
import static com.ecommerceagent.web.IntegrationApiTest.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import com.ecommerceagent.TestcontainersConfiguration;
import com.ecommerceagent.commerce.Backorders;
import com.ecommerceagent.commerce.CatalogRepository;
import com.ecommerceagent.commerce.CommerceSource;
import com.ecommerceagent.commerce.OrderRepository;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Dashboard API (B-35): seven-day metrics, unavailable values with reasons, attention items and isolation. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class DashboardApiTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    OrderRepository orders;
    @Autowired
    CatalogRepository catalog;
    @Autowired
    StoreAccess storeAccess;

    UUID serum;

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
                  (?, ?, 'owner', 'owner@shop.test'), (?, ?, 'owner-b', 'owner-b@shop.test')
                """).params(OWNER, ISSUER, OWNER_B, ISSUER).update();
        jdbc.sql("""
                INSERT INTO store_membership (organization_id, store_id, user_id, role) VALUES
                  (?, ?, ?, 'OWNER'), (?, ?, ?, 'OWNER')
                """).params(ORG, STORE_A, OWNER, ORG, STORE_B, OWNER_B).update();
        TrustedStoreScope scope = storeAccess.systemScope("test", ORG, STORE_A).orElseThrow();
        catalog.upsertProduct(scope, CommerceSource.WOOCOMMERCE, new ProductData("812", "Serum", null, "simple",
                "publish", Instant.now()));
        serum = catalog.upsertVariant(scope, CommerceSource.WOOCOMMERCE, new VariantData("812", "812", "Serum",
                "SERUM-30", "publish", StockTracking.VARIANT, Backorders.NO, Instant.now()));
        ZoneId zone = ZoneId.of("Europe/Warsaw");
        LocalDate today = LocalDate.now(zone);
        orders.recordCoverage(scope, CommerceSource.WOOCOMMERCE, today.minusDays(60).atStartOfDay(zone).toInstant(),
                Instant.now());
        Instant threeDaysAgo = today.minusDays(3).atStartOfDay(zone).plusHours(12).toInstant();
        Instant tenDaysAgo = today.minusDays(10).atStartOfDay(zone).plusHours(12).toInstant();
        orders.upsert(scope, CommerceSource.WOOCOMMERCE, AnalyticsApiTest.order("1", "200.00", threeDaysAgo, 2));
        orders.upsert(scope, CommerceSource.WOOCOMMERCE, AnalyticsApiTest.order("2", "100.00", tenDaysAgo, 1));
    }


    @Test
    void dashboardComparesTheLastSevenDaysAndExplainsMissingValues() throws Exception {
        mvc.perform(get("/api/v1/stores/{storeId}/dashboard", STORE_A).with(user("owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.timezone").value("Europe/Warsaw"))
                .andExpect(jsonPath("$.metrics[0].key").value("net_sales"))
                .andExpect(jsonPath("$.metrics[0].value").value("200.00"))
                .andExpect(jsonPath("$.metrics[0].comparison.baseValue").value("100.00"))
                .andExpect(jsonPath("$.metrics[1].key").value("qualifying_orders"))
                .andExpect(jsonPath("$.metrics[1].value").value("1"))
                .andExpect(jsonPath("$.metrics[2].key").value("meta_spend"))
                .andExpect(jsonPath("$.metrics[2].value").doesNotExist())
                .andExpect(jsonPath("$.metrics[2].missingComponents[0]").value("META_INSIGHTS_NOT_IMPORTED"))
                .andExpect(jsonPath("$.metrics[3].key").value("contribution_margin_known_costs"))
                .andExpect(jsonPath("$.metrics[3].missingComponents[0]").value("COSTS_MISSING"))
                .andExpect(jsonPath("$.salesTrend.length()").value(7))
                .andExpect(jsonPath("$.decisionsToApprove").value(0))
                .andExpect(jsonPath("$.attentionItems[0].kind").value("ONBOARDING_INCOMPLETE"))
                .andExpect(jsonPath("$.attentionItems[0].code").value("CONNECT_STORE"))
                .andExpect(jsonPath("$.onboardingNextStep").value("CONNECT_STORE"))
                .andExpect(jsonPath("$.integrationHealth").isEmpty())
                .andExpect(jsonPath("$.recentActivities").isArray());
    }

    @Test
    void storesAreIsolated() throws Exception {
        mvc.perform(get("/api/v1/stores/{storeId}/dashboard", STORE_B).with(user("owner-b")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metrics[0].value").doesNotExist());
        mvc.perform(get("/api/v1/stores/{storeId}/dashboard", STORE_A).with(user("owner-b")))
                .andExpect(status().isNotFound());
    }
}
