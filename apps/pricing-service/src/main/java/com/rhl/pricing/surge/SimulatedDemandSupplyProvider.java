package com.rhl.pricing.surge;

import com.rhl.pricing.models.ServiceType;
import com.rhl.pricing.models.VehicleType;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simulated implementation of {@link DemandSupplyProvider} for DEV and TEST environments.
 *
 * <p><strong>CRITICAL:</strong> This provider is flagged with {@code isRealtimeDataAvailable() = false}.
 * It MUST NOT be used as authoritative data for production fare quote issuance.
 */
@Component
public class SimulatedDemandSupplyProvider implements DemandSupplyProvider {

    private final AtomicLong fixedDemand = new AtomicLong(-1);
    private final AtomicLong fixedSupply = new AtomicLong(-1);

    @Override
    public SurgeCalculationInput getDemandSupplySnapshot(
            ServiceType serviceType,
            VehicleType vehicleType,
            String regionId,
            Instant targetTime,
            int timeWindowMinutes) {

        long demand = fixedDemand.get();
        long supply = fixedSupply.get();

        // Default: 1:1 balance (no artificial surge in default dev mode)
        if (demand < 0) {
            demand = 10;
        }
        if (supply < 0) {
            supply = 10;
        }

        Instant windowStart = targetTime.minusSeconds(timeWindowMinutes * 60L);
        Instant windowEnd = targetTime;

        return new SurgeCalculationInput(
                demand,
                supply,
                serviceType,
                vehicleType,
                regionId,
                windowStart,
                windowEnd
        );
    }

    /**
     * Overrides demand and supply counts for testing specific scenarios.
     * Pass -1 to reset to default.
     */
    public void setFixedCounts(long demand, long supply) {
        this.fixedDemand.set(demand);
        this.fixedSupply.set(supply);
    }

    public void reset() {
        this.fixedDemand.set(-1);
        this.fixedSupply.set(-1);
    }

    @Override
    public boolean isRealtimeDataAvailable() {
        return false;
    }

    @Override
    public String getProviderName() {
        return "SIMULATED_DEMAND_SUPPLY_PROVIDER";
    }
}
