package com.rhl.pricing.surge;

import com.rhl.pricing.models.ServiceType;
import com.rhl.pricing.models.VehicleType;

import java.time.Instant;

/**
 * Abstraction for retrieving Demand and Supply metrics for dynamic pricing (FR-PRI-007, FR-PRI-008).
 *
 * <p>Separates the data acquisition source from the mathematical calculation engine.
 */
public interface DemandSupplyProvider {

    /**
     * Obtains demand and supply metrics for the given service, vehicle, region, and time window.
     *
     * @param serviceType        service category (RIDE, DELIVERY)
     * @param vehicleType        vehicle category (MOTORBIKE, CAR_4_SEAT, CAR_7_SEAT)
     * @param regionId           geographical region identifier
     * @param targetTime         reference timestamp
     * @param timeWindowMinutes  duration of the historical time window for demand
     * @return populated calculation input
     */
    SurgeCalculationInput getDemandSupplySnapshot(
            ServiceType serviceType,
            VehicleType vehicleType,
            String regionId,
            Instant targetTime,
            int timeWindowMinutes
    );

    /**
     * Indicates whether the returned metrics originate from live production services
     * (Trip Service and Location Service) or from simulated test fixtures.
     */
    boolean isRealtimeDataAvailable();

    /**
     * Identifying name of this data provider.
     */
    String getProviderName();
}
