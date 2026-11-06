package com.rhl.dispatch.events;

import java.time.Instant;
import java.util.UUID;

public record DispatchEventEnvelope<T>(
        UUID eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        String correlationId,
        UUID entityId,
        T payload
) {
    public static <T> DispatchEventEnvelope<T> of(String eventType, UUID entityId, String correlationId, T payload) {
        return new DispatchEventEnvelope<>(
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
