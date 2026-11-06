package com.rhl.realtime.models;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * Standard Realtime Message Envelope Contract (SRS COM-007, COM-009, NFR-MNT-001).
 * Supports client-side deduplication via eventId, versioning via eventVersion,
 * and ordering verification via occurredAt.
 */
public record EventEnvelope<T>(
        @NotNull UUID eventId,
        @NotNull RealtimeEventType eventType,
        int eventVersion,
        @NotNull Instant occurredAt,
        String correlationId,
        UUID entityId,
        @NotNull T payload
) {
    public static <T> EventEnvelope<T> of(RealtimeEventType type, UUID entityId, String correlationId, T payload) {
        return new EventEnvelope<>(
                UUID.randomUUID(),
                type,
                1,
                Instant.now(),
                correlationId,
                entityId,
                payload
        );
    }
}
