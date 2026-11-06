package com.rhl.pricing.repositories;

import com.rhl.pricing.entities.PricingRule;
import com.rhl.pricing.models.PricingRuleStatus;
import com.rhl.pricing.models.ServiceType;
import com.rhl.pricing.models.VehicleType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PricingRuleRepository extends JpaRepository<PricingRule, UUID> {

    @Query("""
        SELECT r FROM PricingRule r
        WHERE r.serviceType = :serviceType
          AND r.vehicleType = :vehicleType
          AND r.regionId = :regionId
          AND r.status = :status
          AND r.effectiveFrom <= :effectiveTime
          AND (r.effectiveTo IS NULL OR r.effectiveTo > :effectiveTime)
        ORDER BY r.version DESC
    """)
    List<PricingRule> findEffectiveRules(
            @Param("serviceType") ServiceType serviceType,
            @Param("vehicleType") VehicleType vehicleType,
            @Param("regionId") String regionId,
            @Param("status") PricingRuleStatus status,
            @Param("effectiveTime") Instant effectiveTime
    );

    @Query("""
        SELECT r FROM PricingRule r
        WHERE r.serviceType = :serviceType
          AND r.vehicleType = :vehicleType
          AND r.regionId = :regionId
          AND r.status = 'PUBLISHED'
          AND (:excludeId IS NULL OR r.id != :excludeId)
          AND (:effectiveTo IS NULL OR r.effectiveFrom < :effectiveTo)
          AND (r.effectiveTo IS NULL OR r.effectiveTo > :effectiveFrom)
    """)
    List<PricingRule> findConflictingRules(
            @Param("serviceType") ServiceType serviceType,
            @Param("vehicleType") VehicleType vehicleType,
            @Param("regionId") String regionId,
            @Param("effectiveFrom") Instant effectiveFrom,
            @Param("effectiveTo") Instant effectiveTo,
            @Param("excludeId") UUID excludeId
    );

    @Query("""
        SELECT COALESCE(MAX(r.version), 0) FROM PricingRule r
        WHERE r.serviceType = :serviceType
          AND r.vehicleType = :vehicleType
          AND r.regionId = :regionId
    """)
    int findMaxVersion(
            @Param("serviceType") ServiceType serviceType,
            @Param("vehicleType") VehicleType vehicleType,
            @Param("regionId") String regionId
    );

    List<PricingRule> findByServiceTypeAndVehicleTypeAndRegionIdOrderByVersionDesc(
            ServiceType serviceType, VehicleType vehicleType, String regionId
    );

    Optional<PricingRule> findByServiceTypeAndVehicleTypeAndRegionIdAndVersion(
            ServiceType serviceType, VehicleType vehicleType, String regionId, int version
    );
}
