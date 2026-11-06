package com.rhl.pricing.services;

import com.rhl.pricing.configs.PricingProperties;
import com.rhl.pricing.entities.PricingRule;
import com.rhl.pricing.exceptions.PricingRuleNotFoundException;
import com.rhl.pricing.models.PricingPolicy;
import com.rhl.pricing.models.PricingRuleStatus;
import com.rhl.pricing.models.ServiceType;
import com.rhl.pricing.models.VehicleType;
import com.rhl.pricing.repositories.PricingRuleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class PricingPolicyResolver {

    private final PricingRuleRepository pricingRuleRepository;
    private final PricingProperties pricingProperties;

    public PricingPolicyResolver(
            PricingRuleRepository pricingRuleRepository,
            PricingProperties pricingProperties) {
        this.pricingRuleRepository = pricingRuleRepository;
        this.pricingProperties = pricingProperties;
    }

    /**
     * Resolves the effective PricingPolicy for a given service type, vehicle type, region and timestamp.
     * Looks up PostgreSQL pricing_rules first. If no database rule exists, falls back to PROVISIONAL_DEV_CONFIG
     * for seamless development and testing.
     */
    @Transactional(readOnly = true)
    public PricingPolicy resolveEffectivePolicy(
            ServiceType serviceType,
            VehicleType vehicleType,
            String regionId,
            Instant effectiveTime) {

        String normalizedRegion = (regionId != null && !regionId.isBlank())
                ? regionId.trim().toUpperCase()
                : "DEFAULT";

        Instant queryTime = effectiveTime != null ? effectiveTime : Instant.now();

        List<PricingRule> rules = pricingRuleRepository.findEffectiveRules(
                serviceType,
                vehicleType,
                normalizedRegion,
                PricingRuleStatus.PUBLISHED,
                queryTime
        );

        if (!rules.isEmpty()) {
            return rules.getFirst().toPolicy();
        }

        // Production fail-closed: do NOT fallback to provisional DEV/TEST config unless explicitly enabled
        if (pricingProperties.isDevFallbackEnabled() && "DEFAULT".equals(normalizedRegion)) {
            return pricingProperties.getPolicy(serviceType, vehicleType)
                    .orElseThrow(() -> new PricingRuleNotFoundException(String.format(
                            "No effective pricing rule found for [%s:%s:%s] at %s",
                            serviceType, vehicleType, normalizedRegion, queryTime
                    )));
        }

        throw new PricingRuleNotFoundException(String.format(
                "No effective pricing rule found in database for [%s:%s:%s] at %s. Provisional DEV fallback is disabled (production fail-closed).",
                serviceType, vehicleType, normalizedRegion, queryTime
        ));
    }
}
