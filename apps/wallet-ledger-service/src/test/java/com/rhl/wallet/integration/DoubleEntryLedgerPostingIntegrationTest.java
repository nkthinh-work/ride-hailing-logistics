package com.rhl.wallet.integration;

import com.rhl.wallet.domain.AccountOwnerType;
import com.rhl.wallet.domain.AccountType;
import com.rhl.wallet.domain.DriverWallet;
import com.rhl.wallet.domain.EntryDirection;
import com.rhl.wallet.domain.LedgerAccount;
import com.rhl.wallet.domain.LedgerTransactionStatus;
import com.rhl.wallet.domain.LedgerTransactionType;
import com.rhl.wallet.domain.NormalBalance;
import com.rhl.wallet.dtos.LedgerTransactionResponse;
import com.rhl.wallet.dtos.PostEntryCommand;
import com.rhl.wallet.dtos.PostJournalCommand;
import com.rhl.wallet.exceptions.UnbalancedLedgerJournalException;
import com.rhl.wallet.repositories.DriverWalletRepository;
import com.rhl.wallet.repositories.LedgerEntryRepository;
import com.rhl.wallet.repositories.LedgerTransactionRepository;
import com.rhl.wallet.services.DriverWalletService;
import com.rhl.wallet.services.LedgerAccountService;
import com.rhl.wallet.services.LedgerPostingService;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@EnabledIf("isDockerAvailable")
@DisplayName("Double-Entry Ledger Posting Atomicity & Concurrency on Real PostgreSQL")
class DoubleEntryLedgerPostingIntegrationTest {

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
    private LedgerPostingService postingService;

    @Autowired
    private DriverWalletService walletService;

    @Autowired
    private LedgerAccountService accountService;

    @Autowired
    private LedgerTransactionRepository transactionRepository;

    @Autowired
    private LedgerEntryRepository entryRepository;

    @Autowired
    private DriverWalletRepository walletRepository;

    @Test
    @DisplayName("postJournal: Atomic commit of journal and entries with wallet balance projection")
    void postBalancedJournal_commitsAtomicallyAndUpdatesBalance() {
        UUID driverId = UUID.randomUUID();
        DriverWallet wallet = walletService.getOrCreateWallet(driverId, "VND");

        LedgerAccount clearing = accountService.getOrCreateAccount(
                "CLEARING:MOMO:" + UUID.randomUUID(),
                AccountType.ASSET,
                NormalBalance.DEBIT,
                AccountOwnerType.PLATFORM,
                null,
                "VND"
        );

        LedgerAccount driverAcc = accountService.getOrCreateAccount(
                "DRIVER_PAYABLE:" + driverId,
                AccountType.LIABILITY,
                NormalBalance.CREDIT,
                AccountOwnerType.DRIVER,
                driverId,
                "VND"
        );

        String businessRef = "TRIP-INT-POST-" + UUID.randomUUID();
        PostJournalCommand command = new PostJournalCommand(
                LedgerTransactionType.TRIP_PAYMENT_SETTLEMENT,
                businessRef,
                UUID.randomUUID(),
                "payment-service",
                "Trip settlement integration",
                "VND",
                "corr-int-1",
                Instant.now(),
                "TEST_ACTOR",
                List.of(
                        new PostEntryCommand(clearing.getId(), null, EntryDirection.DEBIT, new BigDecimal("150000.00"), "Debit clearing asset"),
                        new PostEntryCommand(driverAcc.getId(), wallet.getId(), EntryDirection.CREDIT, new BigDecimal("150000.00"), "Credit driver payable")
                )
        );

        LedgerTransactionResponse response = postingService.postJournal(command);

        assertThat(response).isNotNull();
        assertThat(response.businessReference()).isEqualTo(businessRef);
        assertThat(response.status()).isEqualTo("POSTED");

        // Verify persisted in PostgreSQL
        assertThat(transactionRepository.findByBusinessReference(businessRef)).isPresent();
        assertThat(entryRepository.findByTransaction_Id(response.transactionId())).hasSize(2);

        // Verify wallet balance updated
        DriverWallet updatedWallet = walletRepository.findById(wallet.getId()).orElseThrow();
        assertThat(updatedWallet.getAvailableBalance()).isEqualByComparingTo(new BigDecimal("150000.00"));
    }

