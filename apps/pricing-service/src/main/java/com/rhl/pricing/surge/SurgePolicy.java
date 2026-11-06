package com.rhl.pricing.surge;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Immutable Dynamic Surge Policy definition (FR-PRI-009, FR-PRI-011, FR-PRI-012).
 *
 * <p><strong>NOTE:</strong> Current configuration is a PROVISIONAL_DEV_CONFIG for development and testing.
 * Official production policy parameters require explicit business approval.
 */
public record SurgePolicy(
        String policyId,
        String policyVersion,
        BigDecimal minMultiplier,
        BigDecimal maxMultiplier,
        BigDecimal thresholdRatio,
        BigDecimal sensitivity,
        int timeWindowMinutes,
        int roundingScale,
        RoundingMode roundingMode
) {

    public static final BigDecimal DEFAULT_MIN_MULTIPLIER = BigDecimal.valueOf(1.00).setScale(2, RoundingMode.HALF_UP);
    public static final BigDecimal DEFAULT_MAX_MULTIPLIER = BigDecimal.valueOf(2.50).setScale(2, RoundingMode.HALF_UP);
    public static final BigDecimal DEFAULT_THRESHOLD_RATIO = BigDecimal.valueOf(1.00).setScale(2, RoundingMode.HALF_UP);
    public static final BigDecimal DEFAULT_SENSITIVITY = BigDecimal.valueOf(0.50).setScale(2, RoundingMode.HALF_UP);
    public static final int DEFAULT_TIME_WINDOW_MINUTES = 15;
    public static final int DEFAULT_ROUNDING_SCALE = 2;
    public static final RoundingMode DEFAULT_ROUNDING_MODE = RoundingMode.HALF_UP;

    public SurgePolicy {
        Objects.requireNonNull(policyId, "policyId must not be null");
        Objects.requireNonNull(policyVersion, "policyVersion must not be null");
        Objects.requireNonNull(minMultiplier, "minMultiplier must not be null");
        Objects.requireNonNull(maxMultiplier, "maxMultiplier must not be null");
        Objects.requireNonNull(thresholdRatio, "thresholdRatio must not be null");
        Objects.requireNonNull(sensitivity, "sensitivity must not be null");
        Objects.requireNonNull(roundingMode, "roundingMode must not be null");

        // FR-PRI-009: Surge multiplier cannot be negative and must have valid lower/upper bounds
        if (minMultiplier.compareTo(BigDecimal.ONE) < 0) {
            throw new IllegalArgumentException("minMultiplier must not be less than 1.00, got: " + minMultiplier);
        }
        if (maxMultiplier.compareTo(minMultiplier) < 0) {
            throw new IllegalArgumentException("maxMultiplier (" + maxMultiplier + ") must be >= minMultiplier (" + minMultiplier + ")");
        }
        if (thresholdRatio.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("thresholdRatio must not be negative, got: " + thresholdRatio);
        }
        if (sensitivity.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("sensitivity must not be negative, got: " + sensitivity);
        }
        if (timeWindowMinutes <= 0) {
            throw new IllegalArgumentException("timeWindowMinutes must be positive, got: " + timeWindowMinutes);
        }
        if (roundingScale < 0) {
            throw new IllegalArgumentException("roundingScale must be >= 0, got: " + roundingScale);
        }
    }

    /**
     * Creates default provisional DEV surge policy.
     */
    public static SurgePolicy defaultDevPolicy() {
        return new SurgePolicy(
                "SURGE-DEV-POLICY",
                "SURGE-DEV-v1.0",
                DEFAULT_MIN_MULTIPLIER,
                DEFAULT_MAX_MULTIPLIER,
                DEFAULT_THRESHOLD_RATIO,
                DEFAULT_SENSITIVITY,
                DEFAULT_TIME_WINDOW_MINUTES,
                DEFAULT_ROUNDING_SCALE,
                DEFAULT_ROUNDING_MODE
        );
    }
}
