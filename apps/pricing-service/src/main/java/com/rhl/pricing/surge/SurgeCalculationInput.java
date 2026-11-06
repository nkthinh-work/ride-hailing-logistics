package com.rhl.pricing.surge;

import com.rhl.pricing.models.ServiceType;
import com.rhl.pricing.models.VehicleType;

import java.time.Instant;
import java.util.Objects;

/**
 * Input record for Dynamic Surge calculation (FR-PRI-007, FR-PRI-008, FR-PRI-011).
 *
 * <p><strong>Key Concept Distinctions:</strong>
 * <ul>
 *   <li>{@code demandCount} represents valid trip requests in the time window (NOT merely quote requests or app opens).</li>
 *   <li>{@code supplyCount} represents eligible, AVAILABLE drivers with fresh GPS in the region (NOT merely ONLINE presence in Redis).</li>
 * </ul>
 */
public record SurgeCalculationInput(
        long demandCount,
        long supplyCount,
        ServiceType serviceType,
        VehicleType vehicleType,
        String regionId,
        Instant windowStart,
        Instant windowEnd
) {

    public SurgeCalculationInput {
        Objects.requireNonNull(serviceType, "serviceType must not be null");
        Objects.requireNonNull(vehicleType, "vehicleType must not be null");
        Objects.requireNonNull(regionId, "regionId must not be null");
        Objects.requireNonNull(windowStart, "windowStart must not be null");
        Objects.requireNonNull(windowEnd, "windowEnd must not be null");

        if (demandCount < 0) {
            throw new IllegalArgumentException("demandCount must not be negative, got: " + demandCount);
        }
        if (supplyCount < 0) {
            throw new IllegalArgumentException("supplyCount must not be negative, got: " + supplyCount);
        }
        if (windowEnd.isBefore(windowStart)) {
            throw new IllegalArgumentException("windowEnd (" + windowEnd + ") cannot be before windowStart (" + windowStart + ")");
        }
    }
}
