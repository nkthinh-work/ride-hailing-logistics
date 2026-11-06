package com.rhl.wallet.dtos;

import java.time.Instant;
import java.util.UUID;

public record FinancialEventIngestResponse(
        UUID eventId,
        String status,
        String message,
        Instant processedAt
) {
}
