package com.rhl.dispatch.providers;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * SPI for checking driver operational availability against active trips in Trip Service (FR-MAT-005, FR-DRV-009..011).
 */
public interface DriverAvailabilityProvider {

    /**
     * Checks if a single driver currently has an active trip (ACCEPTED, PICKING_UP, ARRIVED, IN_TRIP).
     *
     * @param driverId driver user ID
     * @return true if the driver is currently assigned and busy with an active trip
     */
    boolean isDriverBusy(UUID driverId);

    /**
     * Batch queries busy drivers among a list of candidate driver IDs to avoid N+1 roundtrips.
     *
     * @param driverIds list of candidate driver IDs
     * @return set of driver IDs that are currently busy with active trips
     */
    Set<UUID> findBusyDrivers(List<UUID> driverIds);
}
