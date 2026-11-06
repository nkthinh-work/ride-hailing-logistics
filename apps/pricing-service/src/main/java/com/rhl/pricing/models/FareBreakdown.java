package com.rhl.pricing.models;

import java.math.BigDecimal;

/**
 * Complete, transparent breakdown of calculated fare components according to FR-PRI-002, 003, 013, 016.
 */
public record FareBreakdown(
        BigDecimal baseFare,
        BigDecimal includedDistanceKm,
        BigDecimal chargeableDistanceKm,
        BigDecimal distanceFare,
        BigDecimal durationMinutes,
        BigDecimal timeFare,
        BigDecimal standardFare,
        BigDecimal minimumFare,
        BigDecimal preSurgeFare,
        BigDecimal surgeMultiplier,
        BigDecimal surgedFare,
        BigDecimal additionalFees,
        BigDecimal tax,
        BigDecimal discount,
        BigDecimal totalFare,
        String currency,
        String ruleVersion
) {
}
