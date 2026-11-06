package com.rhl.payment.entities;

import com.rhl.payment.enums.RefundStatus;
import com.rhl.payment.exceptions.InvalidPaymentAmountException;
import com.rhl.payment.exceptions.InvalidPaymentStateTransitionException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Payment Refund Aggregate Root (SRS Section 4.8.1, FR-PAY-012..013).
 * Represents an independent financial refund business aggregate with strict invariants.
 */
@Entity
@Table(name = "payment_refunds")
public class PaymentRefund {

    @Id
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "trip_id", nullable = false)
    private UUID tripId;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "reason", nullable = false, length = 255)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private RefundStatus status;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "requested_by", nullable = false, length = 100)
    private String requestedBy;

    @Column(name = "approved_by", length = 100)
    private String approvedBy;

    @Column(name = "provider", length = 50)
    private String provider;

    @Column(name = "provider_refund_order_id", length = 100)
    private String providerRefundOrderId;

    @Column(name = "provider_refund_trans_id", length = 100)
    private String providerRefundTransactionId;

    @Column(name = "policy_note", length = 255)
    private String policyNote;

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @OneToMany(mappedBy = "refundId", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<RefundStatusHistory> statusHistory = new ArrayList<>();

    protected PaymentRefund() {
        // JPA
    }

    private PaymentRefund(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "id is required");
        this.paymentId = Objects.requireNonNull(builder.paymentId, "paymentId is required");
        this.customerId = Objects.requireNonNull(builder.customerId, "customerId is required");
        this.tripId = Objects.requireNonNull(builder.tripId, "tripId is required");

        if (builder.amount == null || builder.amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidPaymentAmountException(builder.amount);
        }
        this.amount = builder.amount.setScale(2, RoundingMode.HALF_UP);
        this.currency = (builder.currency != null && !builder.currency.isBlank()) ? builder.currency : "VND";
        this.reason = Objects.requireNonNull(builder.reason, "reason is required");
        this.idempotencyKey = Objects.requireNonNull(builder.idempotencyKey, "idempotencyKey is required");
        this.requestedBy = Objects.requireNonNull(builder.requestedBy, "requestedBy is required");
        this.provider = builder.provider;
        this.policyNote = builder.policyNote;

        this.status = builder.initialStatus != null ? builder.initialStatus : RefundStatus.REQUESTED;
        Instant now = builder.now != null ? builder.now : Instant.now();
        this.createdAt = now;
        this.updatedAt = now;

        String actorRole = builder.actorRole != null ? builder.actorRole : "CUSTOMER";
        this.statusHistory.add(new RefundStatusHistory(
                this.id,
                null,
                this.status,
                this.requestedBy,
                actorRole,
                "Refund request initiated: " + this.reason,
                now
        ));
    }

    public static Builder builder() {
        return new Builder();
    }

    public void approve(String approvedBy, String reason, Instant now) {
        if (!this.status.canTransitionTo(RefundStatus.APPROVED)) {
            throw new InvalidPaymentStateTransitionException("Cannot transition refund from " + this.status + " to APPROVED");
        }
        RefundStatus previous = this.status;
        this.status = RefundStatus.APPROVED;
        this.approvedBy = Objects.requireNonNull(approvedBy, "approvedBy is required");
        Instant timestamp = now != null ? now : Instant.now();
        this.updatedAt = timestamp;
        this.statusHistory.add(new RefundStatusHistory(
                this.id,
                previous,
                RefundStatus.APPROVED,
                approvedBy,
                "FINANCE_STAFF",
                reason != null ? reason : "Refund approved by finance staff",
                timestamp
        ));
    }

    public void startProcessing(String providerRefundOrderId, String actor, String actorRole, String note, Instant now) {
        if (!this.status.canTransitionTo(RefundStatus.PROCESSING)) {
            throw new InvalidPaymentStateTransitionException("Cannot transition refund from " + this.status + " to PROCESSING");
        }
        RefundStatus previous = this.status;
        this.status = RefundStatus.PROCESSING;
        if (providerRefundOrderId != null) {
            this.providerRefundOrderId = providerRefundOrderId;
        }
        Instant timestamp = now != null ? now : Instant.now();
        this.updatedAt = timestamp;
        this.statusHistory.add(new RefundStatusHistory(
                this.id,
                previous,
                RefundStatus.PROCESSING,
                actor != null ? actor : "SYSTEM",
                actorRole != null ? actorRole : "SYSTEM",
                note != null ? note : "Refund processing started",
                timestamp
        ));
    }

    public void markSucceeded(String providerRefundTransactionId, String actor, String actorRole, Instant now) {
        if (!this.status.canTransitionTo(RefundStatus.SUCCEEDED)) {
            throw new InvalidPaymentStateTransitionException("Cannot transition refund from " + this.status + " to SUCCEEDED");
        }
        RefundStatus previous = this.status;
        this.status = RefundStatus.SUCCEEDED;
        this.providerRefundTransactionId = providerRefundTransactionId;
        Instant timestamp = now != null ? now : Instant.now();
        this.updatedAt = timestamp;
        this.completedAt = timestamp;
        this.statusHistory.add(new RefundStatusHistory(
                this.id,
                previous,
                RefundStatus.SUCCEEDED,
                actor != null ? actor : "PROVIDER",
                actorRole != null ? actorRole : "PROVIDER",
                "Refund completed successfully (transId: " + providerRefundTransactionId + ")",
                timestamp
        ));
    }

    public void markFailed(String reason, String actor, String actorRole, Instant now) {
        if (!this.status.canTransitionTo(RefundStatus.FAILED)) {
            throw new InvalidPaymentStateTransitionException("Cannot transition refund from " + this.status + " to FAILED");
        }
        RefundStatus previous = this.status;
        this.status = RefundStatus.FAILED;
        Instant timestamp = now != null ? now : Instant.now();
        this.updatedAt = timestamp;
        this.completedAt = timestamp;
        this.statusHistory.add(new RefundStatusHistory(
                this.id,
                previous,
                RefundStatus.FAILED,
                actor != null ? actor : "SYSTEM",
                actorRole != null ? actorRole : "SYSTEM",
                reason != null ? reason : "Refund failed",
                timestamp
        ));
    }

    public void cancel(String reason, String actor, String actorRole, Instant now) {
        if (!this.status.canTransitionTo(RefundStatus.CANCELLED)) {
            throw new InvalidPaymentStateTransitionException("Cannot transition refund from " + this.status + " to CANCELLED");
        }
        RefundStatus previous = this.status;
        this.status = RefundStatus.CANCELLED;
        Instant timestamp = now != null ? now : Instant.now();
        this.updatedAt = timestamp;
        this.completedAt = timestamp;
        this.statusHistory.add(new RefundStatusHistory(
                this.id,
                previous,
                RefundStatus.CANCELLED,
                actor != null ? actor : "SYSTEM",
                actorRole != null ? actorRole : "SYSTEM",
                reason != null ? reason : "Refund request cancelled/rejected",
                timestamp
        ));
    }

    public UUID getId() {
        return id;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public UUID getTripId() {
        return tripId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getReason() {
        return reason;
    }

    public RefundStatus getStatus() {
        return status;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public String getApprovedBy() {
        return approvedBy;
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

    public String getPolicyNote() {
        return policyNote;
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

    public Instant getCompletedAt() {
        return completedAt;
    }

    public List<RefundStatusHistory> getStatusHistory() {
        return Collections.unmodifiableList(statusHistory);
    }

    public static class Builder {
        private UUID id = UUID.randomUUID();
        private UUID paymentId;
        private UUID customerId;
        private UUID tripId;
        private BigDecimal amount;
        private String currency = "VND";
        private String reason;
        private String idempotencyKey;
        private String requestedBy;
        private String actorRole;
        private String provider;
        private String policyNote;
        private RefundStatus initialStatus = RefundStatus.REQUESTED;
        private Instant now;

        public Builder id(UUID id) {
            this.id = id;
            return this;
        }

        public Builder paymentId(UUID paymentId) {
            this.paymentId = paymentId;
            return this;
        }

        public Builder customerId(UUID customerId) {
            this.customerId = customerId;
            return this;
        }

        public Builder tripId(UUID tripId) {
            this.tripId = tripId;
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

        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public Builder idempotencyKey(String idempotencyKey) {
            this.idempotencyKey = idempotencyKey;
            return this;
        }

        public Builder requestedBy(String requestedBy) {
            this.requestedBy = requestedBy;
            return this;
        }

        public Builder actorRole(String actorRole) {
            this.actorRole = actorRole;
            return this;
        }

        public Builder provider(String provider) {
            this.provider = provider;
            return this;
        }

        public Builder policyNote(String policyNote) {
            this.policyNote = policyNote;
            return this;
        }

        public Builder initialStatus(RefundStatus initialStatus) {
            this.initialStatus = initialStatus;
            return this;
        }

        public Builder now(Instant now) {
            this.now = now;
            return this;
        }

        public PaymentRefund build() {
            return new PaymentRefund(this);
        }
    }
}
