package com.ecommerceagent.automation.inventory;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.TreeMap;

import com.ecommerceagent.automation.notifications.NotificationDeliveryException;
import com.ecommerceagent.automation.notifications.NotificationEvent;
import com.ecommerceagent.automation.notifications.NotificationKind;
import com.ecommerceagent.automation.notifications.OperatorNotifier;
import com.ecommerceagent.commerce.CatalogRepository;
import com.ecommerceagent.decisions.InventoryRiskDetector;
import com.ecommerceagent.decisions.RiskDetectorSettings;
import com.ecommerceagent.decisions.RiskObservation;
import com.ecommerceagent.decisions.RiskOutcome;
import com.ecommerceagent.identity.StoreAccess;
import com.ecommerceagent.identity.TrustedStoreScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * L0 (ADR-0026 point 1): a current observation with confirmed cover below {@code warning-days} gives one
 * {@code STOCK_WARNING} per variant and store-local day. It proposes nothing and opens no episode.
 */
@Component
class StockWarnings {

    private static final Logger log = LoggerFactory.getLogger(StockWarnings.class);

    private final OperatorNotifier notifier;
    private final InventoryRiskDetector detector;
    private final CatalogRepository catalog;
    private final StoreAccess storeAccess;

    StockWarnings(OperatorNotifier notifier, InventoryRiskDetector detector, CatalogRepository catalog,
            StoreAccess storeAccess) {
        this.notifier = notifier;
        this.detector = detector;
        this.catalog = catalog;
        this.storeAccess = storeAccess;
    }

    /** @return whether the observation is below the warning threshold (the day's warning may already exist) */
    boolean warnIfLow(TrustedStoreScope scope, RiskObservation observation) {
        RiskDetectorSettings settings = detector.settings();
        if (observation.outcome() == RiskOutcome.UNKNOWN || observation.daysOfCover() == null
                || observation.daysOfCover().compareTo(settings.warningDays()) >= 0) {
            return false;
        }
        LocalDate day = observation.observedAt().atZone(zone(scope)).toLocalDate();
        Map<String, String> parameters = new TreeMap<>();
        parameters.put("daysOfCover", observation.daysOfCover().stripTrailingZeros().toPlainString());
        if (observation.availableToSell() != null) {
            parameters.put("availableToSell", observation.availableToSell().toString());
        }
        parameters.put("warningDays", settings.warningDays().stripTrailingZeros().toPlainString());
        parameters.put("thresholdDays", settings.thresholdDays().stripTrailingZeros().toPlainString());
        parameters.put("detectorVersion", observation.detectorVersion());
        parameters.put("variantId", observation.variantId().toString());
        catalog.variant(scope, observation.variantId()).ifPresent(variant -> {
            if (variant.sku() != null) {
                parameters.put("sku", variant.sku());
            }
            parameters.put("variantName", variant.name());
        });
        try {
            notifier.notify(scope, new NotificationEvent("stock-warning/" + observation.variantId() + "/" + day,
                    NotificationKind.STOCK_WARNING, null, parameters, null, null, null));
        } catch (NotificationDeliveryException e) {
            // Stored with its deliveries; the sweep sends what could not be sent now. The check itself goes on.
            log.warn("Stock warning stored, {} e-mail(s) to be retried", e.getMessage());
        }
        return true;
    }

    private ZoneId zone(TrustedStoreScope scope) {
        return storeAccess.zone(scope);
    }
}
