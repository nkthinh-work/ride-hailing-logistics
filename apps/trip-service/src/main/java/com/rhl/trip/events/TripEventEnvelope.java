package com.rhl.trip.events;

import java.time.Instant;
import java.util.UUID;

public record TripEventEnvelope<T>(
        UUID eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        String correlationId,
        UUID entityId,
        T payload
) {
    public static <T> TripEventEnvelope<T> of(String eventType, UUID entityId, String correlationId, T payload) {
        return new TripEventEnvelope<>(
                UUID.randomUUID(),
                eventType,
                1,
                Instant.now(),
                correlationId,
                entityId,
                payload
        );
    }
}
