package com.rhl.wallet.dtos;

import com.rhl.wallet.domain.LedgerAccount;

import java.time.Instant;
import java.util.UUID;

public record LedgerAccountResponse(
        UUID accountId,
        String accountCode,
        String accountType,
        String normalBalance,
        String ownerType,
        UUID ownerId,
        String currency,
        String status,
        Instant createdAt
) {
    public static LedgerAccountResponse from(LedgerAccount account) {
        return new LedgerAccountResponse(
                account.getId(),
                account.getAccountCode(),
                account.getAccountType().name(),
                account.getNormalBalance().name(),
                account.getOwnerType().name(),
                account.getOwnerId(),
                account.getCurrency(),
                account.getStatus().name(),
                account.getCreatedAt()
        );
    }
}