    @Test
    @DisplayName("postJournal: Unbalanced journal rolls back completely without persisting any rows")
    void postUnbalancedJournal_rollsBackAtomicallyWithoutSideEffects() {
        UUID driverId = UUID.randomUUID();
        DriverWallet wallet = walletService.getOrCreateWallet(driverId, "VND");

        LedgerAccount clearing = accountService.getOrCreateAccount(
                "CLEARING:TEST:" + UUID.randomUUID(),
                AccountType.ASSET,
                NormalBalance.DEBIT,
                AccountOwnerType.PLATFORM,
                null,
                "VND"
        );

        LedgerAccount driverAcc = accountService.getOrCreateAccount(
                "DRIVER_PAYABLE:" + driverId,
                AccountType.LIABILITY,
                NormalBalance.CREDIT,
                AccountOwnerType.DRIVER,
                driverId,
                "VND"
        );

        String businessRef = "TRIP-UNBALANCED-" + UUID.randomUUID();
        PostJournalCommand command = new PostJournalCommand(
                LedgerTransactionType.TRIP_PAYMENT_SETTLEMENT,
                businessRef,
                UUID.randomUUID(),
                "payment-service",
                "Unbalanced integration",
                "VND",
                "corr-unb",
                Instant.now(),
                "TEST_ACTOR",
                List.of(
                        new PostEntryCommand(clearing.getId(), null, EntryDirection.DEBIT, new BigDecimal("100000.00"), "Debit 100k"),
                        new PostEntryCommand(driverAcc.getId(), wallet.getId(), EntryDirection.CREDIT, new BigDecimal("80000.00"), "Credit 80k")
                )
        );

        assertThatThrownBy(() -> postingService.postJournal(command))
                .isInstanceOf(UnbalancedLedgerJournalException.class);

        // Verify nothing was persisted into PostgreSQL
        assertThat(transactionRepository.findByBusinessReference(businessRef)).isEmpty();

        // Verify wallet balance remained unchanged at 0.00
        DriverWallet currentWallet = walletRepository.findById(wallet.getId()).orElseThrow();
        assertThat(currentWallet.getAvailableBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("reverseJournal: Creates compensating journal and restores wallet balance in PostgreSQL")
    void reverseJournal_createsCompensatingEntriesAndRestoresWalletBalance() {
        UUID driverId = UUID.randomUUID();
        DriverWallet wallet = walletService.getOrCreateWallet(driverId, "VND");

        LedgerAccount clearing = accountService.getOrCreateAccount(
                "CLEARING:REV:" + UUID.randomUUID(),
                AccountType.ASSET,
                NormalBalance.DEBIT,
                AccountOwnerType.PLATFORM,
                null,
                "VND"
        );

        LedgerAccount driverAcc = accountService.getOrCreateAccount(
                "DRIVER_PAYABLE:" + driverId,
                AccountType.LIABILITY,
                NormalBalance.CREDIT,
                AccountOwnerType.DRIVER,
                driverId,
                "VND"
        );

        String businessRef = "TRIP-TO-REV-" + UUID.randomUUID();
        PostJournalCommand command = new PostJournalCommand(
                LedgerTransactionType.TRIP_PAYMENT_SETTLEMENT,
                businessRef,
                UUID.randomUUID(),
                "payment-service",
                "Trip before reversal",
                "VND",
                "corr-rev",
                Instant.now(),
                "TEST_ACTOR",
                List.of(
                        new PostEntryCommand(clearing.getId(), null, EntryDirection.DEBIT, new BigDecimal("200000.00"), "Debit"),
                        new PostEntryCommand(driverAcc.getId(), wallet.getId(), EntryDirection.CREDIT, new BigDecimal("200000.00"), "Credit")
                )
        );

        LedgerTransactionResponse original = postingService.postJournal(command);

        // Execute reversal
        LedgerTransactionResponse reversal = postingService.reverseJournal(original.transactionId(), "Customer refund adjustment", "FINANCE_STAFF");

        assertThat(reversal.businessReference()).isEqualTo("REV:" + businessRef);
        assertThat(reversal.status()).isEqualTo("POSTED");

        // Verify original marked REVERSED
        assertThat(transactionRepository.findById(original.transactionId()).orElseThrow().getStatus())
                .isEqualTo(LedgerTransactionStatus.REVERSED);

        // Verify wallet balance restored to 0.00
        DriverWallet finalWallet = walletRepository.findById(wallet.getId()).orElseThrow();
        assertThat(finalWallet.getAvailableBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
