package com.rhl.pricing.entities;

import com.rhl.pricing.models.FareBreakdown;
import com.rhl.pricing.models.ServiceType;
import com.rhl.pricing.models.VehicleType;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable Fare Quote Entity representing an issued quote snapshot (FR-PRI-013..018).
 * Once created, this snapshot serves as the single source of truth for the fare,
 * ensuring tamper protection and independence from subsequent pricing rule changes.
 */
@Entity
@Table(name = "fare_quotes")
public class FareQuote {

    @Id
    private UUID id;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "service_type", nullable = false, length = 30)
    private ServiceType serviceType;

    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_type", nullable = false, length = 30)
    private VehicleType vehicleType;

    @Column(name = "region_id", nullable = false, length = 50)
    private String regionId;

    @Column(name = "pickup_latitude", nullable = false, precision = 10, scale = 7)
    private BigDecimal pickupLatitude;

    @Column(name = "pickup_longitude", nullable = false, precision = 10, scale = 7)
    private BigDecimal pickupLongitude;

    @Column(name = "pickup_address", length = 255)
    private String pickupAddress;

    @Column(name = "dropoff_latitude", nullable = false, precision = 10, scale = 7)
    private BigDecimal dropoffLatitude;

    @Column(name = "dropoff_longitude", nullable = false, precision = 10, scale = 7)
    private BigDecimal dropoffLongitude;

    @Column(name = "dropoff_address", length = 255)
    private String dropoffAddress;

    @Column(name = "distance_meters", nullable = false)
    private long distanceMeters;

    @Column(name = "duration_seconds", nullable = false)
    private long durationSeconds;

    @Column(name = "route_provider", nullable = false, length = 50)
    private String routeProvider;

    @Column(name = "is_simulated", nullable = false)
    private boolean isSimulated;

    @Column(name = "pricing_rule_id", nullable = false, length = 50)
    private String pricingRuleId;

    @Column(name = "pricing_rule_version", nullable = false, length = 30)
    private String pricingRuleVersion;

    // Fare Breakdown components (Immutable Snapshot)
    @Column(name = "base_fare", nullable = false, precision = 14, scale = 2)
    private BigDecimal baseFare;

    @Column(name = "included_distance_km", nullable = false, precision = 8, scale = 2)
    private BigDecimal includedDistanceKm;

    @Column(name = "chargeable_distance_km", nullable = false, precision = 8, scale = 2)
    private BigDecimal chargeableDistanceKm;

    @Column(name = "distance_fare", nullable = false, precision = 14, scale = 2)
    private BigDecimal distanceFare;

    @Column(name = "duration_minutes", nullable = false, precision = 8, scale = 2)
    private BigDecimal durationMinutes;

    @Column(name = "time_fare", nullable = false, precision = 14, scale = 2)
    private BigDecimal timeFare;

    @Column(name = "standard_fare", nullable = false, precision = 14, scale = 2)
    private BigDecimal standardFare;

    @Column(name = "minimum_fare", nullable = false, precision = 14, scale = 2)
    private BigDecimal minimumFare;

    @Column(name = "pre_surge_fare", nullable = false, precision = 14, scale = 2)
    private BigDecimal preSurgeFare;

    @Column(name = "surge_multiplier", nullable = false, precision = 6, scale = 4)
    private BigDecimal surgeMultiplier;

    @Column(name = "surged_fare", nullable = false, precision = 14, scale = 2)
    private BigDecimal surgedFare;

    @Column(name = "additional_fees", nullable = false, precision = 14, scale = 2)
    private BigDecimal additionalFees;

    @Column(name = "tax", nullable = false, precision = 14, scale = 2)
    private BigDecimal tax;

    @Column(name = "discount", nullable = false, precision = 14, scale = 2)
    private BigDecimal discount;

    @Column(name = "total_fare", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalFare;

    @Column(name = "currency", nullable = false, length = 10)
    private String currency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected FareQuote() {
    }

    public FareQuote(
            UUID id,
            UUID customerId,
            ServiceType serviceType,
            VehicleType vehicleType,
            String regionId,
            BigDecimal pickupLatitude,
            BigDecimal pickupLongitude,
            String pickupAddress,
            BigDecimal dropoffLatitude,
            BigDecimal dropoffLongitude,
            String dropoffAddress,
            long distanceMeters,
            long durationSeconds,
            String routeProvider,
            boolean isSimulated,
            String pricingRuleId,
            String pricingRuleVersion,
            FareBreakdown breakdown,
            Instant createdAt,
            Instant expiresAt) {

        this.id = id != null ? id : UUID.randomUUID();
        this.customerId = Objects.requireNonNull(customerId, "customerId must not be null");
        this.serviceType = Objects.requireNonNull(serviceType, "serviceType must not be null");
        this.vehicleType = Objects.requireNonNull(vehicleType, "vehicleType must not be null");
        this.regionId = (regionId != null && !regionId.isBlank()) ? regionId : "DEFAULT";
        this.pickupLatitude = Objects.requireNonNull(pickupLatitude, "pickupLatitude must not be null");
        this.pickupLongitude = Objects.requireNonNull(pickupLongitude, "pickupLongitude must not be null");
        this.pickupAddress = pickupAddress;
        this.dropoffLatitude = Objects.requireNonNull(dropoffLatitude, "dropoffLatitude must not be null");
        this.dropoffLongitude = Objects.requireNonNull(dropoffLongitude, "dropoffLongitude must not be null");
        this.dropoffAddress = dropoffAddress;
        this.distanceMeters = distanceMeters;
        this.durationSeconds = durationSeconds;
        this.routeProvider = Objects.requireNonNull(routeProvider, "routeProvider must not be null");
        this.isSimulated = isSimulated;
        this.pricingRuleId = Objects.requireNonNull(pricingRuleId, "pricingRuleId must not be null");
        this.pricingRuleVersion = Objects.requireNonNull(pricingRuleVersion, "pricingRuleVersion must not be null");

        Objects.requireNonNull(breakdown, "breakdown must not be null");
        this.baseFare = breakdown.baseFare();
        this.includedDistanceKm = breakdown.includedDistanceKm();
        this.chargeableDistanceKm = breakdown.chargeableDistanceKm();
        this.distanceFare = breakdown.distanceFare();
        this.durationMinutes = breakdown.durationMinutes();
        this.timeFare = breakdown.timeFare();
        this.standardFare = breakdown.standardFare();
        this.minimumFare = breakdown.minimumFare();
        this.preSurgeFare = breakdown.preSurgeFare();
        this.surgeMultiplier = breakdown.surgeMultiplier();
        this.surgedFare = breakdown.surgedFare();
        this.additionalFees = breakdown.additionalFees();
        this.tax = breakdown.tax();
        this.discount = breakdown.discount();
        this.totalFare = breakdown.totalFare();
        this.currency = breakdown.currency();

        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");

        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
    }

    public FareBreakdown toBreakdown() {
        return new FareBreakdown(
                baseFare,
                includedDistanceKm,
                chargeableDistanceKm,
                distanceFare,
                durationMinutes,
                timeFare,
                standardFare,
                minimumFare,
                preSurgeFare,
                surgeMultiplier,
                surgedFare,
                additionalFees,
                tax,
                discount,
                totalFare,
                currency,
                pricingRuleVersion
        );
    }

    public boolean isExpired(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return !now.isBefore(this.expiresAt);
    }

    public boolean isOwnedBy(UUID customerId) {
        return this.customerId != null && this.customerId.equals(customerId);
    }

    public boolean isSurgeApplied() {
        return this.surgeMultiplier != null && this.surgeMultiplier.compareTo(BigDecimal.ONE) > 0;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public ServiceType getServiceType() {
        return serviceType;
    }

    public VehicleType getVehicleType() {
        return vehicleType;
    }

    public String getRegionId() {
        return regionId;
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

    public long getDistanceMeters() {
        return distanceMeters;
    }

    public long getDurationSeconds() {
        return durationSeconds;
    }

    public String getRouteProvider() {
        return routeProvider;
    }

    public boolean isSimulated() {
        return isSimulated;
    }

    public String getPricingRuleId() {
        return pricingRuleId;
    }

    public String getPricingRuleVersion() {
        return pricingRuleVersion;
    }

    public BigDecimal getBaseFare() {
        return baseFare;
    }

    public BigDecimal getIncludedDistanceKm() {
        return includedDistanceKm;
    }

    public BigDecimal getChargeableDistanceKm() {
        return chargeableDistanceKm;
    }

    public BigDecimal getDistanceFare() {
        return distanceFare;
    }

    public BigDecimal getDurationMinutes() {
        return durationMinutes;
    }

    public BigDecimal getTimeFare() {
        return timeFare;
    }

    public BigDecimal getStandardFare() {
        return standardFare;
    }

    public BigDecimal getMinimumFare() {
        return minimumFare;
    }

    public BigDecimal getPreSurgeFare() {
        return preSurgeFare;
    }

    public BigDecimal getSurgeMultiplier() {
        return surgeMultiplier;
    }

    public BigDecimal getSurgedFare() {
        return surgedFare;
    }

    public BigDecimal getAdditionalFees() {
        return additionalFees;
    }

    public BigDecimal getTax() {
        return tax;
    }

    public BigDecimal getDiscount() {
        return discount;
    }

    public BigDecimal getTotalFare() {
        return totalFare;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
