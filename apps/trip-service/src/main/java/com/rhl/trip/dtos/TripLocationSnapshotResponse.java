package com.rhl.trip.dtos;

import java.time.Instant;
import java.util.UUID;

/**
 * Authoritative response DTO for trip location snapshot (FR-RT-008, NFR-SEC-004).
 * Exposed strictly to authorized customer or assigned driver on reconnect.
 * Omits internal tokens, secrets, or raw session metadata.
 */
public record TripLocationSnapshotResponse(
        UUID tripId,
        UUID driverId,
        String tripStatus,
        Double latitude,
        Double longitude,
        Instant lastSeenTimestamp,
        boolean live
) {}
