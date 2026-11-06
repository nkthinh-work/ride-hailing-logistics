package com.rhl.location.dtos;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

public record LocationUpdateRequest(

        @NotNull
        @DecimalMin("-180.0")
        @DecimalMax("180.0")
        Double longitude,

        @NotNull
        @DecimalMin("-85.0")
        @DecimalMax("85.0")
        Double latitude,

        Instant deviceTimestamp,

        Double accuracyMeters,

        Long sequence,

        Double headingDegrees,

        Double speedMetersPerSecond,

        UUID sessionId,

        UUID selectedVehicleId
) {
    public LocationUpdateRequest(Double longitude, Double latitude) {
        this(longitude, latitude, null, null, null, null, null, null, null);
    }

    public LocationUpdateRequest(
            Double longitude,
            Double latitude,
            Instant deviceTimestamp,
            Double accuracyMeters,
            Long sequence,
            Double headingDegrees,
            Double speedMetersPerSecond
    ) {
        this(longitude, latitude, deviceTimestamp, accuracyMeters, sequence, headingDegrees, speedMetersPerSecond, null, null);
    }
}
