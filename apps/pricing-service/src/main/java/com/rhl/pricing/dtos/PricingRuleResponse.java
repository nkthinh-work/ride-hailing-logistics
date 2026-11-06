package com.rhl.pricing.dtos;

import com.rhl.pricing.entities.PricingRule;
import com.rhl.pricing.models.PricingRuleStatus;
import com.rhl.pricing.models.ServiceType;
import com.rhl.pricing.models.VehicleType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PricingRuleResponse(
        UUID id,
        ServiceType serviceType,
        VehicleType vehicleType,
        String regionId,
        int version,
        BigDecimal baseFare,
        BigDecimal includedDistanceKm,
        BigDecimal pricePerKm,
        BigDecimal pricePerMinute,
        BigDecimal minimumFare,
        String currency,
        PricingRuleStatus status,
        Instant effectiveFrom,
        Instant effectiveTo,
        Instant createdAt,
        UUID createdBy
) {
    public static PricingRuleResponse fromEntity(PricingRule rule) {
        return new PricingRuleResponse(
                rule.getId(),
                rule.getServiceType(),
                rule.getVehicleType(),
                rule.getRegionId(),
                rule.getVersion(),
                rule.getBaseFare(),
                rule.getIncludedDistanceKm(),
                rule.getPricePerKm(),
                rule.getPricePerMinute(),
                rule.getMinimumFare(),
                rule.getCurrency(),
                rule.getStatus(),
                rule.getEffectiveFrom(),
                rule.getEffectiveTo(),
                rule.getCreatedAt(),
                rule.getCreatedBy()
        );
    }
}
