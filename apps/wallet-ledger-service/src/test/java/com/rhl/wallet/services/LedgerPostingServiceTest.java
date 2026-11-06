package com.rhl.wallet.services;

import com.rhl.wallet.domain.DriverWallet;
import com.rhl.wallet.domain.EntryDirection;
import com.rhl.wallet.domain.LedgerTransaction;
import com.rhl.wallet.domain.LedgerTransactionStatus;
import com.rhl.wallet.domain.LedgerTransactionType;
import com.rhl.wallet.dtos.LedgerTransactionResponse;
import com.rhl.wallet.dtos.PostEntryCommand;
import com.rhl.wallet.dtos.PostJournalCommand;
import com.rhl.wallet.exceptions.InvalidLedgerTransactionException;
import com.rhl.wallet.exceptions.LedgerTransactionAlreadyReversedException;
import com.rhl.wallet.exceptions.UnbalancedLedgerJournalException;
import com.rhl.wallet.repositories.DriverWalletRepository;
import com.rhl.wallet.repositories.LedgerTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("LedgerPostingService Unit Tests — Double-Entry Invariants")
class LedgerPostingServiceTest {

    @Mock
    private LedgerTransactionRepository transactionRepository;

    @Mock
    private DriverWalletRepository walletRepository;

    private LedgerPostingService postingService;

    private final Instant fixedTime = Instant.parse("2026-10-27T08:00:00Z");
    private final Clock clock = Clock.fixed(fixedTime, ZoneOffset.UTC);

