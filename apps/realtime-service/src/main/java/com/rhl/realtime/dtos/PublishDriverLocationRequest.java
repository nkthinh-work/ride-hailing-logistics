package com.rhl.realtime.dtos;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

/**
 * Internal DTO for publishing validated driver telemetry to resolve active trip rooms.
 */
public record PublishDriverLocationRequest(
        @NotNull UUID driverId,
        @NotNull @DecimalMin("-85.0") @DecimalMax("85.0") Double latitude,
        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude,
        Double headingDegrees,
        Double speedMetersPerSecond,
        Long sequenceNumber,
        UUID sessionId,
        Instant observedAt
) {}
