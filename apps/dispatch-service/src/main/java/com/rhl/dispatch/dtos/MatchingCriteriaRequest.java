package com.rhl.dispatch.dtos;

import com.rhl.dispatch.models.MatchingCriteria;
import com.rhl.dispatch.models.ServiceType;
import com.rhl.dispatch.models.VehicleType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.util.Set;
import java.util.UUID;

public record MatchingCriteriaRequest(
        @NotNull(message = "tripId is required")
        UUID tripId,

        @NotNull(message = "pickupLatitude is required")
        @DecimalMin(value = "-85.0", message = "Latitude must be >= -85.0")
        @DecimalMax(value = "85.0", message = "Latitude must be <= 85.0")
        Double pickupLatitude,

        @NotNull(message = "pickupLongitude is required")
        @DecimalMin(value = "-180.0", message = "Longitude must be >= -180.0")
        @DecimalMax(value = "180.0", message = "Longitude must be <= 180.0")
        Double pickupLongitude,

        @NotNull(message = "serviceType is required")
        ServiceType serviceType,

        @NotNull(message = "vehicleType is required")
        VehicleType vehicleType,

        Double radiusKm,
        Integer maxCandidates,
        Integer searchRound,
        Set<UUID> excludedDriverIds
) {
    public MatchingCriteria toCriteria(double defaultRadiusKm, int defaultMaxCandidates) {
        return MatchingCriteria.builder()
                .tripId(tripId)
                .pickupLocation(pickupLatitude, pickupLongitude)
                .serviceType(serviceType)
                .vehicleType(vehicleType)
                .radiusKm(radiusKm != null ? radiusKm : defaultRadiusKm)
                .maxCandidates(maxCandidates != null ? maxCandidates : defaultMaxCandidates)
                .searchRound(searchRound != null ? searchRound : 1)
                .excludedDriverIds(excludedDriverIds != null ? excludedDriverIds : Set.of())
                .build();
    }
}
