package com.rhl.wallet.dtos;

import com.rhl.wallet.domain.LedgerTransaction;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record LedgerTransactionResponse(
        UUID transactionId,
        String transactionType,
        String businessReference,
        UUID externalEventId,
        String sourceService,
        String description,
        String currency,
        String status,
        String correlationId,
        Instant occurredAt,
        Instant postedAt,
        String createdBy,
        UUID reversedTransactionId,
        List<LedgerEntryResponse> entries
) {
    public static LedgerTransactionResponse from(LedgerTransaction tx) {
        List<LedgerEntryResponse> entryResponses = tx.getEntries().stream()
                .map(LedgerEntryResponse::from)
                .toList();

        return new LedgerTransactionResponse(
                tx.getId(),
                tx.getTransactionType().name(),
                tx.getBusinessReference(),
                tx.getExternalEventId(),
                tx.getSourceService(),
                tx.getDescription(),
                tx.getCurrency(),
                tx.getStatus().name(),
                tx.getCorrelationId(),
                tx.getOccurredAt(),
                tx.getPostedAt(),
                tx.getCreatedBy(),
                tx.getReversedTransactionId(),
                entryResponses
        );
    }
}
