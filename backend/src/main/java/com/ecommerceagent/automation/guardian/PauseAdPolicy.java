package com.ecommerceagent.automation.guardian;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.ecommerceagent.decisions.PauseAd;
import com.ecommerceagent.intelligence.inventory.InventoryContext;
import com.ecommerceagent.integration.IntegrationHealth;

/**
 * Policy for {@link PauseAd} under {@code inventory-protection} (02 §9, ADR-0012). Checks run in a fixed order and
 * the first failing one decides; the rest are recorded as not evaluated. It is a pure function of the proposal's
 * context, the store's integration health and the time, so every outcome can be replayed from the record.
 */
public final class PauseAdPolicy {

    private PauseAdPolicy() {
    }

    public static Evaluation evaluate(InventoryProtectionPolicy policy, PauseAd action, InventoryContext context,
            IntegrationHealth.Status health, Instant now) {
        List<Named> checks = List.of(
                new Named("ISOLATION", () -> isolation(action, context)),
                new Named("INTEGRATION_HEALTH", () -> integration(health)),
                new Named("FRESHNESS", () -> freshness(policy, action, context, now)),
                new Named("RISK_AND_QUALITY", () -> risk(context)),
                new Named("MAPPING_AND_CREATIVE", () -> mapping(policy, action, context, now)),
                new Named("AD_STATE", () -> adState(action, context)),
                new Named("AUTONOMY", () -> autonomy(policy)));
        List<PolicyCheck> results = new ArrayList<>();
        PolicyOutcome outcome = null;
        for (Named check : checks) {
            if (outcome != null) {
                results.add(new PolicyCheck(check.name(), PolicyCheck.Result.NOT_EVALUATED, null, null, Map.of()));
                continue;
            }
            PolicyCheck result = check.check().run();
            results.add(result);
            if (result.result() == PolicyCheck.Result.FAILED || result.name().equals("AUTONOMY")) {
                outcome = result.outcome();
            }
        }
        return new Evaluation(outcome, results);
    }

    public record Evaluation(PolicyOutcome outcome, List<PolicyCheck> checks) {
    }

    @FunctionalInterface
    private interface Check {
        PolicyCheck run();
    }

    private record Named(String name, Check check) {
    }

    private static PolicyCheck isolation(PauseAd action, InventoryContext context) {
        Optional<InventoryContext.AdFact> ad = target(action, context);
        return ad.isPresent()
                ? passed("ISOLATION", Map.of("adId", action.adId().toString(), "variantId",
                        context.variantId().toString()))
                : failed("ISOLATION", PolicyOutcome.DENY, "TARGET_NOT_IN_CONTEXT",
                        Map.of("adId", action.adId().toString()));
    }

    private static PolicyCheck integration(IntegrationHealth.Status health) {
        Map<String, String> facts = Map.of("integrationHealth", String.valueOf(health));
        if (health == IntegrationHealth.Status.HEALTHY || health == IntegrationHealth.Status.DEGRADED) {
            return passed("INTEGRATION_HEALTH", facts);
        }
        return failed("INTEGRATION_HEALTH", PolicyOutcome.DEFER,
                health == null ? "INTEGRATION_MISSING" : "INTEGRATION_" + health.name(), facts);
    }

    private static PolicyCheck freshness(InventoryProtectionPolicy policy, PauseAd action, InventoryContext context,
            Instant now) {
        InventoryContext.Stock stock = context.stock();
        Instant stockAt = latest(stock.observedAt(), stock.confirmedAt());
        Instant adAt = target(action, context).map(InventoryContext.AdFact::observedAt).orElse(null);
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("now", now.toString());
        facts.put("stockConfirmedAt", String.valueOf(stockAt));
        facts.put("adObservedAt", String.valueOf(adAt));
        facts.put("maxStockAge", policy.maxStockAge().toString());
        facts.put("maxAdStateAge", policy.maxAdStateAge().toString());
        if (stockAt == null || age(stockAt, now).compareTo(policy.maxStockAge()) > 0) {
            return failed("FRESHNESS", PolicyOutcome.DEFER, "STOCK_NOT_CURRENT", facts);
        }
        if (adAt == null || age(adAt, now).compareTo(policy.maxAdStateAge()) > 0) {
            return failed("FRESHNESS", PolicyOutcome.DEFER, "AD_STATE_NOT_CURRENT", facts);
        }
        return passed("FRESHNESS", facts);
    }

