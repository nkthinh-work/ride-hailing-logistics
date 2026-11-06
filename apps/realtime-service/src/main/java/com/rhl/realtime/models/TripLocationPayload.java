package com.rhl.realtime.models;

import java.time.Instant;
import java.util.UUID;

/**
 * Payload representing realtime driver GPS location in a trip room (FR-RT-003, NFR-PERF-001).
 * Broadcasted to /topic/trips.{tripId}.location.
 */
public record TripLocationPayload(
        UUID tripId,
        UUID driverId,
        Double latitude,
        Double longitude,
        Double headingDegrees,
        Double speedMetersPerSecond,
        Long sequenceNumber,
        Instant observedAt
) {}
