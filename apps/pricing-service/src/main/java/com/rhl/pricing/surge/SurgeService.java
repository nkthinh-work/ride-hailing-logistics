package com.rhl.pricing.surge;

import com.rhl.pricing.configs.PricingProperties;
import com.rhl.pricing.exceptions.ProductionQuotingBlockedException;
import com.rhl.pricing.models.ServiceType;
import com.rhl.pricing.models.VehicleType;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;

/**
 * Orchestrator service for Dynamic Surge Pricing (FR-PRI-007..010, FR-PRI-012).
 *
 * <p>Integrates {@link DemandSupplyProvider}, {@link SurgeCalculator}, and configuration properties.
 * Provides fail-closed safety guards for production environments.
 */
@Service
public class SurgeService {

    private final DemandSupplyProvider demandSupplyProvider;
    private final SurgeCalculator surgeCalculator;
    private final PricingProperties pricingProperties;

    public SurgeService(
            DemandSupplyProvider demandSupplyProvider,
            SurgeCalculator surgeCalculator,
            PricingProperties pricingProperties) {
        this.demandSupplyProvider = demandSupplyProvider;
        this.surgeCalculator = surgeCalculator;
        this.pricingProperties = pricingProperties;
    }

    /**
     * Calculates the dynamic surge multiplier for the requested trip parameters.
     *
     * @param serviceType service category
     * @param vehicleType vehicle category
     * @param regionId    geographical region
     * @param targetTime  evaluation timestamp
     * @return calculation result including multiplier, demand/supply metrics, and metadata
     */
    public SurgeCalculationResult calculateSurge(
            ServiceType serviceType,
            VehicleType vehicleType,
            String regionId,
            Instant targetTime) {

        Objects.requireNonNull(serviceType, "serviceType must not be null");
        Objects.requireNonNull(vehicleType, "vehicleType must not be null");
        Objects.requireNonNull(regionId, "regionId must not be null");
        Objects.requireNonNull(targetTime, "targetTime must not be null");

        PricingProperties.SurgeProperties surgeProps = pricingProperties.getSurge();
        SurgePolicy policy = surgeProps.toPolicy();

        // 1. If dynamic surge is disabled by business policy, return default 1.00x multiplier
        if (!surgeProps.isEnabled()) {
            return SurgeCalculationResult.noSurge(policy.policyVersion(), targetTime);
        }

        // 2. Production safety guard: do not calculate production quotes using simulated data
        if (!demandSupplyProvider.isRealtimeDataAvailable() && !surgeProps.isAllowSimulatedSurge()) {
            throw new ProductionQuotingBlockedException(
                    "Production surge quoting blocked: demand/supply provider is simulated ("
                            + demandSupplyProvider.getProviderName()
                            + ") while allowSimulatedSurge is disabled"
            );
        }

        // 3. Obtain demand and supply snapshot from provider
        SurgeCalculationInput input = demandSupplyProvider.getDemandSupplySnapshot(
                serviceType,
                vehicleType,
                regionId,
                targetTime,
                policy.timeWindowMinutes()
        );

        // 4. Calculate deterministic surge multiplier
        return surgeCalculator.calculate(input, policy, targetTime);
    }
}
