package com.rhl.dispatch.entities;

import com.rhl.dispatch.models.MatchingSessionStatus;
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
 * Matching Session entity tracking dispatch rounds and expansion radius (FR-MAT-001..004, FR-MAT-015).
 */
@Entity
@Table(name = "matching_sessions")
public class MatchingSession {

    @Id
    private UUID id;

    @Column(name = "trip_id", nullable = false)
    private UUID tripId;

    @Column(name = "round_number", nullable = false)
    private Integer roundNumber = 1;

    @Column(name = "radius_meters", nullable = false)
    private Integer radiusMeters;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private MatchingSessionStatus status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @jakarta.persistence.Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MatchingSession() {
        // JPA requirement
    }

    public MatchingSession(UUID id, UUID tripId, Integer roundNumber, Integer radiusMeters,
                           MatchingSessionStatus status, Instant startedAt) {
        this.id = Objects.requireNonNull(id, "id is required");
        this.tripId = Objects.requireNonNull(tripId, "tripId is required");
        this.roundNumber = roundNumber != null ? roundNumber : 1;
        this.radiusMeters = Objects.requireNonNull(radiusMeters, "radiusMeters is required");
        this.status = status != null ? status : MatchingSessionStatus.SEARCHING;
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt is required");
        this.createdAt = this.startedAt;
        this.updatedAt = this.startedAt;
    }

    public void advanceRound(int newRoundNumber, int newRadiusMeters, Instant now) {
        this.roundNumber = newRoundNumber;
        this.radiusMeters = newRadiusMeters;
        this.updatedAt = Objects.requireNonNull(now, "now is required");
    }

    public void complete(MatchingSessionStatus finalStatus, Instant now) {
        this.status = Objects.requireNonNull(finalStatus, "finalStatus is required");
        this.endedAt = Objects.requireNonNull(now, "now is required");
        this.updatedAt = now;
    }

    public Long getVersion() {
        return version;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTripId() {
        return tripId;
    }

    public Integer getRoundNumber() {
        return roundNumber;
    }

    public Integer getRadiusMeters() {
        return radiusMeters;
    }

    public MatchingSessionStatus getStatus() {
        return status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
