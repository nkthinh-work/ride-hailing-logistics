package com.rhl.wallet.services;

import com.rhl.wallet.domain.AccountOwnerType;
import com.rhl.wallet.domain.AccountType;
import com.rhl.wallet.domain.DriverWallet;
import com.rhl.wallet.domain.NormalBalance;
import com.rhl.wallet.domain.WalletAuditLog;
import com.rhl.wallet.repositories.DriverWalletRepository;
import com.rhl.wallet.repositories.WalletAuditLogRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Component
public class WalletCreationTxHelper {

    private final DriverWalletRepository walletRepository;
    private final LedgerAccountService accountService;
    private final WalletAuditLogRepository auditLogRepository;

    public WalletCreationTxHelper(
            DriverWalletRepository walletRepository,
            LedgerAccountService accountService,
            WalletAuditLogRepository auditLogRepository
    ) {
        this.walletRepository = Objects.requireNonNull(walletRepository, "walletRepository is required");
        this.accountService = Objects.requireNonNull(accountService, "accountService is required");
        this.auditLogRepository = Objects.requireNonNull(auditLogRepository, "auditLogRepository is required");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DriverWallet createNewWallet(UUID driverId, String cleanCurrency, Instant now) {
        DriverWallet newWallet = DriverWallet.create(driverId, cleanCurrency, now);
        DriverWallet saved = walletRepository.saveAndFlush(newWallet);

        String accountCode = "DRIVER_PAYABLE:" + driverId;
        accountService.getOrCreateAccount(
                accountCode,
                AccountType.LIABILITY,
                NormalBalance.CREDIT,
                AccountOwnerType.DRIVER,
                driverId,
                cleanCurrency
        );

        auditLogRepository.save(WalletAuditLog.record(
                saved.getId(),
                "WALLET_CREATED",
                null,
                saved.getStatus().name(),
                driverId.toString(),
                "SYSTEM",
                "Lazy initialized driver wallet",
                now
        ));

        return saved;
    }
}
