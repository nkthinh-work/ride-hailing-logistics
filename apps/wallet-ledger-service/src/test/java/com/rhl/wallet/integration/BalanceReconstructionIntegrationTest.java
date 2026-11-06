package com.rhl.wallet.integration;

import com.rhl.wallet.domain.AccountOwnerType;
import com.rhl.wallet.domain.AccountType;
import com.rhl.wallet.domain.DriverWallet;
import com.rhl.wallet.domain.EntryDirection;
import com.rhl.wallet.domain.LedgerAccount;
import com.rhl.wallet.domain.LedgerTransactionType;
import com.rhl.wallet.domain.NormalBalance;
import com.rhl.wallet.dtos.PostEntryCommand;
import com.rhl.wallet.dtos.PostJournalCommand;
import com.rhl.wallet.dtos.WalletReconciliationResponse;
import com.rhl.wallet.repositories.DriverWalletRepository;
import com.rhl.wallet.services.DriverWalletService;
import com.rhl.wallet.services.LedgerAccountService;
import com.rhl.wallet.services.LedgerPostingService;
import com.rhl.wallet.services.WalletBalanceReconciliationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@EnabledIf("isDockerAvailable")
@DisplayName("Wallet Balance Reconstruction & Reconciliation Integration Test")
class BalanceReconstructionIntegrationTest {

    static PostgreSQLContainer postgres;

