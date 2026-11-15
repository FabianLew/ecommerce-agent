package com.ecommerceagent.automation.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.ecommerceagent.FixtureDb;
import com.ecommerceagent.TestcontainersConfiguration;
import com.ecommerceagent.automation.events.EventDispatch;
import com.ecommerceagent.automation.notifications.MailProvider;
import com.ecommerceagent.commerce.Backorders;
import com.ecommerceagent.commerce.CatalogRepository;
import com.ecommerceagent.commerce.CommerceSource;
import com.ecommerceagent.commerce.ProductData;
import com.ecommerceagent.commerce.StockTracking;
import com.ecommerceagent.commerce.VariantData;
import com.ecommerceagent.decisions.RiskObservation;
import com.ecommerceagent.decisions.RiskOutcome;
import com.ecommerceagent.identity.StoreAccess;
import com.ecommerceagent.identity.TrustedStoreScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** ADR-0026 point 1: one L0 warning per variant and store-local day below 5 days of confirmed cover. */
@SpringBootTest
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class StockWarningsTest {

    static final UUID ORG = UUID.fromString("c2600000-0000-4000-8000-000000000001");
    static final UUID OWNER = UUID.fromString("c2600000-0000-4000-8000-0000000000a1");

    @MockitoBean
    MailProvider mail;
    @Autowired
    StockWarnings warnings;
    @Autowired
    FixtureDb jdbc;
    @Autowired
    StoreAccess storeAccess;
    @Autowired
    CatalogRepository catalog;

    UUID store;
    UUID variant;
    TrustedStoreScope scope;

    @BeforeEach
    void store() {
        when(mail.send(any())).thenReturn(new MailProvider.Accepted("m"));
        store = UUID.randomUUID();
        jdbc.sql("TRUNCATE store_membership, app_user, store, organization CASCADE").update();
        jdbc.sql("INSERT INTO organization (id, name) VALUES (?, 'Warning org')").param(ORG).update();
        jdbc.sql("""
                INSERT INTO store (id, organization_id, name, currency, timezone)
                VALUES (?, ?, 'Store W', 'PLN', 'Europe/Warsaw')
                """).params(store, ORG).update();
        jdbc.sql("INSERT INTO app_user (id, issuer, subject, email) VALUES (?, 'https://issuer.test', 'w-owner', "
                + "'w@shop.test')").param(OWNER).update();
        jdbc.sql("INSERT INTO store_membership (organization_id, store_id, user_id, role) VALUES (?, ?, ?, 'OWNER')")
                .params(ORG, store, OWNER).update();
        scope = storeAccess.systemScope(EventDispatch.SYNC_SERVICE, ORG, store).orElseThrow();
        Instant created = Instant.parse("2026-11-01T00:00:00Z");
        catalog.upsertProduct(scope, CommerceSource.WOOCOMMERCE, new ProductData("1", "Serum", null, "variable",
                "publish", created));
        variant = catalog.upsertVariant(scope, CommerceSource.WOOCOMMERCE, new VariantData("2", "1", "Serum 30 ml",
                "SERUM-30", "publish", StockTracking.VARIANT, Backorders.NO, created));
    }

    RiskObservation observation(Instant at, String cover, RiskOutcome outcome) {
        return new RiskObservation(UUID.randomUUID(), variant, at, at, 12, cover == null ? null : new BigDecimal(cover),
                outcome, null, new BigDecimal("3"), "inventory-risk/2", null);
    }

    long warnings() {
        return jdbc.sql("SELECT count(*) FROM operator_notification WHERE store_id = ? AND kind = 'STOCK_WARNING'")
                .param(store).query(Long.class).single();
    }

    @Test
    void oneWarningPerVariantAndStoreDayBelowFiveDays() {
        // 23:10 UTC on 14.11 is already 15.11 in Warsaw (UTC+1); 23:30 UTC on 15.11 is 16.11.
        Instant first = Instant.parse("2026-11-14T23:10:00Z");
        assertThat(warnings.warnIfLow(scope, observation(first, "4.2", RiskOutcome.NOT_AT_RISK))).isTrue();
        assertThat(warnings.warnIfLow(scope, observation(first.plusSeconds(3600), "2.5", RiskOutcome.AT_RISK)))
                .isTrue();
        assertThat(warnings()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT dedup_key FROM operator_notification WHERE store_id = ?").param(store)
                .query(String.class).single()).isEqualTo("stock-warning/" + variant + "/2026-11-15");
        assertThat(jdbc.sql("SELECT parameters ->> 'daysOfCover' FROM operator_notification WHERE store_id = ?")
                .param(store).query(String.class).single()).isEqualTo("4.2");

        warnings.warnIfLow(scope, observation(Instant.parse("2026-11-15T23:30:00Z"), "4", RiskOutcome.NOT_AT_RISK));
        assertThat(warnings()).isEqualTo(2);
        // The owner is told by e-mail; nothing is proposed.
        assertThat(jdbc.sql("SELECT count(*) FROM notification_delivery WHERE store_id = ?").param(store)
                .query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM opportunity WHERE store_id = ?").param(store).query(Long.class)
                .single()).isZero();
    }

    @Test
    void noWarningAtFiveDaysOrForAnUnknownObservation() {
        Instant at = Instant.parse("2026-11-14T10:00:00Z");
        assertThat(warnings.warnIfLow(scope, observation(at, "5", RiskOutcome.NOT_AT_RISK))).isFalse();
        assertThat(warnings.warnIfLow(scope, observation(at, "1", RiskOutcome.UNKNOWN))).isFalse();
        assertThat(warnings.warnIfLow(scope, observation(at, null, RiskOutcome.UNKNOWN))).isFalse();
        assertThat(warnings()).isZero();
    }
}
