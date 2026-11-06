package com.rhl.wallet.services;

import com.rhl.wallet.domain.AccountOwnerType;
import com.rhl.wallet.domain.AccountType;
import com.rhl.wallet.domain.LedgerAccount;
import com.rhl.wallet.domain.NormalBalance;
import com.rhl.wallet.repositories.LedgerAccountRepository;
import com.rhl.wallet.repositories.LedgerEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class LedgerAccountService {

    private static final Logger log = LoggerFactory.getLogger(LedgerAccountService.class);

    private final LedgerAccountRepository accountRepository;
    private final LedgerEntryRepository entryRepository;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public LedgerAccountService(
            LedgerAccountRepository accountRepository,
            LedgerEntryRepository entryRepository,
            Clock clock
    ) {
        this.accountRepository = Objects.requireNonNull(accountRepository, "accountRepository is required");
        this.entryRepository = Objects.requireNonNull(entryRepository, "entryRepository is required");
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public LedgerAccountService(
            LedgerAccountRepository accountRepository,
            LedgerEntryRepository entryRepository
    ) {
        this(accountRepository, entryRepository, Clock.systemUTC());
    }

    @Transactional
    public LedgerAccount getOrCreateAccount(
            String accountCode,
            AccountType accountType,
            NormalBalance normalBalance,
            AccountOwnerType ownerType,
            UUID ownerId,
            String currency
    ) {
        Optional<LedgerAccount> existing = accountRepository.findByAccountCode(accountCode);
        if (existing.isPresent()) {
            return existing.get();
        }

        Instant now = clock.instant();
        LedgerAccount newAccount = LedgerAccount.create(
                accountCode,
                accountType,
                normalBalance,
                ownerType,
                ownerId,
                currency != null ? currency.toUpperCase() : "VND",
                now
        );

        try {
            LedgerAccount saved = accountRepository.save(newAccount);
            log.info("Created LedgerAccount {} with code {}", saved.getId(), accountCode);
            return saved;
        } catch (DataIntegrityViolationException ex) {
            log.info("Concurrent account creation detected for code {}, fetching existing", accountCode);
            return accountRepository.findByAccountCode(accountCode)
                    .orElseThrow(() -> new IllegalStateException("Failed to retrieve existing account after unique constraint conflict", ex));
        }
    }

    @Transactional(readOnly = true)
    public BigDecimal calculateAccountBalance(UUID accountId) {
        LedgerAccount account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Ledger account " + accountId + " not found"));

        BigDecimal sumDebit = entryRepository.sumDebitByAccountId(accountId);
        BigDecimal sumCredit = entryRepository.sumCreditByAccountId(accountId);

        if (account.getNormalBalance() == NormalBalance.DEBIT) {
            return sumDebit.subtract(sumCredit).setScale(2, RoundingMode.HALF_UP);
        } else {
            return sumCredit.subtract(sumDebit).setScale(2, RoundingMode.HALF_UP);
        }
    }

    @Transactional(readOnly = true)
    public Optional<LedgerAccount> findByAccountCode(String accountCode) {
        return accountRepository.findByAccountCode(accountCode);
    }
}