    static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        if (isDockerAvailable()) {
            if (postgres == null) {
                postgres = new PostgreSQLContainer("postgres:15-alpine");
                postgres.start();
            }
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        }
    }

    @Autowired
    private DriverWalletService walletService;

    @Autowired
    private DriverWalletRepository walletRepository;

    @Autowired
    private LedgerAccountService accountService;

    @Autowired
    private LedgerPostingService postingService;

    @Autowired
    private WalletBalanceReconciliationService reconciliationService;

    @Test
    @DisplayName("Reconciliation on new wallet reports inBalance = true with zero balance")
    void testReconcileInitialWalletBalance() {
        UUID driverId = UUID.randomUUID();
        DriverWallet wallet = walletService.getOrCreateWallet(driverId, "VND");

        WalletReconciliationResponse res = reconciliationService.reconcileWalletBalance(wallet.getId());

        assertThat(res.inBalance()).isTrue();
        assertThat(res.projectedAvailableBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(res.ledgerCalculatedBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(res.discrepancy()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("Reconciliation accurately matches balance after multiple posted transactions")
    void testReconcileAfterMultipleTransactions() {
        UUID driverId = UUID.randomUUID();
        DriverWallet wallet = walletService.getOrCreateWallet(driverId, "VND");

        LedgerAccount driverPayable = accountService.getOrCreateAccount(
                "DRV_PAY_" + driverId.toString().substring(0, 8),
                AccountType.LIABILITY,
                NormalBalance.CREDIT,
                AccountOwnerType.DRIVER,
                driverId,
                "VND"
        );

        LedgerAccount platformClearing = accountService.getOrCreateAccount(
                "SYS_CLEARING_" + UUID.randomUUID().toString().substring(0, 8),
                AccountType.ASSET,
                NormalBalance.DEBIT,
                AccountOwnerType.PLATFORM,
                null,
                "VND"
        );

        // Transaction 1: Credit driver payable 100,000 (Debit platform clearing 100,000)
        postingService.postJournal(new PostJournalCommand(
                LedgerTransactionType.TRIP_PAYMENT_SETTLEMENT,
                "TRIP-RECON-1-" + UUID.randomUUID(),
                UUID.randomUUID(),
                "test-runner",
                "Trip settlement credit",
                "VND",
                "corr-1",
                Instant.now(),
                "TEST_ACTOR",
                List.of(
                        new PostEntryCommand(platformClearing.getId(), null, EntryDirection.DEBIT, new BigDecimal("100000.00"), "Debit clearing"),
                        new PostEntryCommand(driverPayable.getId(), wallet.getId(), EntryDirection.CREDIT, new BigDecimal("100000.00"), "Credit driver")
                )
        ));

        // Transaction 2: Debit driver payable 30,000 (Credit platform clearing 30,000)
        postingService.postJournal(new PostJournalCommand(
                LedgerTransactionType.POLICY_RECONCILIATION,
                "TRIP-RECON-2-" + UUID.randomUUID(),
                UUID.randomUUID(),
                "test-runner",
                "Commission or fee adjustment",
                "VND",
                "corr-2",
                Instant.now(),
                "TEST_ACTOR",
                List.of(
                        new PostEntryCommand(driverPayable.getId(), wallet.getId(), EntryDirection.DEBIT, new BigDecimal("30000.00"), "Debit driver"),
                        new PostEntryCommand(platformClearing.getId(), null, EntryDirection.CREDIT, new BigDecimal("30000.00"), "Credit clearing")
                )
        ));

        // Reconcile
        WalletReconciliationResponse res = reconciliationService.reconcileWalletBalance(wallet.getId());

        assertThat(res.inBalance()).isTrue();
        assertThat(res.projectedAvailableBalance()).isEqualByComparingTo(new BigDecimal("70000.00"));
        assertThat(res.ledgerCalculatedBalance()).isEqualByComparingTo(new BigDecimal("70000.00"));
        assertThat(res.discrepancy()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("Reconciliation detects discrepancy if wallet projected balance is out of sync")
    void testReconcileDetectsDiscrepancyWhenCorrupted() {
        UUID driverId = UUID.randomUUID();
        DriverWallet wallet = walletService.getOrCreateWallet(driverId, "VND");

        LedgerAccount driverPayable = accountService.getOrCreateAccount(
                "DRV_PAY_" + driverId.toString().substring(0, 8),
                AccountType.LIABILITY,
                NormalBalance.CREDIT,
                AccountOwnerType.DRIVER,
                driverId,
                "VND"
        );

        LedgerAccount platformClearing = accountService.getOrCreateAccount(
                "SYS_CLEARING_" + UUID.randomUUID().toString().substring(0, 8),
                AccountType.ASSET,
                NormalBalance.DEBIT,
                AccountOwnerType.PLATFORM,
                null,
                "VND"
        );

        // Credit driver 50,000
        postingService.postJournal(new PostJournalCommand(
                LedgerTransactionType.TRIP_PAYMENT_SETTLEMENT,
                "TRIP-RECON-3-" + UUID.randomUUID(),
                UUID.randomUUID(),
                "test-runner",
                "Trip settlement credit",
                "VND",
                "corr-3",
                Instant.now(),
                "TEST_ACTOR",
                List.of(
                        new PostEntryCommand(platformClearing.getId(), null, EntryDirection.DEBIT, new BigDecimal("50000.00"), "Debit clearing"),
                        new PostEntryCommand(driverPayable.getId(), wallet.getId(), EntryDirection.CREDIT, new BigDecimal("50000.00"), "Credit driver")
                )
        ));

        // Artificially modify wallet projection in database to simulate discrepancy
        wallet = walletRepository.findById(wallet.getId()).orElseThrow();
        wallet.creditAvailable(new BigDecimal("20000.00"), Instant.now()); // Now wallet availableBalance is 70,000 while ledger sum is 50,000
        walletRepository.saveAndFlush(wallet);

        WalletReconciliationResponse res = reconciliationService.reconcileWalletBalance(wallet.getId());

        assertThat(res.inBalance()).isFalse();
        assertThat(res.projectedAvailableBalance()).isEqualByComparingTo(new BigDecimal("70000.00"));
        assertThat(res.ledgerCalculatedBalance()).isEqualByComparingTo(new BigDecimal("50000.00"));
        assertThat(res.discrepancy()).isEqualByComparingTo(new BigDecimal("20000.00"));
    }
}
