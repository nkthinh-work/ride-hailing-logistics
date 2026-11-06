package com.rhl.wallet.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Append-only audit trail for driver wallet lifecycle modifications.
 */
@Entity
@Table(name = "wallet_audit_logs")
public class WalletAuditLog {

    @Id
    private UUID id;

    @Column(name = "wallet_id", nullable = false)
    private UUID walletId;

    @Column(nullable = false, length = 50)
    private String action;

    @Column(name = "previous_status", length = 30)
    private String previousStatus;

    @Column(name = "new_status", length = 30)
    private String newStatus;

    @Column(name = "actor_id", nullable = false, length = 100)
    private String actorId;

    @Column(name = "actor_role", nullable = false, length = 50)
    private String actorRole;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected WalletAuditLog() {
    }

    public WalletAuditLog(
            UUID id,
            UUID walletId,
            String action,
            String previousStatus,
            String newStatus,
            String actorId,
            String actorRole,
            String reason,
            Instant createdAt
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.walletId = Objects.requireNonNull(walletId, "walletId must not be null");
        this.action = Objects.requireNonNull(action, "action must not be null");
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
        this.actorId = Objects.requireNonNull(actorId, "actorId must not be null");
        this.actorRole = Objects.requireNonNull(actorRole, "actorRole must not be null");
        this.reason = reason;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public static WalletAuditLog record(
            UUID walletId,
            String action,
            String previousStatus,
            String newStatus,
            String actorId,
            String actorRole,
            String reason,
            Instant now
    ) {
        return new WalletAuditLog(UUID.randomUUID(), walletId, action, previousStatus, newStatus, actorId, actorRole, reason, now);
    }

    public UUID getId() {
        return id;
    }

    public UUID getWalletId() {
        return walletId;
    }

    public String getAction() {
        return action;
    }

    public String getPreviousStatus() {
        return previousStatus;
    }

    public String getNewStatus() {
        return newStatus;
    }

    public String getActorId() {
        return actorId;
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
