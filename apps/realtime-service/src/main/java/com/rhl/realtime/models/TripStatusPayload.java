package com.rhl.realtime.models;

import java.time.Instant;
import java.util.UUID;

/**
 * Trip status update payload for WebSocket delivery to customer's /user/queue/trips.
 */
public record TripStatusPayload(
        UUID tripId,
        UUID customerId,
        UUID driverId,
        String status,
        String actor,
        String reason,
        Instant occurredAt,
        Long sequence
) {
}
