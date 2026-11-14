package com.ecommerceagent.spike.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Service;

/**
 * Minimal module API used to check Spring Modulith boundaries in the spike.
 */
@Service
public class InventoryForecastService {

    public BigDecimal daysOfCover(int availableToSell, int unitsSoldInWindow, int windowDays) {
        if (unitsSoldInWindow == 0) {
            return null;
        }
        BigDecimal velocity = BigDecimal.valueOf(unitsSoldInWindow)
                .divide(BigDecimal.valueOf(windowDays), 8, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(availableToSell).divide(velocity, 4, RoundingMode.HALF_UP);
    }
}
