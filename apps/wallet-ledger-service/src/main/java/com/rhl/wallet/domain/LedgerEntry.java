package com.rhl.wallet.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Individual posting line in a double-entry transaction.
 */
@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id", nullable = false)
    private LedgerTransaction transaction;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "wallet_id")
    private UUID walletId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private EntryDirection direction;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(length = 255)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerEntry() {
    }

    public LedgerEntry(
            UUID id,
            UUID accountId,
            UUID walletId,
            EntryDirection direction,
            BigDecimal amount,
            String currency,
            String description,
            Instant now
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.accountId = Objects.requireNonNull(accountId, "accountId must not be null");
        this.walletId = walletId;
        this.direction = Objects.requireNonNull(direction, "direction must not be null");
        this.amount = Objects.requireNonNull(amount, "amount must not be null").setScale(2, RoundingMode.HALF_UP);
        if (this.amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Ledger entry amount must be strictly positive: " + amount);
        }
        this.currency = Objects.requireNonNull(currency, "currency must not be null").toUpperCase();
        this.description = description;
        this.createdAt = Objects.requireNonNull(now, "now must not be null");
    }

    public static LedgerEntry create(
            UUID accountId,
            UUID walletId,
            EntryDirection direction,
            BigDecimal amount,
            String currency,
            String description,
            Instant now
    ) {
        return new LedgerEntry(UUID.randomUUID(), accountId, walletId, direction, amount, currency, description, now);
    }

    public UUID getId() {
        return id;
    }

    public LedgerTransaction getTransaction() {
        return transaction;
    }

    void setTransaction(LedgerTransaction transaction) {
        this.transaction = transaction;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public UUID getWalletId() {
        return walletId;
    }

    public EntryDirection getDirection() {
        return direction;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getDescription() {
        return description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
