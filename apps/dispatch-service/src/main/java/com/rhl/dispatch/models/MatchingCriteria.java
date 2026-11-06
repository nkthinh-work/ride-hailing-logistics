package com.rhl.dispatch.models;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Trusted matching criteria derived from authoritative persisted Trip data (FR-MAT-001, FR-MAT-002).
 * Never constructed directly from untrusted client-supplied input.
 */
public record MatchingCriteria(
        UUID tripId,
        double pickupLatitude,
        double pickupLongitude,
        ServiceType serviceType,
        VehicleType vehicleType,
        double radiusKm,
        int maxCandidates,
        int searchRound,
        Set<UUID> excludedDriverIds
) {
    public MatchingCriteria {
        Objects.requireNonNull(tripId, "tripId must not be null");
        Objects.requireNonNull(serviceType, "serviceType must not be null");
        Objects.requireNonNull(vehicleType, "vehicleType must not be null");

        if (Double.isNaN(pickupLatitude) || Double.isInfinite(pickupLatitude)
                || pickupLatitude < -85.0 || pickupLatitude > 85.0) {
            throw new IllegalArgumentException("pickupLatitude must be valid Web Mercator coordinate [-85.0, 85.0]");
        }

        if (Double.isNaN(pickupLongitude) || Double.isInfinite(pickupLongitude)
                || pickupLongitude < -180.0 || pickupLongitude > 180.0) {
            throw new IllegalArgumentException("pickupLongitude must be valid coordinate [-180.0, 180.0]");
        }

        if (!Double.isFinite(radiusKm) || radiusKm < 0.1 || radiusKm > 20.0) {
            throw new IllegalArgumentException("radiusKm must be between 0.1 and 20.0 km");
        }

        if (maxCandidates < 1 || maxCandidates > 50) {
            throw new IllegalArgumentException("maxCandidates must be between 1 and 50");
        }

        if (searchRound < 1) {
            throw new IllegalArgumentException("searchRound must be >= 1");
        }

        excludedDriverIds = excludedDriverIds != null ? Collections.unmodifiableSet(excludedDriverIds) : Collections.emptySet();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private UUID tripId;
        private double pickupLatitude;
        private double pickupLongitude;
        private ServiceType serviceType;
        private VehicleType vehicleType;
        private double radiusKm = 1.0;
        private int maxCandidates = 10;
        private int searchRound = 1;
        private Set<UUID> excludedDriverIds = Set.of();

        public Builder tripId(UUID tripId) {
            this.tripId = tripId;
            return this;
        }

        public Builder pickupLatitude(double pickupLatitude) {
            this.pickupLatitude = pickupLatitude;
            return this;
        }

        public Builder pickupLongitude(double pickupLongitude) {
            this.pickupLongitude = pickupLongitude;
            return this;
        }

        public Builder pickupLocation(double latitude, double longitude) {
            this.pickupLatitude = latitude;
            this.pickupLongitude = longitude;
            return this;
        }

        public Builder serviceType(ServiceType serviceType) {
            this.serviceType = serviceType;
            return this;
        }

        public Builder vehicleType(VehicleType vehicleType) {
            this.vehicleType = vehicleType;
            return this;
        }

        public Builder radiusKm(double radiusKm) {
            this.radiusKm = radiusKm;
            return this;
        }

        public Builder maxCandidates(int maxCandidates) {
            this.maxCandidates = maxCandidates;
            return this;
        }

        public Builder searchRound(int searchRound) {
            this.searchRound = searchRound;
            return this;
        }

        public Builder excludedDriverIds(Set<UUID> excludedDriverIds) {
            this.excludedDriverIds = excludedDriverIds != null ? excludedDriverIds : Set.of();
            return this;
        }

        public MatchingCriteria build() {
            return new MatchingCriteria(
                    tripId,
                    pickupLatitude,
                    pickupLongitude,
                    serviceType,
                    vehicleType,
                    radiusKm,
                    maxCandidates,
                    searchRound,
                    excludedDriverIds
            );
        }
    }
}
