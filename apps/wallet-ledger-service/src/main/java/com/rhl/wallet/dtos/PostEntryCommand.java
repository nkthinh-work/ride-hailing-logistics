package com.rhl.wallet.dtos;

import com.rhl.wallet.domain.EntryDirection;

import java.math.BigDecimal;
import java.util.UUID;

public record PostEntryCommand(
        UUID accountId,
        UUID walletId,
        EntryDirection direction,
        BigDecimal amount,
        String description
) {
}
