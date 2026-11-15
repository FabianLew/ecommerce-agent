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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import com.ecommerceagent.TestcontainersConfiguration;
import com.ecommerceagent.commerce.Backorders;
import com.ecommerceagent.commerce.CatalogRepository;
import com.ecommerceagent.commerce.CommerceSource;
import com.ecommerceagent.commerce.OrderData;
import com.ecommerceagent.commerce.OrderLineData;
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

/** Analytics API (B-34, ADR-0019): MetricDTO shape, comparison, paging, period limits and store isolation. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class AnalyticsApiTest {

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
        jdbc.sql("TRUNCATE store_membership, app_user, store, organization CASCADE").update();
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
        Instant tenDaysAgo = today.minusDays(10).atStartOfDay(zone).plusHours(12).toInstant();
        Instant fortyDaysAgo = today.minusDays(40).atStartOfDay(zone).plusHours(12).toInstant();
        orders.upsert(scope, CommerceSource.WOOCOMMERCE, order("1", "200.00", tenDaysAgo, 2));
        orders.upsert(scope, CommerceSource.WOOCOMMERCE, order("2", "100.00", fortyDaysAgo, 1));
    }

    static OrderData order(String id, String total, Instant at, int units) {
        return new OrderData(id, "completed", "PLN", new BigDecimal(total), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, at, at, at, at, List.of(new OrderLineData("l" + id, "812", "812", "SERUM-30", units,
                        new BigDecimal(total), BigDecimal.ZERO)));
    }

    @Test
    void salesReturnMetricDtosWithAComparisonToThePreviousPeriod() throws Exception {
        mvc.perform(get("/api/v1/stores/{storeId}/analytics/sales", STORE_A).with(user("owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.timezone").value("Europe/Warsaw"))
                .andExpect(jsonPath("$.meta.currency").value("PLN"))
                .andExpect(jsonPath("$.metrics[0].key").value("net_sales"))
                .andExpect(jsonPath("$.metrics[0].value").value("200.00"))
                .andExpect(jsonPath("$.metrics[0].currency").value("PLN"))
                .andExpect(jsonPath("$.metrics[0].definitionVersion").value("net_sales@v1"))
                .andExpect(jsonPath("$.metrics[0].missingComponents[0]").value("REFUNDS_NOT_INCLUDED"))
                .andExpect(jsonPath("$.metrics[0].comparison.baseValue").value("100.00"))
                .andExpect(jsonPath("$.metrics[0].comparison.percentDifference").value("100.0"))
                .andExpect(jsonPath("$.metrics[1].key").value("qualifying_orders"))
                .andExpect(jsonPath("$.metrics[1].currency").doesNotExist())
                .andExpect(jsonPath("$.series.length()").value(30));
    }

    @Test
    void productsArePagedAndTheDetailLinksAdsAndDecisions() throws Exception {
        mvc.perform(get("/api/v1/stores/{storeId}/analytics/products", STORE_A).with(user("owner")))
                .andExpect(jsonPath("$.items[0].variant.sku").value("SERUM-30"))
                .andExpect(jsonPath("$.items[0].units").value(2))
                .andExpect(jsonPath("$.items[0].daysOfCover.key").value("days_of_cover"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.hasMore").value(false));
        mvc.perform(get("/api/v1/stores/{storeId}/analytics/products/{id}", STORE_A, serum).with(user("owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variant.sku").value("SERUM-30"))
                .andExpect(jsonPath("$.ads").isEmpty())
                .andExpect(jsonPath("$.decisions").isEmpty());
        mvc.perform(get("/api/v1/stores/{storeId}/analytics/products", STORE_A).param("limit", "500")
                .with(user("owner"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/stores/{storeId}/analytics/products", STORE_A).param("sort", "PRICE")
                .with(user("owner"))).andExpect(status().isBadRequest());
    }

    @Test
    void periodsAreLimitedAndStoresIsolated() throws Exception {
        mvc.perform(get("/api/v1/stores/{storeId}/analytics/sales", STORE_A).param("from", "2025-01-01")
                .param("to", "2026-06-01").with(user("owner"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PERIOD"));
        mvc.perform(get("/api/v1/stores/{storeId}/analytics/sales", STORE_A).param("from", "x").with(user("owner")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/stores/{storeId}/analytics/sales", STORE_B).with(user("owner-b")))
                .andExpect(jsonPath("$.metrics[0].value").doesNotExist())
                .andExpect(jsonPath("$.metrics[0].missingComponents[0]").value("SALES_HISTORY_INCOMPLETE"));
        mvc.perform(get("/api/v1/stores/{storeId}/analytics/products/{id}", STORE_B, serum).with(user("owner-b")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/stores/{storeId}/analytics/sales", STORE_A).with(user("owner-b")))
                .andExpect(status().isNotFound());
    }
}
