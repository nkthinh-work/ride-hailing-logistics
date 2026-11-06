package com.rhl.dispatch.entities;

import com.rhl.dispatch.exceptions.OfferAccessDeniedException;
import com.rhl.dispatch.exceptions.OfferExpiredException;
import com.rhl.dispatch.fsm.OfferStateMachine;
import com.rhl.dispatch.models.AssignmentStatus;
import com.rhl.dispatch.models.OfferStatus;
import com.rhl.dispatch.models.ServiceType;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Driver Offer Aggregate Root & JPA Entity (M06, Section 4.5.3, FR-MAT-007..014).
 * Encapsulates the driver offer lifecycle states, expiration rules, and domain invariants.
 */
@Entity
@Table(name = "driver_offers")
public class DriverOffer {

    @Id
    private UUID id;

    @Column(name = "trip_id", nullable = false)
    private UUID tripId;

    @Column(name = "driver_id", nullable = false)
    private UUID driverId;

    @Column(name = "vehicle_id")
    private UUID vehicleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "service_type", nullable = false, length = 32)
    private ServiceType serviceType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private OfferStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "assignment_status", nullable = false, length = 32)
    private AssignmentStatus assignmentStatus = AssignmentStatus.UNASSIGNED;

    @Column(name = "offered_at", nullable = false)
    private Instant offeredAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "assigned_at")
    private Instant assignedAt;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revocation_reason", length = 500)
    private String revocationReason;

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "offerId", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<DriverOfferHistory> statusHistory = new ArrayList<>();

    protected DriverOffer() {
        // JPA requirement
    }

    private DriverOffer(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "Offer id is required");
        this.tripId = Objects.requireNonNull(builder.tripId, "Trip id is required");
        this.driverId = Objects.requireNonNull(builder.driverId, "Driver id is required");
        this.vehicleId = builder.vehicleId;
        this.serviceType = Objects.requireNonNull(builder.serviceType, "Service type is required");
        this.offeredAt = Objects.requireNonNull(builder.offeredAt, "OfferedAt timestamp is required");
        this.expiresAt = Objects.requireNonNull(builder.expiresAt, "ExpiresAt timestamp is required");

        if (!this.expiresAt.isAfter(this.offeredAt)) {
            throw new IllegalArgumentException("ExpiresAt (" + expiresAt + ") must be strictly after OfferedAt (" + offeredAt + ")");
        }

        this.status = OfferStatus.PENDING;
        this.assignmentStatus = builder.assignmentStatus != null ? builder.assignmentStatus : AssignmentStatus.UNASSIGNED;
        this.assignedAt = builder.assignedAt;
        this.createdAt = builder.createdAt != null ? builder.createdAt : this.offeredAt;
        this.updatedAt = this.createdAt;

        // Record initial creation history
        this.statusHistory.add(DriverOfferHistory.of(
                this.id,
                null,
                OfferStatus.PENDING,
                builder.actorId != null ? builder.actorId : this.driverId,
                builder.actorRole != null ? builder.actorRole : "SYSTEM",
                "Offer created and dispatched",
                this.createdAt
        ));
    }

    public static Builder builder() {
        return new Builder();
    }

    // =========================================================================
    // Domain Lifecycle Transitions (Strict State Machine per SRS Section 4.5.3)
    // =========================================================================

    /**
     * Driver accepts this offer (PENDING -> ACCEPTED).
     *
     * @param driverId the authenticated driver ID attempting acceptance
     * @param now      authoritative server time
     */
    public void accept(UUID driverId, Instant now) {
        Objects.requireNonNull(driverId, "driverId is required");
        Objects.requireNonNull(now, "authoritative server time is required");

        if (!this.driverId.equals(driverId)) {
            throw new OfferAccessDeniedException(this.id, driverId);
        }

        if (isExpired(now)) {
            throw new OfferExpiredException(this.id, this.expiresAt, now);
        }

        OfferStateMachine.validateTransition(this.status, OfferStatus.ACCEPTED, "DRIVER");

        OfferStatus oldStatus = this.status;
        this.status = OfferStatus.ACCEPTED;
        this.acceptedAt = now;
        this.assignmentStatus = AssignmentStatus.PENDING;
        this.updatedAt = now;

        this.statusHistory.add(DriverOfferHistory.of(
                this.id,
                oldStatus,
                OfferStatus.ACCEPTED,
                driverId,
                "DRIVER",
                "Driver accepted offer",
                now
        ));
    }

    /**
     * Driver rejects this offer (PENDING -> REJECTED).
     *
     * @param driverId the authenticated driver ID attempting rejection
     * @param reason   rejection reason
     * @param now      authoritative server time
     */
    public void reject(UUID driverId, String reason, Instant now) {
        Objects.requireNonNull(driverId, "driverId is required");
        Objects.requireNonNull(now, "authoritative server time is required");

        if (!this.driverId.equals(driverId)) {
            throw new OfferAccessDeniedException(this.id, driverId);
        }

        if (isExpired(now)) {
            throw new OfferExpiredException(this.id, this.expiresAt, now);
        }

        OfferStateMachine.validateTransition(this.status, OfferStatus.REJECTED, "DRIVER");

        OfferStatus oldStatus = this.status;
        this.status = OfferStatus.REJECTED;
        this.rejectedAt = now;
        this.rejectionReason = reason;
        this.updatedAt = now;

        this.statusHistory.add(DriverOfferHistory.of(
                this.id,
                oldStatus,
                OfferStatus.REJECTED,
                driverId,
                "DRIVER",
                reason != null ? reason : "Driver rejected offer",
                now
        ));
    }

    /**
     * Server expires this offer upon timeout (PENDING -> EXPIRED).
     *
     * @param now authoritative server time
     */
    public void expire(Instant now) {
        Objects.requireNonNull(now, "authoritative server time is required");

        OfferStateMachine.validateTransition(this.status, OfferStatus.EXPIRED, "SYSTEM");

        OfferStatus oldStatus = this.status;
        this.status = OfferStatus.EXPIRED;
        this.updatedAt = now;

        this.statusHistory.add(DriverOfferHistory.of(
                this.id,
                oldStatus,
                OfferStatus.EXPIRED,
                this.driverId,
                "SYSTEM",
                "Offer expired due to timeout",
                now
        ));
    }

    /**
     * Server or Admin revokes this offer (PENDING -> REVOKED) e.g. when Trip is cancelled.
     *
     * @param reason    revocation reason
     * @param actorRole actor role initiating revocation
     * @param now       authoritative server time
     */
    public void revoke(String reason, String actorRole, Instant now) {
        Objects.requireNonNull(actorRole, "actorRole is required");
        Objects.requireNonNull(now, "authoritative server time is required");

        OfferStateMachine.validateTransition(this.status, OfferStatus.REVOKED, actorRole);

        OfferStatus oldStatus = this.status;
        this.status = OfferStatus.REVOKED;
        this.revokedAt = now;
        this.revocationReason = reason;
        this.updatedAt = now;

        this.statusHistory.add(DriverOfferHistory.of(
                this.id,
                oldStatus,
                OfferStatus.REVOKED,
                this.driverId,
                actorRole,
                reason != null ? reason : "Offer revoked",
                now
        ));
    }

    /**
     * Checks if the offer is expired based on the authoritative server timestamp.
     */
    public boolean isExpired(Instant now) {
        return !now.isBefore(this.expiresAt);
    }

    // =========================================================================
    // Getters
    // =========================================================================

    public UUID getId() {
        return id;
    }

    public UUID getTripId() {
        return tripId;
    }

    public UUID getDriverId() {
        return driverId;
    }

    public UUID getVehicleId() {
        return vehicleId;
    }

    public ServiceType getServiceType() {
        return serviceType;
    }

    public OfferStatus getStatus() {
        return status;
    }

    public Instant getOfferedAt() {
        return offeredAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public Instant getRejectedAt() {
        return rejectedAt;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public String getRevocationReason() {
        return revocationReason;
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

    public AssignmentStatus getAssignmentStatus() {
        return assignmentStatus;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }

    public void markAssigned(Instant now) {
        Objects.requireNonNull(now, "authoritative server time is required");
        if (this.status != OfferStatus.ACCEPTED) {
            throw new IllegalStateException("Cannot mark offer as assigned when status is " + this.status);
        }
        this.assignmentStatus = AssignmentStatus.ASSIGNED;
        this.assignedAt = now;
        this.updatedAt = now;
        this.statusHistory.add(DriverOfferHistory.of(
                this.id,
                this.status,
                this.status,
                this.driverId,
                "SYSTEM",
                "Trip assigned successfully",
                now
        ));
    }

    public void markAssignmentFailed(String reason, Instant now) {
        Objects.requireNonNull(now, "authoritative server time is required");
        this.assignmentStatus = AssignmentStatus.FAILED;
        this.updatedAt = now;
        this.statusHistory.add(DriverOfferHistory.of(
                this.id,
                this.status,
                this.status,
                this.driverId,
                "SYSTEM",
                "Trip assignment failed: " + (reason != null ? reason : "Unknown error"),
                now
        ));
    }

    public List<DriverOfferHistory> getStatusHistory() {
        return Collections.unmodifiableList(statusHistory);
    }

    // =========================================================================
    // Builder
    // =========================================================================

    public static class Builder {
        private UUID id;
        private UUID tripId;
        private UUID driverId;
        private UUID vehicleId;
        private ServiceType serviceType;
        private Instant offeredAt;
        private Instant expiresAt;
        private Instant createdAt;
        private UUID actorId;
        private String actorRole;
        private AssignmentStatus assignmentStatus = AssignmentStatus.UNASSIGNED;
        private Instant assignedAt;

        public Builder id(UUID id) {
            this.id = id;
            return this;
        }

        public Builder tripId(UUID tripId) {
            this.tripId = tripId;
            return this;
        }

        public Builder driverId(UUID driverId) {
            this.driverId = driverId;
            return this;
        }

        public Builder vehicleId(UUID vehicleId) {
            this.vehicleId = vehicleId;
            return this;
        }

        public Builder serviceType(ServiceType serviceType) {
            this.serviceType = serviceType;
            return this;
        }

        public Builder offeredAt(Instant offeredAt) {
            this.offeredAt = offeredAt;
            return this;
        }

        public Builder expiresAt(Instant expiresAt) {
            this.expiresAt = expiresAt;
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder actor(UUID actorId, String actorRole) {
            this.actorId = actorId;
            this.actorRole = actorRole;
            return this;
        }

        public Builder assignmentStatus(AssignmentStatus assignmentStatus) {
            this.assignmentStatus = assignmentStatus;
            return this;
        }

        public Builder assignedAt(Instant assignedAt) {
            this.assignedAt = assignedAt;
            return this;
        }

        public DriverOffer build() {
            return new DriverOffer(this);
        }
    }
}
