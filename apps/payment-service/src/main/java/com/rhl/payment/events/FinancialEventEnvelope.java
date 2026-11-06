package com.rhl.payment.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Standard envelope for financial domain events (SRS Section 4.9, CON-07, FR-EVT-001..008).
 */
public record FinancialEventEnvelope<T>(
        UUID eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        String correlationId,
        UUID entityId,
        T payload
) {
    public static <T> FinancialEventEnvelope<T> of(String eventType, UUID entityId, String correlationId, T payload) {
        return new FinancialEventEnvelope<>(
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
