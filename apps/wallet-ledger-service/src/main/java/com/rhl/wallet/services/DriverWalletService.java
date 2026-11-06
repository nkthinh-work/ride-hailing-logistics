package com.rhl.wallet.services;

import com.rhl.wallet.domain.AccountOwnerType;
import com.rhl.wallet.domain.AccountType;
import com.rhl.wallet.domain.DriverWallet;
import com.rhl.wallet.domain.NormalBalance;
import com.rhl.wallet.domain.WalletAuditLog;
import com.rhl.wallet.domain.WalletStatus;
import com.rhl.wallet.exceptions.WalletNotFoundException;
import com.rhl.wallet.repositories.DriverWalletRepository;
import com.rhl.wallet.repositories.WalletAuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class DriverWalletService {

    private static final Logger log = LoggerFactory.getLogger(DriverWalletService.class);

    private final DriverWalletRepository walletRepository;
    private final LedgerAccountService accountService;
    private final WalletAuditLogRepository auditLogRepository;
    private final WalletCreationTxHelper txHelper;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public DriverWalletService(
            DriverWalletRepository walletRepository,
            LedgerAccountService accountService,
            WalletAuditLogRepository auditLogRepository,
            WalletCreationTxHelper txHelper,
            Clock clock
    ) {
        this.walletRepository = Objects.requireNonNull(walletRepository, "walletRepository is required");
        this.accountService = Objects.requireNonNull(accountService, "accountService is required");
        this.auditLogRepository = Objects.requireNonNull(auditLogRepository, "auditLogRepository is required");
        this.txHelper = Objects.requireNonNull(txHelper, "txHelper is required");
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public DriverWalletService(
            DriverWalletRepository walletRepository,
            LedgerAccountService accountService,
            WalletAuditLogRepository auditLogRepository,
            WalletCreationTxHelper txHelper
    ) {
        this(walletRepository, accountService, auditLogRepository, txHelper, Clock.systemUTC());
    }

    public DriverWallet getOrCreateWallet(UUID driverId, String currency) {
        String cleanCurrency = (currency != null && !currency.isBlank()) ? currency.toUpperCase() : "VND";

        Optional<DriverWallet> existing = walletRepository.findByDriverIdAndCurrency(driverId, cleanCurrency);
        if (existing.isPresent()) {
            return existing.get();
        }

        Instant now = clock.instant();
        try {
            return txHelper.createNewWallet(driverId, cleanCurrency, now);
        } catch (DataIntegrityViolationException ex) {
            // Concurrent wallet creation handled by database unique constraint uk_driver_wallets_driver_currency
            log.info("Concurrent wallet creation detected for driver {}, fetching existing", driverId);
            return walletRepository.findByDriverIdAndCurrency(driverId, cleanCurrency)
                    .orElseThrow(() -> new IllegalStateException("Failed to retrieve existing wallet after duplicate insert constraint", ex));
        }
    }

    @Transactional(readOnly = true)
    public DriverWallet getWalletByDriverId(UUID driverId) {
        return walletRepository.findByDriverId(driverId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet for driver " + driverId + " not found"));
    }

    @Transactional(readOnly = true)
    public DriverWallet getWalletById(UUID walletId) {
        return walletRepository.findById(walletId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet " + walletId + " not found"));
    }

    @Transactional
    public DriverWallet updateWalletStatus(UUID walletId, WalletStatus newStatus, String actorId, String actorRole, String reason) {
        DriverWallet wallet = walletRepository.findByIdForUpdate(walletId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet " + walletId + " not found"));

        WalletStatus previousStatus = wallet.getStatus();
        Instant now = clock.instant();
        wallet.updateStatus(newStatus, now);
        DriverWallet saved = walletRepository.save(wallet);

        auditLogRepository.save(WalletAuditLog.record(
                saved.getId(),
                "STATUS_UPDATED",
                previousStatus.name(),
                newStatus.name(),
                actorId,
                actorRole,
                reason,
                now
        ));

        log.info("Wallet {} status changed from {} to {} by {}", walletId, previousStatus, newStatus, actorId);
        return saved;
    }
}
