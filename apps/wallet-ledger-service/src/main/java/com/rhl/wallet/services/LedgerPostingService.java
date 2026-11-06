package com.rhl.wallet.services;

import com.rhl.wallet.domain.DriverWallet;
import com.rhl.wallet.domain.EntryDirection;
import com.rhl.wallet.domain.LedgerEntry;
import com.rhl.wallet.domain.LedgerTransaction;
import com.rhl.wallet.domain.LedgerTransactionStatus;
import com.rhl.wallet.domain.LedgerTransactionType;
import com.rhl.wallet.dtos.LedgerTransactionResponse;
import com.rhl.wallet.dtos.PostEntryCommand;
import com.rhl.wallet.dtos.PostJournalCommand;
import com.rhl.wallet.exceptions.InvalidLedgerTransactionException;
import com.rhl.wallet.exceptions.LedgerTransactionAlreadyReversedException;
import com.rhl.wallet.exceptions.UnbalancedLedgerJournalException;
import com.rhl.wallet.exceptions.WalletNotFoundException;
import com.rhl.wallet.repositories.DriverWalletRepository;
import com.rhl.wallet.repositories.LedgerTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable Double-Entry Ledger Posting Engine (FR-WAL-002, FR-WAL-005, FR-WAL-006, FR-PAY-012).
 * Enforces atomic local PostgreSQL transaction boundaries and balanced sum(debits) == sum(credits).
 */
@Service
public class LedgerPostingService {

    private static final Logger log = LoggerFactory.getLogger(LedgerPostingService.class);

