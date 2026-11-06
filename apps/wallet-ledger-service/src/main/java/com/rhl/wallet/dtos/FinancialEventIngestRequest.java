package com.rhl.wallet.dtos;

import java.util.UUID;

public record FinancialEventIngestRequest(
        UUID eventId,
        String eventType,
        String sourceService,
        UUID entityId,
        String correlationId,
        String payload
) {
}
