package com.rhl.trip.events;

import java.time.Instant;
import java.util.UUID;

public record TripStatusPayload(
        UUID tripId,
        UUID customerId,
        UUID driverId,
        String status,
        String actor,
        String reason,
        Instant occurredAt,
        Long sequence
) {}