    private final LedgerTransactionRepository transactionRepository;
    private final DriverWalletRepository walletRepository;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public LedgerPostingService(
            LedgerTransactionRepository transactionRepository,
            DriverWalletRepository walletRepository,
            Clock clock
    ) {
        this.transactionRepository = Objects.requireNonNull(transactionRepository, "transactionRepository is required");
        this.walletRepository = Objects.requireNonNull(walletRepository, "walletRepository is required");
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public LedgerPostingService(
            LedgerTransactionRepository transactionRepository,
            DriverWalletRepository walletRepository
    ) {
        this(transactionRepository, walletRepository, Clock.systemUTC());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public LedgerTransactionResponse postJournal(PostJournalCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        // 1. Validation: Entries count >= 2
        List<PostEntryCommand> entries = command.entries();
        if (entries == null || entries.size() < 2) {
            throw new InvalidLedgerTransactionException("Ledger journal requires at least 2 entries, found: " + (entries == null ? 0 : entries.size()));
        }

        String currency = (command.currency() != null && !command.currency().isBlank()) ? command.currency().toUpperCase() : "VND";

        // 2. Validate all entry amounts and calculate totals
        BigDecimal totalDebit = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalCredit = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        for (PostEntryCommand entryCmd : entries) {
            if (entryCmd.amount() == null || entryCmd.amount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new InvalidLedgerTransactionException("Ledger entry amount must be strictly positive: " + entryCmd.amount());
            }
            if (entryCmd.direction() == EntryDirection.DEBIT) {
                totalDebit = totalDebit.add(entryCmd.amount()).setScale(2, RoundingMode.HALF_UP);
            } else if (entryCmd.direction() == EntryDirection.CREDIT) {
                totalCredit = totalCredit.add(entryCmd.amount()).setScale(2, RoundingMode.HALF_UP);
            } else {
                throw new InvalidLedgerTransactionException("Invalid entry direction: " + entryCmd.direction());
            }
        }

        // 3. Double-entry invariant: SUM(DEBIT) == SUM(CREDIT)
        if (totalDebit.compareTo(totalCredit) != 0) {
            log.warn("Unbalanced ledger journal rejected: sum(DEBIT)={} != sum(CREDIT)={}", totalDebit, totalCredit);
            throw new UnbalancedLedgerJournalException(totalDebit, totalCredit, currency);
        }

        // 4. Idempotency Check on businessReference
        Optional<LedgerTransaction> existingOpt = transactionRepository.findByBusinessReference(command.businessReference());
        if (existingOpt.isPresent()) {
            LedgerTransaction existing = existingOpt.get();
            if (isMatchingTransaction(existing, command, totalDebit)) {
                log.info("Idempotent replay detected for ledger transaction ref {}", command.businessReference());
                return LedgerTransactionResponse.from(existing);
            } else {
                throw new InvalidLedgerTransactionException(
                        "Business reference " + command.businessReference() + " already exists with differing payload");
            }
        }

        // Idempotency Check on externalEventId (if provided)
        if (command.externalEventId() != null) {
            Optional<LedgerTransaction> existingByEvent = transactionRepository.findByExternalEventId(command.externalEventId());
            if (existingByEvent.isPresent()) {
                LedgerTransaction existing = existingByEvent.get();
                if (isMatchingTransaction(existing, command, totalDebit)) {
                    log.info("Idempotent replay detected for externalEventId {}", command.externalEventId());
                    return LedgerTransactionResponse.from(existing);
                } else {
                    throw new InvalidLedgerTransactionException(
                            "External event ID " + command.externalEventId() + " already posted with differing transaction");
                }
            }
        }

        Instant now = clock.instant();
        Instant occurredAt = command.occurredAt() != null ? command.occurredAt() : now;

        // 5. Construct LedgerTransaction Aggregate
        LedgerTransaction transaction = new LedgerTransaction(
                UUID.randomUUID(),
                command.transactionType(),
                command.businessReference(),
                command.externalEventId(),
                command.sourceService(),
                command.description(),
                currency,
                command.correlationId(),
                occurredAt,
                now,
                command.createdBy(),
                null
        );

        // 6. Construct entries and update DriverWallet balance projections with Pessimistic Lock
        for (PostEntryCommand entryCmd : entries) {
            LedgerEntry entry = LedgerEntry.create(
                    entryCmd.accountId(),
                    entryCmd.walletId(),
                    entryCmd.direction(),
                    entryCmd.amount(),
                    currency,
                    entryCmd.description(),
                    now
            );
            transaction.addEntry(entry);

            if (entryCmd.walletId() != null) {
                DriverWallet wallet = walletRepository.findByIdForUpdate(entryCmd.walletId())
                        .orElseThrow(() -> new WalletNotFoundException("Wallet " + entryCmd.walletId() + " not found"));

                // In standard driver payable liability: CREDIT increases driver balance; DEBIT decreases driver balance
                if (entryCmd.direction() == EntryDirection.CREDIT) {
                    wallet.creditAvailable(entryCmd.amount(), now);
                } else {
                    wallet.debitAvailable(entryCmd.amount(), now);
                }
                walletRepository.save(wallet);
            }
        }

        // 7. Commit atomically
        LedgerTransaction saved = transactionRepository.save(transaction);
        log.info("Successfully posted balanced ledger transaction {} (ref: {}, total: {} {}) with {} entries",
                saved.getId(), saved.getBusinessReference(), totalDebit, currency, entries.size());

        return LedgerTransactionResponse.from(saved);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public LedgerTransactionResponse reverseJournal(UUID transactionId, String reason, String actorId) {
        LedgerTransaction original = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Ledger transaction " + transactionId + " not found"));

        if (original.getStatus() == LedgerTransactionStatus.REVERSED) {
            throw new LedgerTransactionAlreadyReversedException(transactionId);
        }

        Instant now = clock.instant();
        String reversalRef = "REV:" + original.getBusinessReference();

        // Check if reversal reference already posted
        Optional<LedgerTransaction> existingReversal = transactionRepository.findByBusinessReference(reversalRef);
        if (existingReversal.isPresent()) {
            return LedgerTransactionResponse.from(existingReversal.get());
        }

        // Build compensating entries (swap DEBIT <-> CREDIT)
        List<PostEntryCommand> compensatingEntries = new ArrayList<>();
        for (LedgerEntry origEntry : original.getEntries()) {
            EntryDirection reversedDir = (origEntry.getDirection() == EntryDirection.DEBIT)
                    ? EntryDirection.CREDIT : EntryDirection.DEBIT;

            compensatingEntries.add(new PostEntryCommand(
                    origEntry.getAccountId(),
                    origEntry.getWalletId(),
                    reversedDir,
                    origEntry.getAmount(),
                    "Reversal of entry " + origEntry.getId() + (reason != null ? ": " + reason : "")
            ));
        }

        // Mark original as REVERSED
        original.markReversed();
        transactionRepository.save(original);

        // Post compensating journal
        PostJournalCommand reversalCommand = new PostJournalCommand(
                LedgerTransactionType.REVERSAL,
                reversalRef,
                null,
                "wallet-ledger-service",
                "Reversal of transaction " + original.getId() + ": " + reason,
                original.getCurrency(),
                original.getCorrelationId(),
                now,
                actorId,
                compensatingEntries
        );

        LedgerTransactionResponse reversalResponse = postJournal(reversalCommand);
        log.info("Successfully reversed transaction {} via compensating journal {}",
                transactionId, reversalResponse.transactionId());

        return reversalResponse;
    }

    private boolean isMatchingTransaction(LedgerTransaction existing, PostJournalCommand command, BigDecimal totalDebit) {
        boolean typeMatches = existing.getTransactionType() == command.transactionType();
        boolean currencyMatches = existing.getCurrency().equalsIgnoreCase(command.currency() != null ? command.currency() : "VND");
        boolean entriesMatch = existing.getEntries() == null || existing.getEntries().isEmpty() || existing.getEntries().size() == command.entries().size();
        return typeMatches && currencyMatches && entriesMatch;
    }
}
