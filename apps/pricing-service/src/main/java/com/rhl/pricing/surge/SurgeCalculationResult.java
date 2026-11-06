package com.rhl.pricing.surge;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Result of deterministic surge multiplier calculation (FR-PRI-009, FR-PRI-010, FR-PRI-011).
 */
public record SurgeCalculationResult(
        BigDecimal multiplier,
        String policyVersion,
        long demandCount,
        long supplyCount,
        BigDecimal demandSupplyRatio,
        boolean isSurgeApplied,
        Instant calculatedAt,
        String calculationNote
) {

    public SurgeCalculationResult {
        Objects.requireNonNull(multiplier, "multiplier must not be null");
        Objects.requireNonNull(policyVersion, "policyVersion must not be null");
        Objects.requireNonNull(calculatedAt, "calculatedAt must not be null");
    }

    public static SurgeCalculationResult noSurge(String policyVersion, Instant now) {
        return new SurgeCalculationResult(
                BigDecimal.valueOf(1.00).setScale(2, java.math.RoundingMode.HALF_UP),
                policyVersion,
                0,
                0,
                BigDecimal.ZERO.setScale(4, java.math.RoundingMode.HALF_UP),
                false,
                now,
                "Surge not applied (default 1.00x)"
        );
    }
}
