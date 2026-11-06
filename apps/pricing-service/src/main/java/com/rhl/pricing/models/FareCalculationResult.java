package com.rhl.pricing.models;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Encapsulates the calculation output, policy reference, and metadata.
 */
public record FareCalculationResult(
        FareBreakdown breakdown,
        PricingPolicy policy,
        boolean isSurgeApplied,
        Instant calculatedAt
) {
    public static FareCalculationResult of(FareBreakdown breakdown, PricingPolicy policy, Instant calculatedAt) {
        boolean surgeApplied = breakdown.surgeMultiplier().compareTo(BigDecimal.ONE) > 0;
        return new FareCalculationResult(breakdown, policy, surgeApplied, calculatedAt);
    }
}
