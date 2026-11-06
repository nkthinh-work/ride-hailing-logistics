package com.rhl.pricing.dtos;

import com.rhl.pricing.entities.FareQuote;
import com.rhl.pricing.models.FareBreakdown;
import com.rhl.pricing.models.ServiceType;
import com.rhl.pricing.models.VehicleType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Transparent response payload containing the complete immutable Fare Quote snapshot (FR-PRI-013).
 */
public record FareQuoteResponse(
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
        long distanceMeters,
        long durationSeconds,
        String routeProvider,
        boolean isSimulated,
        String pricingRuleId,
        String pricingRuleVersion,
        FareBreakdown breakdown,
        BigDecimal totalFare,
        String currency,
        Instant createdAt,
        Instant expiresAt,
        boolean isExpired,
        boolean isSurgeApplied
) {
    public static FareQuoteResponse from(FareQuote quote, Instant now) {
        return new FareQuoteResponse(
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
                quote.getDistanceMeters(),
                quote.getDurationSeconds(),
                quote.getRouteProvider(),
                quote.isSimulated(),
                quote.getPricingRuleId(),
                quote.getPricingRuleVersion(),
                quote.toBreakdown(),
                quote.getTotalFare(),
                quote.getCurrency(),
                quote.getCreatedAt(),
                quote.getExpiresAt(),
                quote.isExpired(now),
                quote.isSurgeApplied()
        );
    }
}
