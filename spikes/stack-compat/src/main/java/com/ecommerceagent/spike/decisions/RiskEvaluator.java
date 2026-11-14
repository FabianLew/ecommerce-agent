package com.ecommerceagent.spike.decisions;

import java.math.BigDecimal;

import com.ecommerceagent.spike.inventory.InventoryForecastService;
import org.springframework.stereotype.Component;

/**
 * Depends on the public API of the inventory module only (allowed direction).
 */
@Component
public class RiskEvaluator {

    private final InventoryForecastService forecastService;

    RiskEvaluator(InventoryForecastService forecastService) {
        this.forecastService = forecastService;
    }

    public boolean isLowCoverage(int availableToSell, int unitsSold, int windowDays, BigDecimal thresholdDays) {
        BigDecimal cover = forecastService.daysOfCover(availableToSell, unitsSold, windowDays);
        return cover != null && cover.compareTo(thresholdDays) < 0;
    }
}
