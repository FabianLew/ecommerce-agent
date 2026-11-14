package com.ecommerceagent.intelligence.inventory;

import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import com.ecommerceagent.decisions.DecisionDisposition;
import com.ecommerceagent.decisions.EvidenceRef;
import com.ecommerceagent.decisions.PauseAd;
import com.ecommerceagent.decisions.Proposer;
import com.ecommerceagent.decisions.ProposerKind;
import com.ecommerceagent.decisions.Recommendation;
import com.ecommerceagent.decisions.RiskDetectorSettings;
import org.springframework.stereotype.Service;

/**
 * Deterministic Inventory Guardian proposer (ADR-0012 point 4): the reference for evaluating the agent and its
 * fallback. It reads only the context snapshot and returns a recommendation under the same contract.
 */
@Service
public class BaselineProposer {

    public static final Proposer PROPOSER = new Proposer(ProposerKind.BASELINE, "inventory-baseline", "1.0.0");

    private final Duration maxStockAge;

    BaselineProposer(RiskDetectorSettings settings) {
        this.maxStockAge = settings.maxStockAge();
    }

    public Recommendation propose(InventoryContext context) {
        InventoryContext.Stock stock = context.stock();
        Instant confirmed = latest(stock.observedAt(), stock.confirmedAt());
        boolean stockCurrent = stock.evidence() != null && confirmed != null
                && !confirmed.isBefore(context.asOf().minus(maxStockAge));
        List<EvidenceRef> demand = Stream.of(stock.evidence(), context.daysOfCover().evidence(),
                context.velocityPerDay().evidence(), context.unitsSold().evidence())
                .filter(Objects::nonNull).toList();

        if (!stockCurrent || context.daysOfCover().value() == null) {
            List<String> missing = new ArrayList<>();
            List<String> reasons = new ArrayList<>();
            if (!stockCurrent) {
                missing.add("CURRENT_STOCK");
                reasons.add("STOCK_NOT_CURRENT");
            }
            if (context.daysOfCover().value() == null) {
                missing.add("SALES_HISTORY_COMPLETE");
                reasons.add("FORECAST_UNAVAILABLE");
            }
            return recommendation(DecisionDisposition.INSUFFICIENT_DATA, null, demand, missing, reasons,
                    "No current stock level or sales forecast, so the stock-out risk cannot be assessed.",
                    List.of());
        }
        String cover = context.daysOfCover().value().setScale(1, RoundingMode.HALF_UP).toPlainString();
        if (context.daysOfCover().value().compareTo(context.thresholdDays()) >= 0) {
            return recommendation(DecisionDisposition.NO_ACTION, null, demand, List.of(),
                    List.of("COVER_ABOVE_THRESHOLD"),
                    "Stock covers " + cover + " days, which is not below the threshold of " + context.thresholdDays()
                            + " days.", List.of());
        }

        List<EvidenceRef> evidence = new ArrayList<>(demand);
        evidence.add(context.replenishmentEvidence());
        List<String> uncertainties = context.replenishmentKnown() ? List.of()
                : List.of("The replenishment date for this variant is unknown.");
        if (context.ads().isEmpty()) {
            return recommendation(DecisionDisposition.NO_ACTION, null, evidence, List.of(),
                    List.of("COVER_BELOW_THRESHOLD", "NO_MAPPED_ADS", "REPLENISHMENT_UNKNOWN"),
                    "Stock covers " + cover + " days, but no ad promotes this variant.",
                    uncertainties);
        }
        List<InventoryContext.AdFact> multi = context.ads().stream()
                .filter(ad -> !ad.singleVariant()).toList();
        List<InventoryContext.AdFact> candidates = context.ads().stream()
                .filter(InventoryContext.AdFact::singleVariant)
                .filter(ad -> "VERIFIED".equals(ad.verification()) && ad.currentForCreative())
                .sorted(Comparator.comparing(InventoryContext.AdFact::externalId))
                .toList();
        List<InventoryContext.AdFact> activeCandidates = candidates.stream()
                .filter(InventoryContext.AdFact::active).toList();
        multi.forEach(ad -> evidence.add(ad.mappingEvidence()));

        if (!activeCandidates.isEmpty()) {
            InventoryContext.AdFact target = activeCandidates.getFirst();
            evidence.add(target.stateEvidence());
            evidence.add(target.mappingEvidence());
            List<String> reasons = new ArrayList<>(List.of("COVER_BELOW_THRESHOLD", "DEDICATED_AD_ACTIVE"));
            String summary = "Stock covers " + cover + " days against a threshold of " + context.thresholdDays()
                    + " days. We propose pausing the dedicated ad " + target.externalId() + ".";
            if (!multi.isEmpty()) {
                reasons.add("MULTI_PRODUCT_AD_EXCLUDED");
                summary += " The catalog ads (" + String.join(", ", multi.stream()
                        .map(InventoryContext.AdFact::externalId).sorted().toList())
                        + ") also promote other products, so we do not pause them.";
            }
            if (!context.replenishmentKnown()) {
                reasons.add("REPLENISHMENT_UNKNOWN");
            }
            return recommendation(DecisionDisposition.RECOMMEND_ACTION, new PauseAd(target.adId()), evidence,
                    List.of(), reasons, summary, uncertainties);
        }
        if (!candidates.isEmpty()) {
            // The dedicated ads are already not running: nothing to pause.
            candidates.forEach(ad -> evidence.add(ad.stateEvidence()));
            return recommendation(DecisionDisposition.NO_ACTION, null, evidence, List.of(),
                    List.of("COVER_BELOW_THRESHOLD", "AD_NOT_ACTIVE"),
                    "Stock covers " + cover + " days, and the dedicated ads are already not running.", uncertainties);
        }
        List<String> reasons = new ArrayList<>(List.of("COVER_BELOW_THRESHOLD"));
        boolean unverified = context.ads().stream().anyMatch(ad -> ad.singleVariant()
                && !("VERIFIED".equals(ad.verification()) && ad.currentForCreative()));
        if (!multi.isEmpty()) {
            reasons.add("ONLY_MULTI_PRODUCT_ADS");
        }
        if (unverified) {
            reasons.add("MAPPING_UNVERIFIED");
            context.ads().stream().filter(InventoryContext.AdFact::singleVariant)
                    .forEach(ad -> evidence.add(ad.mappingEvidence()));
        }
        return recommendation(DecisionDisposition.ESCALATE, null, evidence, List.of(), reasons,
                "Stock covers " + cover + " days, but the variant is promoted only by catalog ads or ads without "
                        + "a verified mapping. The decision is yours.", uncertainties);
    }

    private static Recommendation recommendation(DecisionDisposition disposition, PauseAd action,
            List<EvidenceRef> evidence, List<String> missing, List<String> reasons, String summary,
            List<String> uncertainties) {
        return new Recommendation(disposition, action, null, evidence.stream().distinct().toList(), missing, reasons,
                summary, uncertainties);
    }

    private static Instant latest(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        return b == null || a.isAfter(b) ? a : b;
    }
}
