package com.rhl.dispatch.providers;

import com.rhl.dispatch.models.TripAssignmentResult;
import com.rhl.dispatch.models.TripMatchingCriteriaSnapshot;

import java.util.UUID;

/**
 * SPI Provider for dispatch M2M interactions with Trip Service (M06 Group 3, FR-MAT-010, FR-MAT-015).
 */
public interface TripAssignmentProvider {

    /**
     * Atomically assigns the driver and vehicle to the trip in Trip Service.
     */
    TripAssignmentResult assignDriver(UUID tripId, UUID driverId, UUID vehicleId, UUID offerId);

    /**
     * Transitions trip from CREATED to MATCHING when matching begins.
     */
    TripMatchingCriteriaSnapshot startMatching(UUID tripId);

    /**
     * Marks trip as NO_DRIVER when candidate rounds are exhausted without an assigned driver.
     */
    void markNoDriver(UUID tripId, String reason);
}
