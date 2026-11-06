package com.rhl.wallet.domain;

import com.rhl.wallet.exceptions.InsufficientLedgerBalanceException;
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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * DriverWallet Aggregate Root (FR-WAL-001, FR-WAL-002, FR-WAL-008, FR-WAL-009).
 * Holds projection balances derived strictly from posted ledger entries.
 */
@Entity
@Table(name = "driver_wallets")
public class DriverWallet implements Persistable<UUID> {

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

    @Column(name = "driver_id", nullable = false)
    private UUID driverId;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private WalletStatus status;

    @Column(name = "available_balance", nullable = false, precision = 18, scale = 2)
    private BigDecimal availableBalance;

    @Column(name = "pending_balance", nullable = false, precision = 18, scale = 2)
    private BigDecimal pendingBalance;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DriverWallet() {
    }

    public DriverWallet(UUID id, UUID driverId, String currency, Instant now) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.driverId = Objects.requireNonNull(driverId, "driverId must not be null");
        this.currency = Objects.requireNonNull(currency, "currency must not be null").toUpperCase();
        this.status = WalletStatus.ACTIVE;
        this.availableBalance = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        this.pendingBalance = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        this.version = null;
        this.createdAt = Objects.requireNonNull(now, "now must not be null");
        this.updatedAt = now;
    }

    public static DriverWallet create(UUID driverId, String currency, Instant now) {
        return new DriverWallet(UUID.randomUUID(), driverId, currency, now);
    }

    public void creditAvailable(BigDecimal amount, Instant now) {
        validatePositiveAmount(amount);
        this.availableBalance = this.availableBalance.add(amount).setScale(2, RoundingMode.HALF_UP);
        this.updatedAt = now;
    }

    public void debitAvailable(BigDecimal amount, Instant now) {
        validatePositiveAmount(amount);
        BigDecimal newBalance = this.availableBalance.subtract(amount).setScale(2, RoundingMode.HALF_UP);
        if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
            throw new InsufficientLedgerBalanceException(
                    "Cannot debit " + amount + " " + currency + " from available balance " + availableBalance);
        }
        this.availableBalance = newBalance;
        this.updatedAt = now;
    }

    public void creditPending(BigDecimal amount, Instant now) {
        validatePositiveAmount(amount);
        this.pendingBalance = this.pendingBalance.add(amount).setScale(2, RoundingMode.HALF_UP);
        this.updatedAt = now;
    }

    public void debitPending(BigDecimal amount, Instant now) {
        validatePositiveAmount(amount);
        BigDecimal newPending = this.pendingBalance.subtract(amount).setScale(2, RoundingMode.HALF_UP);
        if (newPending.compareTo(BigDecimal.ZERO) < 0) {
            throw new InsufficientLedgerBalanceException(
                    "Cannot debit " + amount + " " + currency + " from pending balance " + pendingBalance);
        }
        this.pendingBalance = newPending;
        this.updatedAt = now;
    }

    public void updateStatus(WalletStatus newStatus, Instant now) {
        this.status = Objects.requireNonNull(newStatus, "newStatus must not be null");
        this.updatedAt = now;
    }

    public void setBalancesDirect(BigDecimal available, BigDecimal pending, Instant now) {
        if (available.compareTo(BigDecimal.ZERO) < 0) {
            throw new InsufficientLedgerBalanceException("Available balance cannot be negative: " + available);
        }
        if (pending.compareTo(BigDecimal.ZERO) < 0) {
            throw new InsufficientLedgerBalanceException("Pending balance cannot be negative: " + pending);
        }
        this.availableBalance = available.setScale(2, RoundingMode.HALF_UP);
        this.pendingBalance = pending.setScale(2, RoundingMode.HALF_UP);
        this.updatedAt = now;
    }

    private void validatePositiveAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be strictly positive: " + amount);
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getDriverId() {
        return driverId;
    }

    public String getCurrency() {
        return currency;
    }

    public WalletStatus getStatus() {
        return status;
    }

    public BigDecimal getAvailableBalance() {
        return availableBalance;
    }

    public BigDecimal getPendingBalance() {
        return pendingBalance;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
