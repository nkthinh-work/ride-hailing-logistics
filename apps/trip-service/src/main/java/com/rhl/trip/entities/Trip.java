package com.rhl.trip.entities;

import com.rhl.trip.exceptions.InvalidTripStateTransitionException;
import com.rhl.trip.exceptions.TripAccessDeniedException;
import com.rhl.trip.fsm.TripStateMachine;
import com.rhl.trip.models.ServiceType;
import com.rhl.trip.models.TripStatus;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Trip Aggregate Root & JPA Entity (M05).
 * Encapsulates the 9-state lifecycle machine and domain invariants.
 */
@Entity
@Table(name = "trips")
public class Trip {

    @Id
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "driver_id")
    private UUID driverId;

    @Column(name = "quote_id", nullable = false)
    private UUID quoteId;

    @Enumerated(EnumType.STRING)
    @Column(name = "service_type", nullable = false, length = 32)
    private ServiceType serviceType;

    @Column(name = "vehicle_type", nullable = false, length = 32)
    private String vehicleType;

    @Column(name = "vehicle_id")
    private UUID vehicleId;

    // Pickup location
    @Column(name = "pickup_latitude", nullable = false, precision = 10, scale = 7)
    private BigDecimal pickupLatitude;

    @Column(name = "pickup_longitude", nullable = false, precision = 10, scale = 7)
    private BigDecimal pickupLongitude;

    @Column(name = "pickup_address", nullable = false, length = 500)
    private String pickupAddress;

    // Dropoff location
    @Column(name = "dropoff_latitude", nullable = false, precision = 10, scale = 7)
    private BigDecimal dropoffLatitude;

    @Column(name = "dropoff_longitude", nullable = false, precision = 10, scale = 7)
    private BigDecimal dropoffLongitude;

    @Column(name = "dropoff_address", nullable = false, length = 500)
    private String dropoffAddress;

    // Fare Quote Snapshot (DR-004)
    @Column(name = "estimated_distance_km", nullable = false, precision = 8, scale = 2)
    private BigDecimal estimatedDistanceKm;

    @Column(name = "estimated_duration_minutes", nullable = false)
    private Integer estimatedDurationMinutes;

    @Column(name = "estimated_fare", nullable = false, precision = 12, scale = 2)
    private BigDecimal estimatedFare;

    @Column(name = "surge_multiplier", nullable = false, precision = 4, scale = 2)
    private BigDecimal surgeMultiplier;

    @Column(name = "currency", nullable = false, length = 8)
    private String currency;

    @Column(name = "pricing_rule_version", nullable = false, length = 64)
    private String pricingRuleVersion;

    // Lifecycle Status
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private TripStatus status;

    // Lifecycle Timestamps
    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "arrived_at")
    private Instant arrivedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    // Cancellation details
    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    @Column(name = "cancelled_by")
    private UUID cancelledBy;

    @Column(name = "cancellation_actor_role", length = 32)
    private String cancellationActorRole;

    @Column(name = "cancellation_fee", precision = 12, scale = 2)
    private BigDecimal cancellationFee;

    // Optimistic Concurrency Control (DR-009)
    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "tripId", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<TripStatusHistory> statusHistory = new ArrayList<>();

    protected Trip() {
        // JPA requirement
    }

    private Trip(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "Trip id is required");
        this.idempotencyKey = Objects.requireNonNull(builder.idempotencyKey, "Idempotency key is required");
        this.customerId = Objects.requireNonNull(builder.customerId, "Customer id is required");
        this.quoteId = Objects.requireNonNull(builder.quoteId, "Quote id is required");
        this.serviceType = Objects.requireNonNull(builder.serviceType, "Service type is required");
        this.vehicleType = Objects.requireNonNull(builder.vehicleType, "Vehicle type is required");
        this.pickupLatitude = Objects.requireNonNull(builder.pickupLatitude, "Pickup latitude is required");
        this.pickupLongitude = Objects.requireNonNull(builder.pickupLongitude, "Pickup longitude is required");
        this.pickupAddress = Objects.requireNonNull(builder.pickupAddress, "Pickup address is required");
        this.dropoffLatitude = Objects.requireNonNull(builder.dropoffLatitude, "Dropoff latitude is required");
        this.dropoffLongitude = Objects.requireNonNull(builder.dropoffLongitude, "Dropoff longitude is required");
        this.dropoffAddress = Objects.requireNonNull(builder.dropoffAddress, "Dropoff address is required");
        this.estimatedDistanceKm = Objects.requireNonNull(builder.estimatedDistanceKm, "Estimated distance is required");
        this.estimatedDurationMinutes = Objects.requireNonNull(builder.estimatedDurationMinutes, "Estimated duration is required");
        this.estimatedFare = Objects.requireNonNull(builder.estimatedFare, "Estimated fare is required");
        this.surgeMultiplier = builder.surgeMultiplier != null ? builder.surgeMultiplier : BigDecimal.valueOf(1.00);
        this.currency = builder.currency != null ? builder.currency : "VND";
        this.pricingRuleVersion = Objects.requireNonNull(builder.pricingRuleVersion, "Pricing rule version is required");

        validateCoordinates(this.pickupLatitude, this.pickupLongitude, "Pickup");
        validateCoordinates(this.dropoffLatitude, this.dropoffLongitude, "Dropoff");

        this.status = TripStatus.CREATED;
        this.requestedAt = builder.requestedAt != null ? builder.requestedAt : Instant.now();
        this.createdAt = this.requestedAt;
        this.updatedAt = this.requestedAt;

        // Record initial creation history
        this.statusHistory.add(TripStatusHistory.of(
                this.id,
                null,
                TripStatus.CREATED,
                this.customerId,
                "CUSTOMER",
                "Trip created",
                this.requestedAt
        ));
    }

    public static Builder builder() {
        return new Builder();
    }

    // =========================================================================
    // Domain Lifecycle Transitions (Strict State Machine per SRS Section 7.1)
    // =========================================================================

    /**
     * Transition CREATED -> MATCHING.
     * Kích hoạt tìm kiếm tài xế khi trip và quote hợp lệ (FR-TRIP-006).
     */
    public void startMatching(String actorRole) {
        TripStateMachine.validateTransition(this.status, TripStatus.MATCHING, actorRole);
        TripStatus oldStatus = this.status;
        this.status = TripStatus.MATCHING;
        Instant now = Instant.now();
        this.updatedAt = now;

        this.statusHistory.add(TripStatusHistory.of(
                this.id, oldStatus, TripStatus.MATCHING,
                this.customerId, actorRole, "Matching process started", now
        ));
    }

    /**
     * Transition MATCHING -> ACCEPTED.
     * Tài xế chấp nhận đề nghị phân chuyến nguyên tử (FR-MAT-010, FR-MAT-014).
     */
    public void accept(UUID driverId, UUID vehicleId, String actorRole, Instant acceptedAt) {
        TripStateMachine.validateTransition(this.status, TripStatus.ACCEPTED, actorRole);
        if (driverId == null) {
            throw new IllegalArgumentException("Driver ID cannot be null when accepting a trip");
        }
        if (this.driverId != null && !this.driverId.equals(driverId)) {
            throw new IllegalStateException("Trip is already assigned to driver " + this.driverId);
        }

        TripStatus oldStatus = this.status;
        this.status = TripStatus.ACCEPTED;
        this.driverId = driverId;
        this.vehicleId = vehicleId;
        Instant now = acceptedAt != null ? acceptedAt : Instant.now();
        this.acceptedAt = now;
        this.updatedAt = now;

        this.statusHistory.add(TripStatusHistory.of(
                this.id, oldStatus, TripStatus.ACCEPTED,
                driverId, actorRole, "Driver accepted dispatch offer", now
        ));
    }

    /**
     * Transition ACCEPTED -> PICKING_UP.
     * Tài xế bắt đầu di chuyển tới điểm đón (FR-TRIP-011).
     */
    public void startPickingUp(UUID driverId, String actorRole, Instant pickingUpAt) {
        assertAssignedDriver(driverId);
        TripStateMachine.validateTransition(this.status, TripStatus.PICKING_UP, actorRole);

        TripStatus oldStatus = this.status;
        this.status = TripStatus.PICKING_UP;
        Instant now = pickingUpAt != null ? pickingUpAt : Instant.now();
        this.updatedAt = now;

        this.statusHistory.add(TripStatusHistory.of(
                this.id, oldStatus, TripStatus.PICKING_UP,
                driverId, actorRole, "Driver is heading to pickup location", now
        ));
    }

    /**
     * Transition PICKING_UP -> ARRIVED.
     * Tài xế đã tới điểm đón và xác nhận đến (FR-TRIP-011).
     */
    public void confirmArrived(UUID driverId, String actorRole, Instant arrivedAt) {
        assertAssignedDriver(driverId);
        TripStateMachine.validateTransition(this.status, TripStatus.ARRIVED, actorRole);

        TripStatus oldStatus = this.status;
        this.status = TripStatus.ARRIVED;
        Instant now = arrivedAt != null ? arrivedAt : Instant.now();
        this.arrivedAt = now;
        this.updatedAt = now;

        this.statusHistory.add(TripStatusHistory.of(
                this.id, oldStatus, TripStatus.ARRIVED,
                driverId, actorRole, "Driver arrived at pickup location", now
        ));
    }

    /**
     * Transition ARRIVED -> IN_TRIP.
     * Khách hàng / hàng hóa đã lên xe, chuyến xe bắt đầu di chuyển tới điểm đến (FR-TRIP-011, FR-TRIP-012).
     */
    public void startTrip(UUID driverId, String actorRole, Instant startedAt) {
        assertAssignedDriver(driverId);
        TripStateMachine.validateTransition(this.status, TripStatus.IN_TRIP, actorRole);

        TripStatus oldStatus = this.status;
        this.status = TripStatus.IN_TRIP;
        Instant now = startedAt != null ? startedAt : Instant.now();
        this.startedAt = now;
        this.updatedAt = now;

        this.statusHistory.add(TripStatusHistory.of(
                this.id, oldStatus, TripStatus.IN_TRIP,
                driverId, actorRole, "Trip started towards destination", now
        ));
    }

    /**
     * Transition IN_TRIP -> COMPLETED.
     * Đã tới điểm đến và hoàn tất chuyến đi (FR-TRIP-011, FR-TRIP-014).
     */
    public void complete(UUID driverId, String actorRole, Instant completedAt) {
        assertAssignedDriver(driverId);
        TripStateMachine.validateTransition(this.status, TripStatus.COMPLETED, actorRole);

        TripStatus oldStatus = this.status;
        this.status = TripStatus.COMPLETED;
        Instant now = completedAt != null ? completedAt : Instant.now();
        this.completedAt = now;
        this.updatedAt = now;

        this.statusHistory.add(TripStatusHistory.of(
                this.id, oldStatus, TripStatus.COMPLETED,
                driverId, actorRole, "Trip successfully completed at destination", now
        ));
    }

    /**
     * Transition Any Active State -> CANCELLED.
     * Hủy chuyến theo quy tắc actor, trạng thái và phí hủy (FR-CAN-001..007, SRS 7.1).
     */
    public void cancel(UUID actorId, String actorRole, String reason, BigDecimal fee, Instant cancelledAt) {
        if (actorId == null) {
            throw new IllegalArgumentException("Actor ID cannot be null when cancelling a trip");
        }
        TripStateMachine.validateTransition(this.status, TripStatus.CANCELLED, actorRole);

        // Invariant check for driver actor
        if ("DRIVER".equalsIgnoreCase(actorRole) && this.driverId != null && !this.driverId.equals(actorId)) {
            throw new TripAccessDeniedException("Driver " + actorId + " is not assigned to trip " + this.id);
        }

        // Invariant check for customer actor
        if ("CUSTOMER".equalsIgnoreCase(actorRole) && !this.customerId.equals(actorId)) {
            throw new TripAccessDeniedException("Customer " + actorId + " does not own trip " + this.id);
        }

        TripStatus oldStatus = this.status;
        this.status = TripStatus.CANCELLED;
        Instant now = cancelledAt != null ? cancelledAt : Instant.now();
        this.cancelledAt = now;
        this.cancelledBy = actorId;
        this.cancellationActorRole = actorRole;
        this.cancellationReason = reason;
        this.cancellationFee = fee;
        this.updatedAt = now;

        this.statusHistory.add(TripStatusHistory.of(
                this.id, oldStatus, TripStatus.CANCELLED,
                actorId, actorRole, reason != null ? reason : "Trip cancelled", now
        ));
    }

    /**
     * Transition MATCHING -> NO_DRIVER.
     * Không tìm được tài xế trong giới hạn matching (SRS 7.1, FR-MAT-015).
     */
    public void markNoDriver(String actorRole, Instant noDriverAt) {
        TripStateMachine.validateTransition(this.status, TripStatus.NO_DRIVER, actorRole);

        TripStatus oldStatus = this.status;
        this.status = TripStatus.NO_DRIVER;
        Instant now = noDriverAt != null ? noDriverAt : Instant.now();
        this.updatedAt = now;

        this.statusHistory.add(TripStatusHistory.of(
                this.id, oldStatus, TripStatus.NO_DRIVER,
                this.customerId, actorRole, "Matching limits reached with no driver available", now
        ));
    }

    private void assertAssignedDriver(UUID actingDriverId) {
        if (actingDriverId == null) {
            throw new TripAccessDeniedException("Driver ID cannot be null");
        }
        if (this.driverId == null) {
            throw new IllegalStateException("Trip has not been assigned to any driver yet");
        }
        if (!this.driverId.equals(actingDriverId)) {
            throw new TripAccessDeniedException("Driver " + actingDriverId + " is not assigned to trip " + this.id);
        }
    }

    private static void validateCoordinates(BigDecimal lat, BigDecimal lng, String label) {
        if (lat.compareTo(BigDecimal.valueOf(-90.0)) < 0 || lat.compareTo(BigDecimal.valueOf(90.0)) > 0) {
            throw new IllegalArgumentException(label + " latitude must be between -90 and 90, got: " + lat);
        }
        if (lng.compareTo(BigDecimal.valueOf(-180.0)) < 0 || lng.compareTo(BigDecimal.valueOf(180.0)) > 0) {
            throw new IllegalArgumentException(label + " longitude must be between -180 and 180, got: " + lng);
        }
    }

    // =========================================================================
    // Getters
    // =========================================================================

    public UUID getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public UUID getDriverId() {
        return driverId;
    }

    public UUID getQuoteId() {
        return quoteId;
    }

    public ServiceType getServiceType() {
        return serviceType;
    }

    public String getVehicleType() {
        return vehicleType;
    }

    public UUID getVehicleId() {
        return vehicleId;
    }

    public BigDecimal getPickupLatitude() {
        return pickupLatitude;
    }

    public BigDecimal getPickupLongitude() {
        return pickupLongitude;
    }

    public String getPickupAddress() {
        return pickupAddress;
    }

    public BigDecimal getDropoffLatitude() {
        return dropoffLatitude;
    }

    public BigDecimal getDropoffLongitude() {
        return dropoffLongitude;
    }

    public String getDropoffAddress() {
        return dropoffAddress;
    }

    public BigDecimal getEstimatedDistanceKm() {
        return estimatedDistanceKm;
    }

    public Integer getEstimatedDurationMinutes() {
        return estimatedDurationMinutes;
    }

    public BigDecimal getEstimatedFare() {
        return estimatedFare;
    }

    public BigDecimal getSurgeMultiplier() {
        return surgeMultiplier;
    }

    public String getCurrency() {
        return currency;
    }

    public String getPricingRuleVersion() {
        return pricingRuleVersion;
    }

    public TripStatus getStatus() {
        return status;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public Instant getArrivedAt() {
        return arrivedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public String getCancellationReason() {
        return cancellationReason;
    }

    public UUID getCancelledBy() {
        return cancelledBy;
    }

    public String getCancellationActorRole() {
        return cancellationActorRole;
    }

    public BigDecimal getCancellationFee() {
        return cancellationFee;
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

    public List<TripStatusHistory> getStatusHistory() {
        return Collections.unmodifiableList(statusHistory);
    }

    // =========================================================================
    // Builder
    // =========================================================================

    public static class Builder {
        private UUID id;
        private String idempotencyKey;
        private UUID customerId;
        private UUID quoteId;
        private ServiceType serviceType = ServiceType.RIDE;
        private String vehicleType;
        private BigDecimal pickupLatitude;
        private BigDecimal pickupLongitude;
        private String pickupAddress;
        private BigDecimal dropoffLatitude;
        private BigDecimal dropoffLongitude;
        private String dropoffAddress;
        private BigDecimal estimatedDistanceKm;
        private Integer estimatedDurationMinutes;
        private BigDecimal estimatedFare;
        private BigDecimal surgeMultiplier = BigDecimal.valueOf(1.00);
        private String currency = "VND";
        private String pricingRuleVersion;
        private Instant requestedAt;

        public Builder id(UUID id) {
            this.id = id;
            return this;
        }

        public Builder idempotencyKey(String idempotencyKey) {
            this.idempotencyKey = idempotencyKey;
            return this;
        }

        public Builder customerId(UUID customerId) {
            this.customerId = customerId;
            return this;
        }

        public Builder quoteId(UUID quoteId) {
            this.quoteId = quoteId;
            return this;
        }

        public Builder serviceType(ServiceType serviceType) {
            this.serviceType = serviceType;
            return this;
        }

        public Builder vehicleType(String vehicleType) {
            this.vehicleType = vehicleType;
            return this;
        }

        public Builder pickupLocation(BigDecimal lat, BigDecimal lng, String address) {
            this.pickupLatitude = lat;
            this.pickupLongitude = lng;
            this.pickupAddress = address;
            return this;
        }

        public Builder dropoffLocation(BigDecimal lat, BigDecimal lng, String address) {
            this.dropoffLatitude = lat;
            this.dropoffLongitude = lng;
            this.dropoffAddress = address;
            return this;
        }

        public Builder estimatedDistanceKm(BigDecimal estimatedDistanceKm) {
            this.estimatedDistanceKm = estimatedDistanceKm;
            return this;
        }

        public Builder estimatedDurationMinutes(Integer estimatedDurationMinutes) {
            this.estimatedDurationMinutes = estimatedDurationMinutes;
            return this;
        }

        public Builder estimatedFare(BigDecimal estimatedFare) {
            this.estimatedFare = estimatedFare;
            return this;
        }

        public Builder surgeMultiplier(BigDecimal surgeMultiplier) {
            this.surgeMultiplier = surgeMultiplier;
            return this;
        }

        public Builder currency(String currency) {
            this.currency = currency;
            return this;
        }

        public Builder pricingRuleVersion(String pricingRuleVersion) {
            this.pricingRuleVersion = pricingRuleVersion;
            return this;
        }

        public Builder requestedAt(Instant requestedAt) {
            this.requestedAt = requestedAt;
            return this;
        }

        public Trip build() {
            if (this.id == null) {
                this.id = UUID.randomUUID();
            }
            return new Trip(this);
        }
    }
}