    private static PolicyCheck risk(InventoryContext context) {
        BigDecimal cover = context.daysOfCover().value();
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("daysOfCover", cover == null ? "null" : cover.toPlainString());
        facts.put("thresholdDays", context.thresholdDays().toPlainString());
        facts.put("demandQuality", String.valueOf(context.unitsSold().quality()));
        facts.put("lastRiskOutcome", String.valueOf(context.lastRiskOutcome()));
        if (!"AT_RISK".equals(context.lastRiskOutcome())) {
            return failed("RISK_AND_QUALITY", PolicyOutcome.DEFER, "RISK_NOT_CONFIRMED", facts);
        }
        if (!"CONFIRMED".equals(context.unitsSold().quality()) || cover == null) {
            return failed("RISK_AND_QUALITY", PolicyOutcome.DEFER, "DEMAND_WINDOW_INCOMPLETE", facts);
        }
        if (cover.compareTo(context.thresholdDays()) >= 0) {
            return failed("RISK_AND_QUALITY", PolicyOutcome.DENY, "COVER_ABOVE_THRESHOLD", facts);
        }
        return passed("RISK_AND_QUALITY", facts);
    }

    private static PolicyCheck mapping(InventoryProtectionPolicy policy, PauseAd action, InventoryContext context,
            Instant now) {
        InventoryContext.AdFact ad = target(action, context).orElseThrow();
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("mappingScope", ad.mappingScope());
        facts.put("mappingVersion", Integer.toString(ad.mappingVersion()));
        facts.put("variantCount", Integer.toString(ad.variantCount()));
        facts.put("verification", ad.verification());
        facts.put("verifiedAt", String.valueOf(ad.verifiedAt()));
        facts.put("currentForCreative", Boolean.toString(ad.currentForCreative()));
        facts.put("maxVerificationAge", policy.maxVerificationAge().toString());
        if (!ad.singleVariant()) {
            return failed("MAPPING_AND_CREATIVE", PolicyOutcome.DENY, "MULTI_PRODUCT_AD", facts);
        }
        if (!"VERIFIED".equals(ad.verification()) || ad.verifiedAt() == null) {
            return failed("MAPPING_AND_CREATIVE", PolicyOutcome.DENY, "MAPPING_UNVERIFIED", facts);
        }
        if (age(ad.verifiedAt(), now).compareTo(policy.maxVerificationAge()) > 0) {
            return failed("MAPPING_AND_CREATIVE", PolicyOutcome.DENY, "MAPPING_VERIFICATION_EXPIRED", facts);
        }
        if (!ad.currentForCreative()) {
            return failed("MAPPING_AND_CREATIVE", PolicyOutcome.DENY, "CREATIVE_CHANGED", facts);
        }
        return passed("MAPPING_AND_CREATIVE", facts);
    }

    private static PolicyCheck adState(PauseAd action, InventoryContext context) {
        InventoryContext.AdFact ad = target(action, context).orElseThrow();
        Map<String, String> facts = Map.of("ownStatus", String.valueOf(ad.ownStatus()), "effectiveStatus",
                String.valueOf(ad.effectiveStatus()));
        if (ad.active()) {
            return passed("AD_STATE", facts);
        }
        String code = "PAUSED".equals(ad.ownStatus()) ? "ALREADY_PAUSED" : "CONFLICT";
        return failed("AD_STATE", PolicyOutcome.DENY, code, facts);
    }

    /** ADR-0026 point 4: L2 is not available in this version, so every action needs a person's approval. */
    private static PolicyCheck autonomy(InventoryProtectionPolicy policy) {
        Map<String, String> facts = Map.of("autonomy", policy.autonomy().name(), "l2Enabled",
                Boolean.toString(policy.l2Enabled()));
        String code = policy.autonomy() == InventoryProtectionPolicy.Autonomy.L2 || policy.l2Enabled()
                ? "L2_NOT_AVAILABLE" : "AUTONOMY_L1";
        return new PolicyCheck("AUTONOMY", PolicyCheck.Result.PASSED, PolicyOutcome.REQUIRE_APPROVAL, code, facts);
    }

    private static Optional<InventoryContext.AdFact> target(PauseAd action, InventoryContext context) {
        return context.ads().stream().filter(ad -> ad.adId().equals(action.adId())).findFirst();
    }

    private static PolicyCheck passed(String name, Map<String, String> facts) {
        return new PolicyCheck(name, PolicyCheck.Result.PASSED, null, null, facts);
    }

    private static PolicyCheck failed(String name, PolicyOutcome outcome, String code, Map<String, String> facts) {
        return new PolicyCheck(name, PolicyCheck.Result.FAILED, outcome, code, facts);
    }

    private static Duration age(Instant at, Instant now) {
        return Duration.between(at, now);
    }

    private static Instant latest(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        return b == null || a.isAfter(b) ? a : b;
    }
}
