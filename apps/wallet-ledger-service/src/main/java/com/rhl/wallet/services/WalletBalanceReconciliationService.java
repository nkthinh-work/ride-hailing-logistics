package com.rhl.wallet.services;

import com.rhl.wallet.domain.DriverWallet;
import com.rhl.wallet.dtos.WalletReconciliationResponse;
import com.rhl.wallet.exceptions.WalletNotFoundException;
import com.rhl.wallet.repositories.DriverWalletRepository;
import com.rhl.wallet.repositories.LedgerEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class WalletBalanceReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(WalletBalanceReconciliationService.class);

    private final DriverWalletRepository walletRepository;
    private final LedgerEntryRepository entryRepository;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public WalletBalanceReconciliationService(
            DriverWalletRepository walletRepository,
            LedgerEntryRepository entryRepository,
            Clock clock
    ) {
        this.walletRepository = Objects.requireNonNull(walletRepository, "walletRepository is required");
        this.entryRepository = Objects.requireNonNull(entryRepository, "entryRepository is required");
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public WalletBalanceReconciliationService(
            DriverWalletRepository walletRepository,
            LedgerEntryRepository entryRepository
    ) {
        this(walletRepository, entryRepository, Clock.systemUTC());
    }

    @Transactional(readOnly = true)
    public WalletReconciliationResponse reconcileWalletBalance(UUID walletId) {
        DriverWallet wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet " + walletId + " not found"));

        BigDecimal sumCredits = entryRepository.sumCreditByWalletId(walletId);
        BigDecimal sumDebits = entryRepository.sumDebitByWalletId(walletId);

        BigDecimal ledgerCalculated = sumCredits.subtract(sumDebits).setScale(2, RoundingMode.HALF_UP);
        BigDecimal projected = wallet.getAvailableBalance();
        BigDecimal discrepancy = projected.subtract(ledgerCalculated).setScale(2, RoundingMode.HALF_UP);

        boolean inBalance = discrepancy.compareTo(BigDecimal.ZERO) == 0;
        Instant now = clock.instant();

        if (!inBalance) {
            log.warn("Reconciliation discrepancy detected on wallet {}: projected={}, ledgerCalculated={}, diff={}",
                    walletId, projected, ledgerCalculated, discrepancy);
        } else {
            log.info("Wallet {} in balance: projected={} == ledgerCalculated={}", walletId, projected, ledgerCalculated);
        }

        return new WalletReconciliationResponse(
                walletId,
                wallet.getDriverId(),
                wallet.getCurrency(),
                projected,
                ledgerCalculated,
                discrepancy,
                inBalance,
                now
        );
    }
}
