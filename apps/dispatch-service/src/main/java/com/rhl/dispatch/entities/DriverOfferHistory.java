package com.rhl.dispatch.entities;

import com.rhl.dispatch.models.OfferStatus;
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
 * Append-only audit record for driver offer lifecycle transitions (FR-MAT-010, Section 4.5.3).
 */
@Entity
@Table(name = "driver_offer_history")
public class DriverOfferHistory {

    @Id
    private UUID id;

    @Column(name = "offer_id", nullable = false)
    private UUID offerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 32)
    private OfferStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 32)
    private OfferStatus toStatus;

    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    @Column(name = "actor_role", nullable = false, length = 32)
    private String actorRole;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected DriverOfferHistory() {
        // JPA requirement
    }

    private DriverOfferHistory(UUID id, UUID offerId, OfferStatus fromStatus, OfferStatus toStatus,
                               UUID actorId, String actorRole, String reason, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id is required");
        this.offerId = Objects.requireNonNull(offerId, "offerId is required");
        this.fromStatus = fromStatus;
        this.toStatus = Objects.requireNonNull(toStatus, "toStatus is required");
        this.actorId = Objects.requireNonNull(actorId, "actorId is required");
        this.actorRole = Objects.requireNonNull(actorRole, "actorRole is required");
        this.reason = reason;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt is required");
    }

    public static DriverOfferHistory of(UUID offerId, OfferStatus fromStatus, OfferStatus toStatus,
                                        UUID actorId, String actorRole, String reason, Instant createdAt) {
        return new DriverOfferHistory(
                UUID.randomUUID(),
                offerId,
                fromStatus,
                toStatus,
                actorId,
                actorRole,
                reason,
                createdAt
        );
    }

    public UUID getId() {
        return id;
    }

    public UUID getOfferId() {
        return offerId;
    }

    public OfferStatus getFromStatus() {
        return fromStatus;
    }

    public OfferStatus getToStatus() {
        return toStatus;
    }

    public UUID getActorId() {
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
