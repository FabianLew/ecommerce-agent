package com.ecommerceagent.metrics;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.ecommerceagent.commerce.InventoryObservation;
import com.ecommerceagent.commerce.InventoryReading;
import com.ecommerceagent.identity.TrustedStoreScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Evidence of the Inventory Guardian forecast (03 §4): the stock reading, units sold, velocity and days of
 * cover, with lineage from each metric to its inputs. Called only when a decision context is built.
 */
@Service
public class InventoryEvidence {

    private final EvidenceStore store;
    private final Clock clock;

    InventoryEvidence(EvidenceStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /** Evidence of one coverage calculation; {@code stock} is null when there was no stock observation. */
    public record CoverageEvidence(Evidence stock, Evidence unitsSold, Evidence velocity, Evidence daysOfCover) {

        public List<Evidence> all() {
            List<Evidence> all = new ArrayList<>();
            if (stock != null) {
                all.add(stock);
            }
            all.add(unitsSold);
            all.add(velocity);
            all.add(daysOfCover);
            return all;
        }
    }

    @Transactional
    public CoverageEvidence record(TrustedStoreScope scope, InventoryCoverage coverage) {
        String ref = EvidenceDraft.variantRef(coverage.variantId());
        Evidence stock = coverage.stock() == null ? null : store.record(scope, stockDraft(ref, coverage.stock()));
        Evidence units = store.record(scope, metricDraft(EvidenceType.UNITS_SOLD, ref, coverage, coverage.unitsSold(),
                List.of()));
        Evidence velocity = store.record(scope, metricDraft(EvidenceType.SALES_VELOCITY, ref, coverage,
                coverage.velocityPerDay(), List.of(units.id())));
        List<UUID> coverInputs = new ArrayList<>();
        if (stock != null) {
            coverInputs.add(stock.id());
        }
        coverInputs.add(velocity.id());
        Evidence cover = store.record(scope, metricDraft(EvidenceType.DAYS_OF_COVER, ref, coverage,
                coverage.daysOfCover(), coverInputs));
        return new CoverageEvidence(stock, units, velocity, cover);
    }

    private EvidenceDraft stockDraft(String ref, InventoryObservation observation) {
        InventoryReading reading = observation.reading();
        ObjectNode content = JsonNodeFactory.instance.objectNode();
        content.put("observationId", observation.id().toString());
        content.put("stockQuantity", reading.stockQuantity());
        content.put("stockStatus", reading.stockStatus().name());
        content.put("reservationSemantics", reading.semantics().name());
        content.put("reservedQuantity", reading.reservedQuantity());
        content.put("availableToSell", reading.availableToSell());
        content.put("observationSource", reading.observationSource().name());
        content.put("sourceUpdatedAt", reading.sourceUpdatedAt() == null ? null : reading.sourceUpdatedAt().toString());
        return new EvidenceDraft(EvidenceType.STOCK_READING, ref, null, content, List.of(), reading.observedAt(),
                observation.ingestedAt(), clock.instant());
    }

    private EvidenceDraft metricDraft(EvidenceType type, String ref, InventoryCoverage coverage, MetricValue metric,
            List<UUID> inputs) {
        ObjectNode content = JsonNodeFactory.instance.objectNode();
        content.put("metricKey", metric.definition().key());
        content.put("unit", metric.definition().unit());
        content.put("value", metric.value() == null ? null : metric.value().toPlainString());
        content.put("quality", metric.quality().name());
        content.put("unavailableReason", metric.unavailableReason() == null ? null : metric.unavailableReason().name());
        var limitations = content.putArray("limitations");
        metric.limitations().forEach(reason -> limitations.add(reason.name()));
        ObjectNode period = content.putObject("period");
        period.put("from", metric.period().from().toString());
        period.put("toExclusive", metric.period().toExclusive().toString());
        period.put("timezone", metric.period().timezone());
        if (type == EvidenceType.UNITS_SOLD) {
            var statuses = content.putArray("qualifyingOrderStatuses");
            MetricDefinition.QUALIFYING_ORDER_STATUSES_V1.forEach(statuses::add);
        }
        return new EvidenceDraft(type, ref, metric.definitionVersion(), content, inputs, coverage.asOf(),
                coverage.asOf(), clock.instant());
    }
}
