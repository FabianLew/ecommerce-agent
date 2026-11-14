package com.ecommerceagent.decisions;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.metrics.InventoryCoverage;
import com.ecommerceagent.metrics.InventoryMetrics;
import com.ecommerceagent.metrics.MetricValue;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inventory risk detector {@code inventory-risk/1} (B-19, ADR-0011). Every run records an observation; a
 * series of current risk observations long enough opens an episode. A run on a variant with an active episode
 * attaches the observation to it.
 */
@Service
public class InventoryRiskDetector {

    private final InventoryMetrics metrics;
    private final Opportunities opportunities;
    private final RiskDetectorSettings settings;
    private final JdbcClient jdbc;
    private final Clock clock;

    InventoryRiskDetector(InventoryMetrics metrics, Opportunities opportunities, RiskDetectorSettings settings,
            JdbcClient jdbc, Clock clock) {
        this.metrics = metrics;
        this.opportunities = opportunities;
        this.settings = settings;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public RiskDetectorSettings settings() {
        return settings;
    }

    /**
     * Assesses the variant now. {@code confirmedAt} is the time a fresh source read confirmed the stock (the
     * stored reading may be older when the state did not change); null when there was no fresh read.
     */
    @Transactional
    public RiskAssessment assess(TrustedStoreScope scope, UUID variantId, Instant confirmedAt) {
        Instant now = clock.instant();
        InventoryCoverage coverage = metrics.coverage(scope, variantId, now);
        Instant stockConfirmedAt = coverage.stock() == null ? null : coverage.stock().reading().observedAt();
        if (confirmedAt != null && coverage.stock() != null
                && (stockConfirmedAt == null || confirmedAt.isAfter(stockConfirmedAt))) {
            stockConfirmedAt = confirmedAt;
        }
        Integer available = coverage.stock() == null ? null : coverage.stock().reading().availableToSell();
        MetricValue cover = coverage.daysOfCover();

        RiskOutcome outcome;
        String reason = null;
        if (stockConfirmedAt == null || stockConfirmedAt.isBefore(now.minus(settings.maxStockAge()))) {
            outcome = RiskOutcome.UNKNOWN;
            reason = "STOCK_NOT_CURRENT";
        } else if (cover.value() == null) {
            outcome = RiskOutcome.UNKNOWN;
            reason = cover.unavailableReason().name();
        } else {
            outcome = cover.value().compareTo(settings.thresholdDays()) < 0 ? RiskOutcome.AT_RISK
                    : RiskOutcome.NOT_AT_RISK;
        }

        Optional<Opportunity> active = opportunities.active(scope, OpportunityCategory.INVENTORY_RISK,
                Opportunity.PRODUCT_VARIANT, variantId);
        boolean opened = false;
        Instant nextCheckAt = null;
        if (active.isEmpty() && outcome == RiskOutcome.AT_RISK) {
            List<Instant> series = riskSeries(scope, variantId);
            int observations = series.size() + 1;
            Instant first = series.isEmpty() ? now : series.getLast();
            Instant dueAt = first.plus(settings.minGap());
            if (observations >= settings.minObservations() && !now.isBefore(dueAt)) {
                Opportunities.Opened result = opportunities.open(scope, OpportunityCategory.INVENTORY_RISK,
                        Opportunity.PRODUCT_VARIANT, variantId);
                active = Optional.of(result.opportunity());
                opened = result.created();
            } else {
                nextCheckAt = now.isBefore(dueAt) ? dueAt : now.plus(settings.minGap());
            }
        }

        RiskObservation observation = new RiskObservation(UUID.randomUUID(), variantId, now, stockConfirmedAt,
                available, cover.value(), outcome, reason, settings.thresholdDays(), RiskDetectorSettings.VERSION,
                active.map(Opportunity::id).orElse(null));
        record(scope, observation);
        return new RiskAssessment(observation, active.orElse(null), opened, nextCheckAt);
    }

    /** Times of the current series of risk observations, newest first; any other outcome ends the series. */
    private List<Instant> riskSeries(TrustedStoreScope scope, UUID variantId) {
        return jdbc.sql("""
                        SELECT observed_at FROM inventory_risk_observation
                        WHERE variant_id = :variantId AND organization_id = :organizationId AND store_id = :storeId
                          AND outcome = 'AT_RISK' AND opportunity_id IS NULL
                          AND observed_at > coalesce((
                              SELECT max(observed_at) FROM inventory_risk_observation
                              WHERE variant_id = :variantId AND organization_id = :organizationId
                                AND store_id = :storeId
                                AND (outcome <> 'AT_RISK' OR opportunity_id IS NOT NULL)), '-infinity')
                        ORDER BY observed_at DESC
                        """)
                .param("variantId", variantId)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .query(Timestamp.class)
                .list()
                .stream()
                .map(Timestamp::toInstant)
                .toList();
    }

    private void record(TrustedStoreScope scope, RiskObservation observation) {
        jdbc.sql("""
                        INSERT INTO inventory_risk_observation (id, organization_id, store_id, variant_id, observed_at,
                                                                stock_confirmed_at, available_to_sell, days_of_cover,
                                                                outcome, reason, threshold_days, detector_version,
                                                                opportunity_id)
                        VALUES (:id, :organizationId, :storeId, :variantId, :observedAt, :confirmedAt, :available,
                                :cover, :outcome, :reason, :threshold, :version, :opportunityId)
                        """)
                .param("id", observation.id())
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("variantId", observation.variantId())
                .param("observedAt", Timestamp.from(observation.observedAt()))
                .param("confirmedAt", observation.stockConfirmedAt() == null ? null
                        : Timestamp.from(observation.stockConfirmedAt()))
                .param("available", observation.availableToSell())
                .param("cover", observation.daysOfCover())
                .param("outcome", observation.outcome().name())
                .param("reason", observation.reason())
                .param("threshold", observation.thresholdDays())
                .param("version", observation.detectorVersion())
                .param("opportunityId", observation.opportunityId())
                .update();
    }

    /** Observations of a variant, newest first (UI, tests). */
    public List<RiskObservation> observations(TrustedStoreScope scope, UUID variantId, int limit) {
        return jdbc.sql("""
                        SELECT id, variant_id, observed_at, stock_confirmed_at, available_to_sell, days_of_cover,
                               outcome, reason, threshold_days, detector_version, opportunity_id
                        FROM inventory_risk_observation
                        WHERE variant_id = :variantId AND organization_id = :organizationId AND store_id = :storeId
                        ORDER BY observed_at DESC
                        LIMIT :limit
                        """)
                .param("variantId", variantId)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("limit", limit)
                .query((rs, n) -> {
                    Timestamp confirmed = rs.getTimestamp("stock_confirmed_at");
                    Object available = rs.getObject("available_to_sell");
                    BigDecimal cover = rs.getBigDecimal("days_of_cover");
                    return new RiskObservation(rs.getObject("id", UUID.class), rs.getObject("variant_id", UUID.class),
                            rs.getTimestamp("observed_at").toInstant(), confirmed == null ? null : confirmed.toInstant(),
                            available == null ? null : ((Number) available).intValue(), cover,
                            RiskOutcome.valueOf(rs.getString("outcome")), rs.getString("reason"),
                            rs.getBigDecimal("threshold_days"), rs.getString("detector_version"),
                            rs.getObject("opportunity_id", UUID.class));
                })
                .list();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RiskDetectorSettings.class)
    static class Settings {
    }
}
