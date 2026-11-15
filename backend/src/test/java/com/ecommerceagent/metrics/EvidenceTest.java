package com.ecommerceagent.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.ecommerceagent.FixtureDb;
import com.ecommerceagent.TestcontainersConfiguration;
import com.ecommerceagent.commerce.Backorders;
import com.ecommerceagent.commerce.CatalogRepository;
import com.ecommerceagent.commerce.CommerceSource;
import com.ecommerceagent.commerce.InventoryReading;
import com.ecommerceagent.commerce.InventoryRepository;
import com.ecommerceagent.commerce.ObservationSource;
import com.ecommerceagent.commerce.ProductData;
import com.ecommerceagent.commerce.ReservationSemantics;
import com.ecommerceagent.commerce.StockStatus;
import com.ecommerceagent.commerce.StockTracking;
import com.ecommerceagent.commerce.VariantData;
import com.ecommerceagent.decisions.ContextSnapshot;
import com.ecommerceagent.decisions.ContextSnapshots;
import com.ecommerceagent.identity.StoreAccess;
import com.ecommerceagent.identity.TrustedStoreScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * B-18 (ADR-0011): evidence and context snapshots are immutable, hashed from a canonical record and visible
 * only within their store.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
class EvidenceTest {

    static final UUID ORG = UUID.fromString("d0000000-0000-4000-8000-000000000001");
    static final UUID STORE_A = UUID.fromString("d1000000-0000-4000-8000-00000000000a");
    static final UUID STORE_B = UUID.fromString("d1000000-0000-4000-8000-00000000000b");
    static final Instant T0 = Instant.parse("2026-11-14T08:00:15.123456Z");

    @Autowired
    EvidenceStore evidence;
    @Autowired
    InventoryEvidence inventoryEvidence;
    @Autowired
    InventoryMetrics metrics;
    @Autowired
    ContextSnapshots snapshots;
    @Autowired
    CatalogRepository catalog;
    @Autowired
    InventoryRepository inventory;
    @Autowired
    StoreAccess storeAccess;
    @Autowired
    FixtureDb jdbc;

    TrustedStoreScope scopeA;
    TrustedStoreScope scopeB;

    @BeforeEach
    void stores() {
        jdbc.sql("TRUNCATE store_membership, app_user, store, organization CASCADE").update();
        jdbc.sql("INSERT INTO organization (id, name) VALUES (?, 'Evidence org')").param(ORG).update();
        jdbc.sql("""
                INSERT INTO store (id, organization_id, name, currency, timezone)
                VALUES (?, ?, 'A', 'PLN', 'Europe/Warsaw'), (?, ?, 'B', 'PLN', 'Europe/Warsaw')
                """).params(STORE_A, ORG, STORE_B, ORG).update();
        scopeA = storeAccess.systemScope("evidence-test", ORG, STORE_A).orElseThrow();
        scopeB = storeAccess.systemScope("evidence-test", ORG, STORE_B).orElseThrow();
    }

