package com.rhl.dispatch.models;

import java.util.UUID;

/**
 * Result of M2M trip assignment request to Trip Service (M06 Group 3, FR-MAT-010).
 */
public record TripAssignmentResult(
        UUID tripId,
        UUID driverId,
        UUID vehicleId,
        String status,
        boolean success,
        String errorCode,
        String errorMessage
) {
    public static TripAssignmentResult success(UUID tripId, UUID driverId, UUID vehicleId, String status) {
        return new TripAssignmentResult(tripId, driverId, vehicleId, status, true, null, null);
    }

    public static TripAssignmentResult failure(UUID tripId, UUID driverId, UUID vehicleId, String errorCode, String errorMessage) {
        return new TripAssignmentResult(tripId, driverId, vehicleId, null, false, errorCode, errorMessage);
    }
}
