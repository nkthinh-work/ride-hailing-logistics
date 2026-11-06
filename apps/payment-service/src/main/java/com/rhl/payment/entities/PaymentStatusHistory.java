package com.rhl.payment.entities;

import com.rhl.payment.enums.PaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Append-Only Financial Audit Record (SRS Section 4.8.1, FR-ADM-004..006).
 */
@Entity
@Table(name = "payment_status_history")
public class PaymentStatusHistory {

    @Id
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 32)
    private PaymentStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 32)
    private PaymentStatus toStatus;

    @Column(name = "actor", nullable = false, length = 128)
    private String actor;

    @Column(name = "actor_role", nullable = false, length = 64)
    private String actorRole;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PaymentStatusHistory() {
        // JPA
    }

    public PaymentStatusHistory(
            UUID paymentId,
            PaymentStatus fromStatus,
            PaymentStatus toStatus,
            String actor,
            String actorRole,
            String reason,
            Instant createdAt) {
        this.id = UUID.randomUUID();
        this.paymentId = Objects.requireNonNull(paymentId, "paymentId is required");
        this.fromStatus = fromStatus;
        this.toStatus = Objects.requireNonNull(toStatus, "toStatus is required");
        this.actor = Objects.requireNonNull(actor, "actor is required");
        this.actorRole = Objects.requireNonNull(actorRole, "actorRole is required");
        this.reason = reason;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public PaymentStatus getFromStatus() {
        return fromStatus;
    }

    public PaymentStatus getToStatus() {
        return toStatus;
    }

    public String getActor() {
        return actor;
    }

    public String getActorRole() {
        return actorRole;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