    @Test
    void canonicalJsonSortsKeysAndRejectsDecimalNumbers() {
        JsonNode one = CanonicalJson.read("{\"b\":1,\"a\":{\"y\":[1,\"x\"],\"x\":null},\"c\":true}");
        JsonNode other = CanonicalJson.read("{ \"c\" : true, \"a\" : {\"x\":null, \"y\":[1, \"x\"]}, \"b\" : 1 }");

        assertThat(CanonicalJson.write(one)).isEqualTo("{\"a\":{\"x\":null,\"y\":[1,\"x\"]},\"b\":1,\"c\":true}");
        assertThat(CanonicalJson.hash(one)).isEqualTo(CanonicalJson.hash(other)).startsWith("sha256:").hasSize(71);
        assertThatThrownBy(() -> CanonicalJson.write(CanonicalJson.read("{\"v\":2.57}")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void identicalContentIsOneEvidenceAndDifferentContentIsAnother() {
        Evidence first = evidence.record(scopeA, draft("18"));
        Evidence again = evidence.record(scopeA, draft("18"));
        Evidence other = evidence.record(scopeA, draft("17"));

        assertThat(again.id()).isEqualTo(first.id());
        assertThat(other.id()).isNotEqualTo(first.id());
        assertThat(first.version()).isEqualTo(1);
        assertThat(first.observedAt()).isEqualTo(Instant.parse("2026-11-14T08:00:15Z"));
        assertThat(evidence.verify(scopeA, first)).isTrue();
        // The same facts in another store are another store's evidence.
        assertThat(evidence.record(scopeB, draft("18")).contentHash()).isNotEqualTo(first.contentHash());
    }

    @Test
    void evidenceAndSnapshotsCannotBeChangedOrDeleted() {
        Evidence stock = evidence.record(scopeA, draft("18"));
        ContextSnapshot snapshot = snapshots.create(scopeA, null, "inventory-context/1", T0, content(),
                List.of(stock.id()));

        assertThatThrownBy(() -> jdbc.sql("UPDATE evidence SET content = '{}' WHERE id = ?").param(stock.id())
                .update()).hasMessageContaining("evidence is immutable");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM evidence WHERE id = ?").param(stock.id()).update())
                .hasMessageContaining("evidence is immutable");
        assertThatThrownBy(() -> jdbc.sql("UPDATE context_snapshot SET as_of = now() WHERE id = ?")
                .param(snapshot.id()).update()).hasMessageContaining("context_snapshot is immutable");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM context_snapshot_evidence WHERE snapshot_id = ?")
                .param(snapshot.id()).update()).hasMessageContaining("context_snapshot_evidence is immutable");
    }

    @Test
    void snapshotIsReadableOnlyInItsStoreAndKeepsItsHash() {
        Evidence stock = evidence.record(scopeA, draft("18"));
        ContextSnapshot snapshot = snapshots.create(scopeA, null, "inventory-context/1", T0, content(),
                List.of(stock.id(), stock.id()));

        ContextSnapshot read = snapshots.find(scopeA, snapshot.id()).orElseThrow();
        assertThat(read.evidenceIds()).containsExactly(stock.id());
        assertThat(read.content().get("inventory").get("availableToSell").asInt()).isEqualTo(18);
        assertThat(read.contentHash()).isEqualTo(snapshot.contentHash());
        assertThat(snapshots.verify(scopeA, read)).isTrue();

        assertThat(snapshots.find(scopeB, snapshot.id())).isEmpty();
        assertThat(evidence.find(scopeB, stock.id())).isEmpty();
        assertThat(evidence.findAll(scopeB, List.of(stock.id()))).isEmpty();
        // Another store's evidence cannot be pulled into a snapshot by its id.
        assertThatThrownBy(() -> snapshots.create(scopeB, null, "inventory-context/1", T0, content(),
                List.of(stock.id()))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void coverageEvidenceKeepsLineageFromDaysOfCoverToItsInputs() {
        catalog.upsertProduct(scopeA, CommerceSource.WOOCOMMERCE, new ProductData("812", "Serum", null, "variable",
                "publish", T0));
        UUID variant = catalog.upsertVariant(scopeA, CommerceSource.WOOCOMMERCE, new VariantData("826", "812",
                "Serum 30 ml", "SERUM-30", "publish", StockTracking.VARIANT, Backorders.NO, T0));
        inventory.record(scopeA, variant, new InventoryReading(18, StockStatus.IN_STOCK,
                ReservationSemantics.ALREADY_ACCOUNTED_FOR, null, ObservationSource.WEBHOOK, T0, T0));

        InventoryEvidence.CoverageEvidence recorded = inventoryEvidence.record(scopeA,
                metrics.coverage(scopeA, variant, T0.plusSeconds(15)));

        assertThat(recorded.stock().content().get("availableToSell").asInt()).isEqualTo(18);
        assertThat(recorded.velocity().inputEvidenceIds()).containsExactly(recorded.unitsSold().id());
        assertThat(recorded.daysOfCover().inputEvidenceIds())
                .containsExactly(recorded.stock().id(), recorded.velocity().id());
        // No order coverage yet: the metric is unavailable with its reason, never zero.
        assertThat(recorded.daysOfCover().content().get("value").isNull()).isTrue();
        assertThat(recorded.daysOfCover().content().get("unavailableReason").asString())
                .isEqualTo("SALES_HISTORY_INCOMPLETE");
        assertThat(recorded.daysOfCover().definitionVersion()).isEqualTo("days_of_cover@v1");
        assertThat(recorded.all()).allMatch(item -> evidence.verify(scopeA, item));
    }

    private static EvidenceDraft draft(String quantity) {
        ObjectNode content = JsonNodeFactory.instance.objectNode();
        content.put("availableToSell", Integer.parseInt(quantity));
        content.put("stockStatus", "IN_STOCK");
        return new EvidenceDraft(EvidenceType.STOCK_READING, EvidenceDraft.variantRef(
                UUID.fromString("d2000000-0000-4000-8000-000000000001")), null, content, List.of(), T0, T0, T0);
    }

    private static JsonNode content() {
        return CanonicalJson.read("{\"inventory\":{\"availableToSell\":18,\"observedAt\":\"2026-11-14T08:00:15Z\"}}");
    }
}
