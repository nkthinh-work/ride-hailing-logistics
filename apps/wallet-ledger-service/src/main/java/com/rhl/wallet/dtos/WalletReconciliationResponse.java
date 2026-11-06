package com.rhl.wallet.dtos;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record WalletReconciliationResponse(
        UUID walletId,
        UUID driverId,
        String currency,
        BigDecimal projectedAvailableBalance,
        BigDecimal ledgerCalculatedBalance,
        BigDecimal discrepancy,
        boolean inBalance,
        Instant reconciledAt
) {
}
