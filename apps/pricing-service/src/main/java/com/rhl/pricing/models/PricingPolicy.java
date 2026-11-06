package com.rhl.pricing.models;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Immutable pricing policy holding rate parameters for a service and vehicle type.
 * Note: When populated from PROVISIONAL_DEV_CONFIG, this represents dev/test rates only.
 */
public record PricingPolicy(
        String ruleId,
        String ruleVersion,
        ServiceType serviceType,
        VehicleType vehicleType,
        BigDecimal baseFare,
        BigDecimal includedDistanceKm,
        BigDecimal pricePerKm,
        BigDecimal pricePerMinute,
        BigDecimal minimumFare,
        String currency
) {
    public PricingPolicy {
        Objects.requireNonNull(ruleVersion, "ruleVersion must not be null");
        Objects.requireNonNull(serviceType, "serviceType must not be null");
        Objects.requireNonNull(vehicleType, "vehicleType must not be null");
        Objects.requireNonNull(baseFare, "baseFare must not be null");
        Objects.requireNonNull(includedDistanceKm, "includedDistanceKm must not be null");
        Objects.requireNonNull(pricePerKm, "pricePerKm must not be null");
        Objects.requireNonNull(pricePerMinute, "pricePerMinute must not be null");
        Objects.requireNonNull(minimumFare, "minimumFare must not be null");
        Objects.requireNonNull(currency, "currency must not be null");

        if (baseFare.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("baseFare must not be negative");
        }
        if (includedDistanceKm.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("includedDistanceKm must not be negative");
        }
        if (pricePerKm.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("pricePerKm must not be negative");
        }
        if (pricePerMinute.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("pricePerMinute must not be negative");
        }
        if (minimumFare.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("minimumFare must not be negative");
        }
        if (currency.isBlank()) {
            throw new IllegalArgumentException("currency must not be blank");
        }
    }
}
