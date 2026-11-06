package com.rhl.payment.entities;

import com.rhl.payment.enums.PaymentMethod;
import com.rhl.payment.enums.PaymentStatus;
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
 * Payment Transaction Aggregate Root (SRS Section 4.8.1, FR-PAY-001..008).
 * Enforces financial invariants, monetary scale, strict state transitions, and audit records.
 */
@Entity
@Table(name = "payment_transactions")
public class PaymentTransaction {

    @Id
    private UUID id;

    @Column(name = "trip_id", nullable = false)
    private UUID tripId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 8)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 32)
    private PaymentMethod paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PaymentStatus status;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "provider_reference", length = 128)
    private String providerReference;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @OneToMany(mappedBy = "paymentId", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<PaymentStatusHistory> statusHistory = new ArrayList<>();

    protected PaymentTransaction() {
        // JPA
    }

    private PaymentTransaction(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "id is required");
        this.tripId = Objects.requireNonNull(builder.tripId, "tripId is required");
        this.customerId = Objects.requireNonNull(builder.customerId, "customerId is required");

        if (builder.amount == null || builder.amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidPaymentAmountException(builder.amount);
        }
        this.amount = builder.amount.setScale(2, RoundingMode.HALF_UP);

        this.currency = (builder.currency != null && !builder.currency.isBlank()) ? builder.currency : "VND";
        this.paymentMethod = Objects.requireNonNull(builder.paymentMethod, "paymentMethod is required");
        this.idempotencyKey = Objects.requireNonNull(builder.idempotencyKey, "idempotencyKey is required");

        this.status = PaymentStatus.PENDING;
        Instant now = builder.now != null ? builder.now : Instant.now();
        this.createdAt = now;
        this.updatedAt = now;

        // Initial creation audit
        String actor = builder.initialActor != null ? builder.initialActor : customerId.toString();
        String actorRole = builder.initialActorRole != null ? builder.initialActorRole : "CUSTOMER";
        this.statusHistory.add(new PaymentStatusHistory(
                this.id,
                null,
                PaymentStatus.PENDING,
                actor,
                actorRole,
                "Payment obligation initialized",
                now
        ));
    }

    public static Builder builder() {
        return new Builder();
    }

    // Domain State Machine Transitions

    public void startProcessing(String actor, String actorRole, String reason, Instant now) {
        transitionTo(PaymentStatus.PROCESSING, actor, actorRole, reason, now);
    }

    public void markSucceeded(String providerReference, String actor, String actorRole, Instant now) {
        if (!status.canTransitionTo(PaymentStatus.SUCCEEDED)) {
            throw new InvalidPaymentStateTransitionException(status, PaymentStatus.SUCCEEDED);
        }
        PaymentStatus prev = this.status;
        this.status = PaymentStatus.SUCCEEDED;
        this.providerReference = providerReference;
        this.failureReason = null;
        Instant timestamp = now != null ? now : Instant.now();
        this.updatedAt = timestamp;
        this.completedAt = timestamp;

        this.statusHistory.add(new PaymentStatusHistory(
                this.id,
                prev,
                PaymentStatus.SUCCEEDED,
                actor,
                actorRole,
                "Payment captured successfully with ref: " + providerReference,
                timestamp
        ));
    }

    public void markFailed(String failureReason, String actor, String actorRole, Instant now) {
        if (!status.canTransitionTo(PaymentStatus.FAILED)) {
            throw new InvalidPaymentStateTransitionException(status, PaymentStatus.FAILED);
        }
        PaymentStatus prev = this.status;
        this.status = PaymentStatus.FAILED;
        this.failureReason = failureReason;
        Instant timestamp = now != null ? now : Instant.now();
        this.updatedAt = timestamp;

        this.statusHistory.add(new PaymentStatusHistory(
                this.id,
                prev,
                PaymentStatus.FAILED,
                actor,
                actorRole,
                "Payment failed: " + failureReason,
                timestamp
        ));
    }

    public void cancel(String reason, String actor, String actorRole, Instant now) {
        transitionTo(PaymentStatus.CANCELLED, actor, actorRole, reason, now);
        this.completedAt = now != null ? now : Instant.now();
    }

    private void transitionTo(PaymentStatus target, String actor, String actorRole, String reason, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidPaymentStateTransitionException(status, target);
        }
        PaymentStatus prev = this.status;
        this.status = target;
        Instant timestamp = now != null ? now : Instant.now();
        this.updatedAt = timestamp;

        this.statusHistory.add(new PaymentStatusHistory(
                this.id,
                prev,
                target,
                actor,
                actorRole,
                reason,
                timestamp
        ));
    }

    // Getters

    public UUID getId() {
        return id;
    }

    public UUID getTripId() {
        return tripId;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public PaymentMethod getPaymentMethod() {
        return paymentMethod;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public String getFailureReason() {
        return failureReason;
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

    public List<PaymentStatusHistory> getStatusHistory() {
        return Collections.unmodifiableList(statusHistory);
    }

    // Builder Pattern

    public static class Builder {
        private UUID id = UUID.randomUUID();
        private UUID tripId;
        private UUID customerId;
        private BigDecimal amount;
        private String currency = "VND";
        private PaymentMethod paymentMethod;
        private String idempotencyKey;
        private Instant now;
        private String initialActor;
        private String initialActorRole;

        public Builder id(UUID id) {
            this.id = id;
            return this;
        }

        public Builder tripId(UUID tripId) {
            this.tripId = tripId;
            return this;
        }

        public Builder customerId(UUID customerId) {
            this.customerId = customerId;
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

        public Builder paymentMethod(PaymentMethod paymentMethod) {
            this.paymentMethod = paymentMethod;
            return this;
        }

        public Builder idempotencyKey(String idempotencyKey) {
            this.idempotencyKey = idempotencyKey;
            return this;
        }

        public Builder now(Instant now) {
            this.now = now;
            return this;
        }

        public Builder initialActor(String initialActor, String initialActorRole) {
            this.initialActor = initialActor;
            this.initialActorRole = initialActorRole;
            return this;
        }

        public PaymentTransaction build() {
            return new PaymentTransaction(this);
        }
    }
}
