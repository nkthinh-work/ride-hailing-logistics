package com.rhl.dispatch.providers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * SPI for fetching nearby driver positions from Location Service (FR-MAT-001, FR-LOC-004).
 */
public interface LocationNearbyProvider {

    /**
     * Queries online drivers within the specified radius from pickup coordinates.
     *
     * @param longitude pickup longitude
     * @param latitude  pickup latitude
     * @param radiusKm  search radius in kilometers
     * @param limit     maximum candidates to retrieve from spatial index
     * @return list of nearby driver locations with timestamps
     */
    List<NearbyDriverLocation> findNearbyDrivers(double longitude, double latitude, double radiusKm, int limit);

    record NearbyDriverLocation(
            UUID driverId,
            double longitude,
            double latitude,
            double distanceKm,
            Instant lastSeenTimestamp,
            UUID selectedVehicleId
    ) {
        public NearbyDriverLocation(
                UUID driverId,
                double longitude,
                double latitude,
                double distanceKm,
                Instant lastSeenTimestamp
        ) {
            this(driverId, longitude, latitude, distanceKm, lastSeenTimestamp, null);
        }
    }
}
