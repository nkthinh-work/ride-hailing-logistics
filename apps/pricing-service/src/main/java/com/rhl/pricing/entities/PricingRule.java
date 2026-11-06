package com.rhl.pricing.entities;

import com.rhl.pricing.models.PricingPolicy;
import com.rhl.pricing.models.PricingRuleStatus;
import com.rhl.pricing.models.ServiceType;
import com.rhl.pricing.models.VehicleType;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "pricing_rules")
public class PricingRule {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "service_type", nullable = false, length = 30)
    private ServiceType serviceType;

    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_type", nullable = false, length = 30)
    private VehicleType vehicleType;

    @Column(name = "region_id", nullable = false, length = 50)
    private String regionId;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "base_fare", nullable = false, precision = 14, scale = 2)
    private BigDecimal baseFare;

    @Column(name = "included_distance_km", nullable = false, precision = 8, scale = 2)
    private BigDecimal includedDistanceKm;

    @Column(name = "price_per_km", nullable = false, precision = 14, scale = 2)
    private BigDecimal pricePerKm;

    @Column(name = "price_per_minute", nullable = false, precision = 14, scale = 2)
    private BigDecimal pricePerMinute;

    @Column(name = "minimum_fare", nullable = false, precision = 14, scale = 2)
    private BigDecimal minimumFare;

    @Column(name = "currency", nullable = false, length = 10)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PricingRuleStatus status;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Column(name = "effective_to")
    private Instant effectiveTo;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    protected PricingRule() {
    }

    public PricingRule(
            UUID id,
            ServiceType serviceType,
            VehicleType vehicleType,
            String regionId,
            int version,
            BigDecimal baseFare,
            BigDecimal includedDistanceKm,
            BigDecimal pricePerKm,
            BigDecimal pricePerMinute,
            BigDecimal minimumFare,
            String currency,
            PricingRuleStatus status,
            Instant effectiveFrom,
            Instant effectiveTo,
            Instant createdAt,
            UUID createdBy) {
        this.id = id != null ? id : UUID.randomUUID();
        this.serviceType = Objects.requireNonNull(serviceType, "serviceType must not be null");
        this.vehicleType = Objects.requireNonNull(vehicleType, "vehicleType must not be null");
        this.regionId = (regionId != null && !regionId.isBlank()) ? regionId : "DEFAULT";
        this.version = version;
        this.baseFare = Objects.requireNonNull(baseFare, "baseFare must not be null");
        this.includedDistanceKm = includedDistanceKm != null ? includedDistanceKm : BigDecimal.valueOf(2.0);
        this.pricePerKm = Objects.requireNonNull(pricePerKm, "pricePerKm must not be null");
        this.pricePerMinute = Objects.requireNonNull(pricePerMinute, "pricePerMinute must not be null");
        this.minimumFare = Objects.requireNonNull(minimumFare, "minimumFare must not be null");
        this.currency = (currency != null && !currency.isBlank()) ? currency : "VND";
        this.status = status != null ? status : PricingRuleStatus.PUBLISHED;
        this.effectiveFrom = Objects.requireNonNull(effectiveFrom, "effectiveFrom must not be null");
        this.effectiveTo = effectiveTo;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy must not be null");
    }

    public PricingPolicy toPolicy() {
        return new PricingPolicy(
                this.id.toString(),
                "v" + this.version,
                this.serviceType,
                this.vehicleType,
                this.baseFare,
                this.includedDistanceKm,
                this.pricePerKm,
                this.pricePerMinute,
                this.minimumFare,
                this.currency
        );
    }

    public UUID getId() {
        return id;
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

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public BigDecimal getBaseFare() {
        return baseFare;
    }

    public BigDecimal getIncludedDistanceKm() {
        return includedDistanceKm;
    }

    public BigDecimal getPricePerKm() {
        return pricePerKm;
    }

    public BigDecimal getPricePerMinute() {
        return pricePerMinute;
    }

    public BigDecimal getMinimumFare() {
        return minimumFare;
    }

    public String getCurrency() {
        return currency;
    }

    public PricingRuleStatus getStatus() {
        return status;
    }

    public void setStatus(PricingRuleStatus status) {
        this.status = status;
    }

    public Instant getEffectiveFrom() {
        return effectiveFrom;
    }

    public Instant getEffectiveTo() {
        return effectiveTo;
    }

    public void setEffectiveTo(Instant effectiveTo) {
        this.effectiveTo = effectiveTo;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }
}
