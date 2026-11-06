package com.rhl.trip.entities;

import com.rhl.trip.models.TripStatus;
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
 * Immutable Audit Log of Trip Status Transitions (DR-005, FR-TRIP-010, BR-014).
 */
@Entity
@Table(name = "trip_status_history")
public class TripStatusHistory {

    @Id
    private UUID id;

    @Column(name = "trip_id", nullable = false)
    private UUID tripId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status")
    private TripStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false)
    private TripStatus toStatus;

    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    @Column(name = "actor_role", nullable = false, length = 32)
    private String actorRole;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TripStatusHistory() {
        // JPA requirement
    }

    public TripStatusHistory(UUID id, UUID tripId, TripStatus fromStatus, TripStatus toStatus,
                             UUID actorId, String actorRole, String reason, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "History id cannot be null");
        this.tripId = Objects.requireNonNull(tripId, "Trip id cannot be null");
        this.fromStatus = fromStatus;
        this.toStatus = Objects.requireNonNull(toStatus, "To status cannot be null");
        this.actorId = Objects.requireNonNull(actorId, "Actor id cannot be null");
        this.actorRole = Objects.requireNonNull(actorRole, "Actor role cannot be null");
        this.reason = reason;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
    }

    public static TripStatusHistory of(UUID tripId, TripStatus fromStatus, TripStatus toStatus,
                                       UUID actorId, String actorRole, String reason, Instant createdAt) {
        return new TripStatusHistory(UUID.randomUUID(), tripId, fromStatus, toStatus, actorId, actorRole, reason, createdAt);
    }

    public UUID getId() {
        return id;
    }

    public UUID getTripId() {
        return tripId;
    }

    public TripStatus getFromStatus() {
        return fromStatus;
    }

    public TripStatus getToStatus() {
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
