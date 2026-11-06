package com.rhl.payment.entities;

import com.rhl.payment.enums.PaymentAttemptStatus;
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
 * Tracks individual provider refund execution attempts.
 */
@Entity
@Table(name = "refund_attempts")
public class RefundAttempt {

    @Id
    private UUID id;

    @Column(name = "refund_id", nullable = false)
    private UUID refundId;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Column(name = "provider", nullable = false, length = 50)
    private String provider;

    @Column(name = "provider_refund_order_id", nullable = false, length = 100)
    private String providerRefundOrderId;

    @Column(name = "provider_refund_trans_id", length = 100)
    private String providerRefundTransactionId;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private PaymentAttemptStatus status;

    @Column(name = "error_code", length = 50)
    private String errorCode;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RefundAttempt() {
        // JPA
    }

    public RefundAttempt(
            UUID id,
            UUID refundId,
            int attemptNumber,
            String provider,
            String providerRefundOrderId,
            BigDecimal amount,
            String currency,
            PaymentAttemptStatus status,
            Instant now
    ) {
        this.id = id != null ? id : UUID.randomUUID();
        this.refundId = Objects.requireNonNull(refundId, "refundId is required");
        this.attemptNumber = attemptNumber;
        this.provider = Objects.requireNonNull(provider, "provider is required");
        this.providerRefundOrderId = Objects.requireNonNull(providerRefundOrderId, "providerRefundOrderId is required");
        this.amount = Objects.requireNonNull(amount, "amount is required").setScale(2, RoundingMode.HALF_UP);
        this.currency = (currency != null && !currency.isBlank()) ? currency : "VND";
        this.status = status != null ? status : PaymentAttemptStatus.PENDING;
        Instant timestamp = now != null ? now : Instant.now();
        this.createdAt = timestamp;
        this.updatedAt = timestamp;
    }

    public void markProcessing(Instant now) {
        this.status = PaymentAttemptStatus.PROCESSING;
        this.updatedAt = now != null ? now : Instant.now();
    }

    public void markSucceeded(String providerRefundTransactionId, Instant now) {
        this.status = PaymentAttemptStatus.SUCCEEDED;
        this.providerRefundTransactionId = providerRefundTransactionId;
        this.updatedAt = now != null ? now : Instant.now();
    }

    public void markFailed(String errorCode, String errorMessage, Instant now) {
        this.status = PaymentAttemptStatus.FAILED;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.updatedAt = now != null ? now : Instant.now();
    }

    public void markUnknown(String message, Instant now) {
        this.status = PaymentAttemptStatus.UNKNOWN;
        this.errorMessage = message;
        this.updatedAt = now != null ? now : Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getRefundId() {
        return refundId;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public String getProvider() {
        return provider;
    }

    public String getProviderRefundOrderId() {
        return providerRefundOrderId;
    }

    public String getProviderRefundTransactionId() {
        return providerRefundTransactionId;
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

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
