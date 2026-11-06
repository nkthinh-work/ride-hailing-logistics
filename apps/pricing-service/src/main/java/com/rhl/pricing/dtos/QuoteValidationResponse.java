package com.rhl.pricing.dtos;

import com.rhl.pricing.entities.FareQuote;
import com.rhl.pricing.models.ServiceType;
import com.rhl.pricing.models.VehicleType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/**
 * Trusted Quote Snapshot returned to Trip Service upon successful cross-service validation (DR-004, FR-PRI-015).
 */
public record QuoteValidationResponse(
        UUID quoteId,
        UUID customerId,
        ServiceType serviceType,
        VehicleType vehicleType,
        String regionId,
        BigDecimal pickupLatitude,
        BigDecimal pickupLongitude,
        String pickupAddress,
        BigDecimal dropoffLatitude,
        BigDecimal dropoffLongitude,
        String dropoffAddress,
        BigDecimal estimatedDistanceKm,
        Integer estimatedDurationMinutes,
        BigDecimal estimatedFare,
        BigDecimal surgeMultiplier,
        String currency,
        String pricingRuleVersion,
        Instant expiresAt,
        boolean isSurgeApplied
) {
    public static QuoteValidationResponse from(FareQuote quote) {
        BigDecimal estimatedDistanceKm = BigDecimal.valueOf(quote.getDistanceMeters())
                .divide(BigDecimal.valueOf(1000), 2, RoundingMode.HALF_UP);
        Integer estimatedDurationMinutes = (int) Math.max(1, Math.round(quote.getDurationSeconds() / 60.0));

        return new QuoteValidationResponse(
                quote.getId(),
                quote.getCustomerId(),
                quote.getServiceType(),
                quote.getVehicleType(),
                quote.getRegionId(),
                quote.getPickupLatitude(),
                quote.getPickupLongitude(),
                quote.getPickupAddress(),
                quote.getDropoffLatitude(),
                quote.getDropoffLongitude(),
                quote.getDropoffAddress(),
                estimatedDistanceKm,
                estimatedDurationMinutes,
                quote.getTotalFare(),
                quote.getSurgeMultiplier(),
                quote.getCurrency(),
                quote.getPricingRuleVersion(),
                quote.getExpiresAt(),
                quote.isSurgeApplied()
        );
    }
}
