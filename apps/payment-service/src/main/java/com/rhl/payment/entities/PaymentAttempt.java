package com.rhl.payment.entities;

import com.rhl.payment.enums.PaymentAttemptStatus;
import com.rhl.payment.exceptions.InvalidPaymentAmountException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Entity representing an individual charge attempt dispatched to an external payment provider (FR-PAY-004..006).
 */
@Entity
@Table(name = "payment_attempts")
public class PaymentAttempt {

    @Id
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "attempt_number", nullable = false)
    private Integer attemptNumber;

    @Column(name = "provider", nullable = false, length = 32)
    private String provider;

    @Column(name = "provider_order_id", nullable = false, length = 128)
    private String providerOrderId;

    @Column(name = "provider_transaction_id", length = 128)
    private String providerTransactionId;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 8)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PaymentAttemptStatus status;

    @Column(name = "checkout_url", length = 1000)
    private String checkoutUrl;

    @Column(name = "idempotency_reference", nullable = false, length = 128)
    private String idempotencyReference;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    protected PaymentAttempt() {
        // JPA
    }

    private PaymentAttempt(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "id is required");
        this.paymentId = Objects.requireNonNull(builder.paymentId, "paymentId is required");
        this.attemptNumber = Objects.requireNonNull(builder.attemptNumber, "attemptNumber is required");
        this.provider = Objects.requireNonNull(builder.provider, "provider is required");
        this.providerOrderId = Objects.requireNonNull(builder.providerOrderId, "providerOrderId is required");

        if (builder.amount == null || builder.amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidPaymentAmountException(builder.amount);
        }
        this.amount = builder.amount.setScale(2, RoundingMode.HALF_UP);

        this.currency = (builder.currency != null && !builder.currency.isBlank()) ? builder.currency : "VND";
        this.status = builder.status != null ? builder.status : PaymentAttemptStatus.PENDING;
        this.checkoutUrl = builder.checkoutUrl;
        this.idempotencyReference = Objects.requireNonNull(builder.idempotencyReference, "idempotencyReference is required");
        Instant now = builder.now != null ? builder.now : Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static Builder builder() {
        return new Builder();
    }

    public void markProcessing(String checkoutUrl, Instant now) {
        if (this.status != PaymentAttemptStatus.PENDING) {
            throw new IllegalStateException("Cannot move to PROCESSING from " + this.status);
        }
        this.status = PaymentAttemptStatus.PROCESSING;
        this.checkoutUrl = checkoutUrl;
        Instant timestamp = now != null ? now : Instant.now();
        this.updatedAt = timestamp;
        this.processedAt = timestamp;
    }

    public void markSucceeded(String providerTransactionId, Instant now) {
        if (this.status == PaymentAttemptStatus.SUCCEEDED) {
            return; // Idempotent
        }
        this.status = PaymentAttemptStatus.SUCCEEDED;
        this.providerTransactionId = providerTransactionId;
        this.failureCode = null;
        this.failureReason = null;
        Instant timestamp = now != null ? now : Instant.now();
        this.updatedAt = timestamp;
        if (this.processedAt == null) {
            this.processedAt = timestamp;
        }
    }

    public void markFailed(String failureCode, String failureReason, Instant now) {
        if (this.status == PaymentAttemptStatus.SUCCEEDED) {
            throw new IllegalStateException("Cannot fail a SUCCEEDED payment attempt");
        }
        this.status = PaymentAttemptStatus.FAILED;
        this.failureCode = failureCode;
        this.failureReason = failureReason;
        Instant timestamp = now != null ? now : Instant.now();
        this.updatedAt = timestamp;
    }

    public void markUnknown(String reason, Instant now) {
        if (this.status.isTerminal()) {
            return;
        }
        this.status = PaymentAttemptStatus.UNKNOWN;
        this.failureReason = reason;
        this.updatedAt = now != null ? now : Instant.now();
    }

    public void markCancelled(String reason, Instant now) {
        if (this.status == PaymentAttemptStatus.SUCCEEDED) {
            throw new IllegalStateException("Cannot cancel a SUCCEEDED payment attempt");
        }
        this.status = PaymentAttemptStatus.CANCELLED;
        this.failureReason = reason;
        this.updatedAt = now != null ? now : Instant.now();
    }

    // Getters

    public UUID getId() {
        return id;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public Integer getAttemptNumber() {
        return attemptNumber;
    }

    public String getProvider() {
        return provider;
    }

    public String getProviderOrderId() {
        return providerOrderId;
    }

    public String getProviderTransactionId() {
        return providerTransactionId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public PaymentAttemptStatus getStatus() {
        return status;
    }

    public String getCheckoutUrl() {
        return checkoutUrl;
    }

    public String getIdempotencyReference() {
        return idempotencyReference;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public static class Builder {
        private UUID id = UUID.randomUUID();
        private UUID paymentId;
        private Integer attemptNumber = 1;
        private String provider;
        private String providerOrderId;
        private BigDecimal amount;
        private String currency = "VND";
        private PaymentAttemptStatus status = PaymentAttemptStatus.PENDING;
        private String checkoutUrl;
        private String idempotencyReference;
        private Instant now;

        public Builder id(UUID id) {
            this.id = id;
            return this;
        }

        public Builder paymentId(UUID paymentId) {
            this.paymentId = paymentId;
            return this;
        }

        public Builder attemptNumber(Integer attemptNumber) {
            this.attemptNumber = attemptNumber;
            return this;
        }

        public Builder provider(String provider) {
            this.provider = provider;
            return this;
        }

        public Builder providerOrderId(String providerOrderId) {
            this.providerOrderId = providerOrderId;
            return this;
        }

        public Builder amount(BigDecimal amount) {
            this.amount = amount;
            return this;
        }

        public Builder currency(String currency) {
            this.currency = currency;
            return this;
        }

        public Builder status(PaymentAttemptStatus status) {
            this.status = status;
            return this;
        }

        public Builder checkoutUrl(String checkoutUrl) {
            this.checkoutUrl = checkoutUrl;
            return this;
        }

        public Builder idempotencyReference(String idempotencyReference) {
            this.idempotencyReference = idempotencyReference;
            return this;
        }

        public Builder now(Instant now) {
            this.now = now;
            return this;
        }

        public PaymentAttempt build() {
            return new PaymentAttempt(this);
        }
    }
}
