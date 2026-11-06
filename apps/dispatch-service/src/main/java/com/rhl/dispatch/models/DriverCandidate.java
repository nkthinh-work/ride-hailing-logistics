package com.rhl.dispatch.models;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Snapshot representation of an eligible, available driver candidate matching trip criteria (FR-MAT-001..003).
 * Note: A candidate snapshot is NOT a reservation or assigned trip.
 */
public record DriverCandidate(
        UUID driverId,
        UUID vehicleId,
        VehicleType vehicleType,
        double longitude,
        double latitude,
        double distanceKm,
        Instant lastSeenTimestamp,
        Long estimatedArrivalSeconds
) {
    public DriverCandidate {
        Objects.requireNonNull(driverId, "driverId must not be null");
        Objects.requireNonNull(vehicleId, "vehicleId must not be null");
        Objects.requireNonNull(vehicleType, "vehicleType must not be null");
        Objects.requireNonNull(lastSeenTimestamp, "lastSeenTimestamp must not be null");

        if (distanceKm < 0.0) {
            throw new IllegalArgumentException("distanceKm cannot be negative");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private UUID driverId;
        private UUID vehicleId;
        private VehicleType vehicleType;
        private double longitude;
        private double latitude;
        private double distanceKm;
        private Instant lastSeenTimestamp;
        private Long estimatedArrivalSeconds;

        public Builder driverId(UUID driverId) {
            this.driverId = driverId;
            return this;
        }

        public Builder vehicleId(UUID vehicleId) {
            this.vehicleId = vehicleId;
            return this;
        }

        public Builder vehicleType(VehicleType vehicleType) {
            this.vehicleType = vehicleType;
            return this;
        }

        public Builder location(double longitude, double latitude) {
            this.longitude = longitude;
            this.latitude = latitude;
            return this;
        }

        public Builder distanceKm(double distanceKm) {
            this.distanceKm = distanceKm;
            return this;
        }

        public Builder lastSeenTimestamp(Instant lastSeenTimestamp) {
            this.lastSeenTimestamp = lastSeenTimestamp;
            return this;
        }

        public Builder estimatedArrivalSeconds(Long estimatedArrivalSeconds) {
            this.estimatedArrivalSeconds = estimatedArrivalSeconds;
            return this;
        }

        public DriverCandidate build() {
            return new DriverCandidate(
                    driverId,
                    vehicleId,
                    vehicleType,
                    longitude,
                    latitude,
                    distanceKm,
                    lastSeenTimestamp,
                    estimatedArrivalSeconds
            );
        }
    }
}
