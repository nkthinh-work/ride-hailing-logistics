package com.rhl.payment.entities;

import com.rhl.payment.enums.RefundStatus;
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
 * Immutable append-only audit record for refund status transitions.
 */
@Entity
@Table(name = "refund_status_history")
public class RefundStatusHistory {

    @Id
    private UUID id;

    @Column(name = "refund_id", nullable = false)
    private UUID refundId;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", length = 30)
    private RefundStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, length = 30)
    private RefundStatus newStatus;

    @Column(name = "actor", nullable = false, length = 100)
    private String actor;

    @Column(name = "actor_role", nullable = false, length = 50)
    private String actorRole;

    @Column(name = "reason", length = 255)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RefundStatusHistory() {
        // JPA
    }

    public RefundStatusHistory(
            UUID refundId,
            RefundStatus previousStatus,
            RefundStatus newStatus,
            String actor,
            String actorRole,
            String reason,
            Instant createdAt
    ) {
        this.id = UUID.randomUUID();
        this.refundId = Objects.requireNonNull(refundId, "refundId is required");
        this.previousStatus = previousStatus;
        this.newStatus = Objects.requireNonNull(newStatus, "newStatus is required");
        this.actor = Objects.requireNonNull(actor, "actor is required");
        this.actorRole = Objects.requireNonNull(actorRole, "actorRole is required");
        this.reason = reason;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getRefundId() {
        return refundId;
    }

    public RefundStatus getPreviousStatus() {
        return previousStatus;
    }

    public RefundStatus getNewStatus() {
        return newStatus;
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
