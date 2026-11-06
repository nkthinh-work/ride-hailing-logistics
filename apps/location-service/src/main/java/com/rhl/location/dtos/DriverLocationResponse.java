
package com.rhl.location.dtos;

import java.time.Instant;
import java.util.UUID;

public record DriverLocationResponse(
        UUID driverId,
        String status,
        Double longitude,
        Double latitude,
        Instant lastUpdatedAt,
        UUID sessionId,
        UUID selectedVehicleId
) {
    public DriverLocationResponse(
            UUID driverId,
            String status,
            Double longitude,
            Double latitude,
            Instant lastUpdatedAt
    ) {
        this(driverId, status, longitude, latitude, lastUpdatedAt, null, null);
    }
}
