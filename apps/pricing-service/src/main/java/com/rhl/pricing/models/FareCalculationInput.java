package com.rhl.pricing.models;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Input parameters for fare calculation.
 */
public record FareCalculationInput(
        BigDecimal distanceKm,
        BigDecimal durationMinutes,
        BigDecimal surgeMultiplier,
        BigDecimal additionalFees,
        BigDecimal discount
) {
    public FareCalculationInput {
        Objects.requireNonNull(distanceKm, "distanceKm must not be null");
        Objects.requireNonNull(durationMinutes, "durationMinutes must not be null");

        if (surgeMultiplier == null) {
            surgeMultiplier = BigDecimal.ONE;
        }
        if (additionalFees == null) {
            additionalFees = BigDecimal.ZERO;
        }
        if (discount == null) {
            discount = BigDecimal.ZERO;
        }

        if (distanceKm.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("distanceKm must not be negative");
        }
        if (durationMinutes.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("durationMinutes must not be negative");
        }
        if (surgeMultiplier.compareTo(BigDecimal.ONE) < 0) {
            throw new IllegalArgumentException("surgeMultiplier must be at least 1.0");
        }
        if (additionalFees.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("additionalFees must not be negative");
        }
        if (discount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("discount must not be negative");
        }
    }

    public static FareCalculationInput of(BigDecimal distanceKm, BigDecimal durationMinutes) {
        return new FareCalculationInput(distanceKm, durationMinutes, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    public static FareCalculationInput of(BigDecimal distanceKm, BigDecimal durationMinutes, BigDecimal surgeMultiplier) {
        return new FareCalculationInput(distanceKm, durationMinutes, surgeMultiplier, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
