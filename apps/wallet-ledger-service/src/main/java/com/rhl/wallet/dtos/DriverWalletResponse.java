package com.rhl.wallet.dtos;

import com.rhl.wallet.domain.DriverWallet;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record DriverWalletResponse(
        UUID walletId,
        UUID driverId,
        String currency,
        String status,
        BigDecimal availableBalance,
        BigDecimal pendingBalance,
        Instant lastUpdated,
        String policyStatus
) {
    public static DriverWalletResponse from(DriverWallet wallet, String policyStatus) {
        return new DriverWalletResponse(
                wallet.getId(),
                wallet.getDriverId(),
                wallet.getCurrency(),
                wallet.getStatus().name(),
                wallet.getAvailableBalance(),
                wallet.getPendingBalance(),
                wallet.getUpdatedAt(),
                policyStatus
        );
    }
}
