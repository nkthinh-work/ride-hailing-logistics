
package com.rhl.location.dtos;

import java.time.Instant;
import java.util.UUID;

public record NearbyDriverResponse(
        UUID driverId,
        double longitude,
        double latitude,
        double distanceKm,
        Instant lastSeenTimestamp,
        UUID selectedVehicleId
) {
    public NearbyDriverResponse(UUID driverId, double longitude, double latitude, double distanceKm) {
        this(driverId, longitude, latitude, distanceKm, null, null);
    }

    public NearbyDriverResponse(UUID driverId, double longitude, double latitude, double distanceKm, Instant lastSeenTimestamp) {
        this(driverId, longitude, latitude, distanceKm, lastSeenTimestamp, null);
    }
}
