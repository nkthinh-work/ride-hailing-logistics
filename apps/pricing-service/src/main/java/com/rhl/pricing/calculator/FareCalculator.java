package com.rhl.pricing.calculator;

import com.rhl.pricing.models.FareBreakdown;
import com.rhl.pricing.models.FareCalculationInput;
import com.rhl.pricing.models.PricingPolicy;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Deterministic Fare Calculator implementing pure monetary calculation rules.
 *
 * <p>Principles:
 * <ul>
 *   <li>Pure Function: depends solely on (input, policy) without external side effects (FR-PRI-011).</li>
 *   <li>Strict BigDecimal arithmetic: zero binary floating-point errors (FR-PRI-016).</li>
 *   <li>Included distance deduction: distance fare applies only to {@code max(0, distanceKm - includedDistanceKm)}.</li>
 *   <li>Minimum fare constraint: {@code preSurgeFare = max(standardFare, minimumFare)}.</li>
 *   <li>Surge Multiplier applied to pre-surge amount.</li>
 *   <li>Final rounding: Single round-trip at the final total fare according to currency specification (VND: scale 0, HALF_UP).</li>
 * </ul>
 */
@Component
public class FareCalculator {

    public static final int INTERMEDIATE_SCALE = 4;
    public static final RoundingMode INTERMEDIATE_ROUNDING = RoundingMode.HALF_UP;

    /**
     * Calculates the transparent fare breakdown deterministically.
     *
     * @param input  calculation input (distance, duration, surge multiplier, additional fees, discount)
     * @param policy pricing policy (base fare, included distance, rates, minimum fare, currency)
     * @return complete immutable fare breakdown
     */
    public FareBreakdown calculate(FareCalculationInput input, PricingPolicy policy) {
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(policy, "policy must not be null");

        // 1. Base Fare
        BigDecimal baseFare = policy.baseFare();
        BigDecimal includedDistanceKm = policy.includedDistanceKm();

        // 2. Chargeable Distance: max(0, distanceKm - includedDistanceKm)
        BigDecimal chargeableDistanceKm = input.distanceKm().subtract(includedDistanceKm);
        if (chargeableDistanceKm.compareTo(BigDecimal.ZERO) < 0) {
            chargeableDistanceKm = BigDecimal.ZERO;
        }

        // 3. Distance Fare: chargeableDistanceKm * pricePerKm
        BigDecimal distanceFare = chargeableDistanceKm
                .multiply(policy.pricePerKm())
                .setScale(INTERMEDIATE_SCALE, INTERMEDIATE_ROUNDING);

        // 4. Time Fare: durationMinutes * pricePerMinute
        BigDecimal timeFare = input.durationMinutes()
                .multiply(policy.pricePerMinute())
                .setScale(INTERMEDIATE_SCALE, INTERMEDIATE_ROUNDING);

        // 5. Standard Fare: baseFare + distanceFare + timeFare
        BigDecimal standardFare = baseFare
                .add(distanceFare)
                .add(timeFare)
                .setScale(INTERMEDIATE_SCALE, INTERMEDIATE_ROUNDING);

        // 6. Pre-Surge Fare: max(standardFare, minimumFare)
        BigDecimal preSurgeFare = standardFare.max(policy.minimumFare());

        // 7. Surged Fare: preSurgeFare * surgeMultiplier
        BigDecimal surgeMultiplier = input.surgeMultiplier();
        BigDecimal surgedFare = preSurgeFare
                .multiply(surgeMultiplier)
                .setScale(INTERMEDIATE_SCALE, INTERMEDIATE_ROUNDING);

        // 8. Additional Fees, Tax & Discount
        BigDecimal additionalFees = input.additionalFees();
        BigDecimal tax = BigDecimal.ZERO; // Tax rule not yet defined in SRS Group 1
        BigDecimal discount = input.discount();

        // 9. Total Fare: max(0, surgedFare + additionalFees + tax - discount)
        BigDecimal unroundedTotal = surgedFare
                .add(additionalFees)
                .add(tax)
                .subtract(discount);

        if (unroundedTotal.compareTo(BigDecimal.ZERO) < 0) {
            unroundedTotal = BigDecimal.ZERO;
        }

        // 10. Final Rounding: rounded once at the final result based on currency
        BigDecimal finalTotal = roundFinalAmount(unroundedTotal, policy.currency());

        return new FareBreakdown(
                baseFare,
                includedDistanceKm,
                chargeableDistanceKm,
                distanceFare,
                input.durationMinutes(),
                timeFare,
                standardFare,
                policy.minimumFare(),
                preSurgeFare,
                surgeMultiplier,
                surgedFare,
                additionalFees,
                tax,
                discount,
                finalTotal,
                policy.currency(),
                policy.ruleVersion()
        );
    }

    /**
     * Applies final currency rounding.
     * For VND (DEV/TEST default): scale 0 with RoundingMode.HALF_UP.
     */
    private BigDecimal roundFinalAmount(BigDecimal amount, String currency) {
        if ("VND".equalsIgnoreCase(currency)) {
            return amount.setScale(0, RoundingMode.HALF_UP);
        }
        // Default decimal currency (USD/EUR etc.): 2 decimal places
        return amount.setScale(2, RoundingMode.HALF_UP);
    }
}