    private final UUID clearingAccountId = UUID.randomUUID();
    private final UUID driverAccountId = UUID.randomUUID();
    private final UUID driverId = UUID.randomUUID();
    private final UUID walletId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        postingService = new LedgerPostingService(transactionRepository, walletRepository, clock);
    }

    @Test
    @DisplayName("Balanced journal (DEBIT 100k == CREDIT 100k) succeeds and credits driver wallet")
    void postJournal_balanced_success() {
        DriverWallet wallet = DriverWallet.create(driverId, "VND", fixedTime);
        when(walletRepository.findByIdForUpdate(wallet.getId())).thenReturn(Optional.of(wallet));
        when(transactionRepository.findByBusinessReference("TRIP-PAY-1")).thenReturn(Optional.empty());
        when(transactionRepository.save(any(LedgerTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

        PostJournalCommand command = new PostJournalCommand(
                LedgerTransactionType.TRIP_PAYMENT_SETTLEMENT,
                "TRIP-PAY-1",
                UUID.randomUUID(),
                "payment-service",
                "Trip settlement",
                "VND",
                "corr-1",
                fixedTime,
                "SYSTEM",
                List.of(
                        new PostEntryCommand(clearingAccountId, null, EntryDirection.DEBIT, new BigDecimal("100000.00"), "Debit clearing"),
                        new PostEntryCommand(driverAccountId, wallet.getId(), EntryDirection.CREDIT, new BigDecimal("100000.00"), "Credit driver")
                )
        );

        LedgerTransactionResponse response = postingService.postJournal(command);

        assertThat(response.status()).isEqualTo("POSTED");
        assertThat(response.businessReference()).isEqualTo("TRIP-PAY-1");
        assertThat(response.entries()).hasSize(2);
        assertThat(wallet.getAvailableBalance()).isEqualByComparingTo(new BigDecimal("100000.00"));
    }

    @Test
    @DisplayName("Unbalanced journal (DEBIT 100k != CREDIT 90k) throws UnbalancedLedgerJournalException")
    void postJournal_unbalanced_throwsUnbalancedLedgerJournalException() {
        PostJournalCommand command = new PostJournalCommand(
                LedgerTransactionType.TRIP_PAYMENT_SETTLEMENT,
                "TRIP-PAY-UNBALANCED",
                UUID.randomUUID(),
                "payment-service",
                "Unbalanced trip settlement",
                "VND",
                "corr-unb",
                fixedTime,
                "SYSTEM",
                List.of(
                        new PostEntryCommand(clearingAccountId, null, EntryDirection.DEBIT, new BigDecimal("100000.00"), "Debit clearing"),
                        new PostEntryCommand(driverAccountId, null, EntryDirection.CREDIT, new BigDecimal("90000.00"), "Credit driver missing 10k")
                )
        );

        assertThatThrownBy(() -> postingService.postJournal(command))
                .isInstanceOf(UnbalancedLedgerJournalException.class)
                .hasMessageContaining("total DEBIT (100000.00 VND) does not equal total CREDIT (90000.00 VND)");
    }

    @Test
    @DisplayName("Single-entry journal is rejected (requires at least 2 entries)")
    void postJournal_singleEntry_throwsInvalidLedgerTransactionException() {
        PostJournalCommand command = new PostJournalCommand(
                LedgerTransactionType.TRIP_PAYMENT_SETTLEMENT,
                "TRIP-PAY-SINGLE",
                UUID.randomUUID(),
                "payment-service",
                "Single entry",
                "VND",
                "corr-single",
                fixedTime,
                "SYSTEM",
                List.of(
                        new PostEntryCommand(clearingAccountId, null, EntryDirection.DEBIT, new BigDecimal("100000.00"), "Debit clearing")
                )
        );

        assertThatThrownBy(() -> postingService.postJournal(command))
                .isInstanceOf(InvalidLedgerTransactionException.class)
                .hasMessageContaining("requires at least 2 entries");
    }

    @Test
    @DisplayName("Zero or negative entry amount throws InvalidLedgerTransactionException")
    void postJournal_negativeAmount_throwsInvalidLedgerTransactionException() {
        PostJournalCommand command = new PostJournalCommand(
                LedgerTransactionType.TRIP_PAYMENT_SETTLEMENT,
                "TRIP-PAY-NEG",
                UUID.randomUUID(),
                "payment-service",
                "Negative entry",
                "VND",
                "corr-neg",
                fixedTime,
                "SYSTEM",
                List.of(
                        new PostEntryCommand(clearingAccountId, null, EntryDirection.DEBIT, new BigDecimal("-50000.00"), "Debit negative"),
                        new PostEntryCommand(driverAccountId, null, EntryDirection.CREDIT, new BigDecimal("-50000.00"), "Credit negative")
                )
        );

        assertThatThrownBy(() -> postingService.postJournal(command))
                .isInstanceOf(InvalidLedgerTransactionException.class)
                .hasMessageContaining("must be strictly positive");
    }

    @Test
    @DisplayName("Duplicate businessReference returns idempotent replay response")
    void postJournal_duplicateBusinessRef_returnsIdempotentReplay() {
        LedgerTransaction existing = new LedgerTransaction(
                UUID.randomUUID(),
                LedgerTransactionType.TRIP_PAYMENT_SETTLEMENT,
                "TRIP-PAY-DUP",
                UUID.randomUUID(),
                "payment-service",
                "Existing tx",
                "VND",
                "corr-dup",
                fixedTime,
                fixedTime,
                "SYSTEM",
                null
        );
        when(transactionRepository.findByBusinessReference("TRIP-PAY-DUP")).thenReturn(Optional.of(existing));

        PostJournalCommand command = new PostJournalCommand(
                LedgerTransactionType.TRIP_PAYMENT_SETTLEMENT,
                "TRIP-PAY-DUP",
                UUID.randomUUID(),
                "payment-service",
                "Existing tx",
                "VND",
                "corr-dup",
                fixedTime,
                "SYSTEM",
                List.of(
                        new PostEntryCommand(clearingAccountId, null, EntryDirection.DEBIT, new BigDecimal("100000.00"), "Debit"),
                        new PostEntryCommand(driverAccountId, null, EntryDirection.CREDIT, new BigDecimal("100000.00"), "Credit")
                )
        );

        LedgerTransactionResponse response = postingService.postJournal(command);

        assertThat(response.transactionId()).isEqualTo(existing.getId());
        assertThat(response.businessReference()).isEqualTo("TRIP-PAY-DUP");
    }

    @Test
    @DisplayName("reverseJournal: Creates compensating journal, marks original REVERSED and cannot reverse twice")
    void reverseJournal_success_andCannotReverseTwice() {
        UUID txId = UUID.randomUUID();
        LedgerTransaction original = new LedgerTransaction(
                txId,
                LedgerTransactionType.TRIP_PAYMENT_SETTLEMENT,
                "TRIP-PAY-TO-REVERSE",
                UUID.randomUUID(),
                "payment-service",
                "Original",
                "VND",
                "corr-rev",
                fixedTime,
                fixedTime,
                "SYSTEM",
                null
        );

        com.rhl.wallet.domain.LedgerEntry entry1 = com.rhl.wallet.domain.LedgerEntry.create(
                clearingAccountId, null, EntryDirection.DEBIT, new BigDecimal("50000.00"), "VND", "Clearing debit", fixedTime
        );
        com.rhl.wallet.domain.LedgerEntry entry2 = com.rhl.wallet.domain.LedgerEntry.create(
                driverAccountId, null, EntryDirection.CREDIT, new BigDecimal("50000.00"), "VND", "Driver credit", fixedTime
        );
        original.addEntry(entry1);
        original.addEntry(entry2);

        when(transactionRepository.findById(txId)).thenReturn(Optional.of(original));
        when(transactionRepository.findByBusinessReference("REV:TRIP-PAY-TO-REVERSE")).thenReturn(Optional.empty());
        when(transactionRepository.save(any(LedgerTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

        LedgerTransactionResponse reversalResponse = postingService.reverseJournal(txId, "Driver dispute", "FINANCE_STAFF");

        assertThat(original.getStatus()).isEqualTo(LedgerTransactionStatus.REVERSED);
        assertThat(reversalResponse.businessReference()).isEqualTo("REV:TRIP-PAY-TO-REVERSE");
        assertThat(reversalResponse.entries()).hasSize(2);

        // Attempting to reverse again throws LedgerTransactionAlreadyReversedException
        assertThatThrownBy(() -> postingService.reverseJournal(txId, "Second attempt", "FINANCE_STAFF"))
                .isInstanceOf(LedgerTransactionAlreadyReversedException.class)
                .hasMessageContaining("is already reversed");
    }
}
