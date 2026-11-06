package com.rhl.pricing.routing;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Route estimate result holding distance, duration and simulation metadata (FR-PRI-005).
 */
public record RouteEstimate(
        long distanceMeters,
        long durationSeconds,
        String providerName,
        boolean isSimulated
) {
    public BigDecimal distanceKm() {
        return BigDecimal.valueOf(distanceMeters)
                .divide(BigDecimal.valueOf(1000), 4, RoundingMode.HALF_UP);
    }

    public BigDecimal durationMinutes() {
        return BigDecimal.valueOf(durationSeconds)
                .divide(BigDecimal.valueOf(60), 4, RoundingMode.HALF_UP);
    }
}
