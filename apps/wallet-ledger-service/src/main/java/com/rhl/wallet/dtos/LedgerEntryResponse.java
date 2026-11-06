package com.rhl.wallet.dtos;

import com.rhl.wallet.domain.LedgerEntry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LedgerEntryResponse(
        UUID entryId,
        UUID transactionId,
        UUID accountId,
        UUID walletId,
        String direction,
        BigDecimal amount,
        String currency,
        String description,
        Instant createdAt
) {
    public static LedgerEntryResponse from(LedgerEntry entry) {
        return new LedgerEntryResponse(
                entry.getId(),
                entry.getTransaction() != null ? entry.getTransaction().getId() : null,
                entry.getAccountId(),
                entry.getWalletId(),
                entry.getDirection().name(),
                entry.getAmount(),
                entry.getCurrency(),
                entry.getDescription(),
                entry.getCreatedAt()
        );
    }
}
