package com.rhl.pricing.surge;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;

/**
 * Pure deterministic Dynamic Surge Calculator (FR-PRI-009, FR-PRI-011).
 *
 * <p>Principles:
 * <ul>
 *   <li>Pure Function: depends solely on (input, policy) without external side effects or I/O.</li>
 *   <li>Deterministic: identical input + identical policy version yields identical multiplier.</li>
 *   <li>Zero Division Protection: cleanly handles supply = 0 without throwing ArithmeticException.</li>
 *   <li>Strict Bounds: guarantees multiplier >= minMultiplier (>= 1.00) and <= maxMultiplier (surge cap).</li>
 * </ul>
 */
@Component
public class SurgeCalculator {

    public static final int RATIO_SCALE = 4;
    public static final RoundingMode RATIO_ROUNDING = RoundingMode.HALF_UP;

    /**
     * Calculates the surge multiplier deterministically.
     *
     * @param input  surge calculation input containing demand and supply counts
     * @param policy surge policy configuration with bounds and sensitivity
     * @param calculatedAt timestamp of calculation (supplied explicitly for determinism)
     * @return immutable calculation result
     */
    public SurgeCalculationResult calculate(SurgeCalculationInput input, SurgePolicy policy, Instant calculatedAt) {
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        Objects.requireNonNull(calculatedAt, "calculatedAt must not be null");

        long demand = input.demandCount();
        long supply = input.supplyCount();
        BigDecimal minMultiplier = policy.minMultiplier().setScale(policy.roundingScale(), policy.roundingMode());
        BigDecimal maxMultiplier = policy.maxMultiplier().setScale(policy.roundingScale(), policy.roundingMode());

        // Case 1: Demand is zero -> no upward pricing pressure
        if (demand == 0) {
            return new SurgeCalculationResult(
                    minMultiplier,
                    policy.policyVersion(),
                    demand,
                    supply,
                    BigDecimal.ZERO.setScale(RATIO_SCALE, RATIO_ROUNDING),
                    false,
                    calculatedAt,
                    "Zero demand: minimum multiplier applied"
            );
        }

        // Case 2: Supply is zero, Demand > 0 -> extreme supply deficit (prevent division by zero)
        if (supply == 0) {
            boolean isSurgeApplied = maxMultiplier.compareTo(BigDecimal.ONE) > 0;
            return new SurgeCalculationResult(
                    maxMultiplier,
                    policy.policyVersion(),
                    demand,
                    0,
                    BigDecimal.valueOf(999.9999).setScale(RATIO_SCALE, RATIO_ROUNDING),
                    isSurgeApplied,
                    calculatedAt,
                    "Zero supply with active demand: maximum surge cap applied"
            );
        }

        // Case 3: Supply > 0 -> compute ratio demand / supply
        BigDecimal demandBd = BigDecimal.valueOf(demand);
        BigDecimal supplyBd = BigDecimal.valueOf(supply);
        BigDecimal ratio = demandBd.divide(supplyBd, RATIO_SCALE, RATIO_ROUNDING);

        // Case 3a: Ratio <= threshold -> balance or over-supply, no surge
        if (ratio.compareTo(policy.thresholdRatio()) <= 0) {
            return new SurgeCalculationResult(
                    minMultiplier,
                    policy.policyVersion(),
                    demand,
                    supply,
                    ratio,
                    false,
                    calculatedAt,
                    "Demand/supply ratio (" + ratio + ") <= threshold (" + policy.thresholdRatio() + "): no surge applied"
            );
        }

        // Case 3b: Ratio > threshold -> calculate linear surge above threshold
        BigDecimal excessRatio = ratio.subtract(policy.thresholdRatio());
        BigDecimal rawSurgeIncrease = excessRatio.multiply(policy.sensitivity());
        BigDecimal calculatedMultiplier = minMultiplier.add(rawSurgeIncrease);

        // Enforce upper bound (surge cap) and lower bound
        BigDecimal clampedMultiplier = calculatedMultiplier.max(minMultiplier).min(maxMultiplier);
        BigDecimal finalMultiplier = clampedMultiplier.setScale(policy.roundingScale(), policy.roundingMode());

        boolean isSurgeApplied = finalMultiplier.compareTo(BigDecimal.ONE) > 0;
        String note = (finalMultiplier.compareTo(maxMultiplier) == 0 && calculatedMultiplier.compareTo(maxMultiplier) > 0)
                ? "Surge capped at maximum allowed multiplier (" + maxMultiplier + ")"
                : "Surge applied based on demand/supply ratio (" + ratio + ")";

        return new SurgeCalculationResult(
                finalMultiplier,
                policy.policyVersion(),
                demand,
                supply,
                ratio,
                isSurgeApplied,
                calculatedAt,
                note
        );
    }
}
