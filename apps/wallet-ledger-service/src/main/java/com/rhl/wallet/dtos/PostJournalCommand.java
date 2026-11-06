package com.rhl.wallet.dtos;

import com.rhl.wallet.domain.LedgerTransactionType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PostJournalCommand(
        LedgerTransactionType transactionType,
        String businessReference,
        UUID externalEventId,
        String sourceService,
        String description,
        String currency,
        String correlationId,
        Instant occurredAt,
        String createdBy,
        List<PostEntryCommand> entries
) {
}
