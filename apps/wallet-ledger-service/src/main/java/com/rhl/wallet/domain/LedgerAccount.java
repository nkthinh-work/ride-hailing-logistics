package com.rhl.wallet.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Chart of accounts representation for double-entry bookkeeping.
 */
@Entity
@Table(name = "ledger_accounts")
public class LedgerAccount implements Persistable<UUID> {

    @Id
    private UUID id;

    @Transient
    private boolean isNew = true;

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    @Column(name = "account_code", nullable = false, unique = true, length = 100)
    private String accountCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 30)
    private AccountType accountType;

    @Enumerated(EnumType.STRING)
    @Column(name = "normal_balance", nullable = false, length = 10)
    private NormalBalance normalBalance;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false, length = 30)
    private AccountOwnerType ownerType;

    @Column(name = "owner_id")
    private UUID ownerId;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AccountStatus status;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerAccount() {
    }

    public LedgerAccount(
            UUID id,
            String accountCode,
            AccountType accountType,
            NormalBalance normalBalance,
            AccountOwnerType ownerType,
            UUID ownerId,
            String currency,
            Instant now
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.accountCode = Objects.requireNonNull(accountCode, "accountCode must not be null");
        this.accountType = Objects.requireNonNull(accountType, "accountType must not be null");
        this.normalBalance = Objects.requireNonNull(normalBalance, "normalBalance must not be null");
        this.ownerType = Objects.requireNonNull(ownerType, "ownerType must not be null");
        this.ownerId = ownerId;
        this.currency = Objects.requireNonNull(currency, "currency must not be null").toUpperCase();
        this.status = AccountStatus.ACTIVE;
        this.version = null;
        this.createdAt = Objects.requireNonNull(now, "now must not be null");
    }

    public static LedgerAccount create(
            String accountCode,
            AccountType accountType,
            NormalBalance normalBalance,
            AccountOwnerType ownerType,
            UUID ownerId,
            String currency,
            Instant now
    ) {
        return new LedgerAccount(UUID.randomUUID(), accountCode, accountType, normalBalance, ownerType, ownerId, currency, now);
    }

    public UUID getId() {
        return id;
    }

    public String getAccountCode() {
        return accountCode;
    }

    public AccountType getAccountType() {
        return accountType;
    }

    public NormalBalance getNormalBalance() {
        return normalBalance;
    }

    public AccountOwnerType getOwnerType() {
        return ownerType;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public String getCurrency() {
        return currency;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
