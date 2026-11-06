package com.rhl.pricing.services;

import com.rhl.pricing.dtos.CreatePricingRuleRequest;
import com.rhl.pricing.entities.PricingRule;
import com.rhl.pricing.entities.PricingRuleAudit;
import com.rhl.pricing.exceptions.InvalidPricingRuleException;
import com.rhl.pricing.exceptions.PricingRuleConflictException;
import com.rhl.pricing.exceptions.PricingRuleImmutableException;
import com.rhl.pricing.exceptions.PricingRuleNotFoundException;
import com.rhl.pricing.models.PricingAuditAction;
import com.rhl.pricing.models.PricingRuleStatus;
import com.rhl.pricing.models.ServiceType;
import com.rhl.pricing.models.VehicleType;
import com.rhl.pricing.repositories.PricingRuleAuditRepository;
import com.rhl.pricing.repositories.PricingRuleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class PricingRuleService {

    private final PricingRuleRepository pricingRuleRepository;
    private final PricingRuleAuditRepository auditRepository;
    private final PricingScopeLockManager scopeLockManager;

    public PricingRuleService(
            PricingRuleRepository pricingRuleRepository,
            PricingRuleAuditRepository auditRepository,
            PricingScopeLockManager scopeLockManager) {
        this.pricingRuleRepository = pricingRuleRepository;
        this.auditRepository = auditRepository;
        this.scopeLockManager = scopeLockManager;
    }

    /**
     * Atomically creates and publishes a new Pricing Rule with version increment,
     * interval conflict checking and audit log recording (FR-PRI-001, FR-PRI-004, FR-PRI-012).
     */
    @Transactional
    public PricingRule createAndPublishRule(CreatePricingRuleRequest request, UUID actorId) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");

        // Validate effective time bounds
        if (request.effectiveTo() != null && !request.effectiveTo().isAfter(request.effectiveFrom())) {
            throw new InvalidPricingRuleException("effectiveTo (" + request.effectiveTo() +
                    ") must be strictly after effectiveFrom (" + request.effectiveFrom() + ")");
        }

        String regionId = (request.regionId() != null && !request.regionId().isBlank())
                ? request.regionId().trim().toUpperCase()
                : "DEFAULT";

        String currency = (request.currency() != null && !request.currency().isBlank())
                ? request.currency().trim().toUpperCase()
                : "VND";

        BigDecimal includedDistance = request.includedDistanceKm() != null
                ? request.includedDistanceKm()
                : BigDecimal.valueOf(2.0);

        // 1. Acquire transaction advisory lock on business key scope
        String scopeKey = request.serviceType().name() + ":" + request.vehicleType().name() + ":" + regionId;
        scopeLockManager.acquireScopeLock(scopeKey);

        // 2. Conflict Detection (FR-PRI-004)
        List<PricingRule> conflicts = pricingRuleRepository.findConflictingRules(
                request.serviceType(),
                request.vehicleType(),
                regionId,
                request.effectiveFrom(),
                request.effectiveTo(),
                null
        );

        if (!conflicts.isEmpty()) {
            PricingRule conflicting = conflicts.getFirst();
            throw new PricingRuleConflictException(String.format(
                    "Pricing rule conflict detected with existing rule v%d [%s to %s] for scope [%s]",
                    conflicting.getVersion(),
                    conflicting.getEffectiveFrom(),
                    conflicting.getEffectiveTo() != null ? conflicting.getEffectiveTo() : "infinity",
                    scopeKey
            ));
        }

        // 3. Increment Version monotonically
        int nextVersion = pricingRuleRepository.findMaxVersion(request.serviceType(), request.vehicleType(), regionId) + 1;

        // 4. Persist Pricing Rule
        PricingRule newRule = new PricingRule(
                UUID.randomUUID(),
                request.serviceType(),
                request.vehicleType(),
                regionId,
                nextVersion,
                request.baseFare(),
                includedDistance,
                request.pricePerKm(),
                request.pricePerMinute(),
                request.minimumFare(),
                currency,
                PricingRuleStatus.PUBLISHED,
                request.effectiveFrom(),
                request.effectiveTo(),
                Instant.now(),
                actorId
        );

        PricingRule savedRule = pricingRuleRepository.save(newRule);

        // 5. Append Audit Log atomically (FR-PRI-012)
        String snapshot = buildSnapshot(savedRule);
        PricingRuleAudit audit = new PricingRuleAudit(
                UUID.randomUUID(),
                savedRule.getId(),
                savedRule.getVersion(),
                PricingAuditAction.RULE_PUBLISHED,
                actorId,
                request.reason() != null ? request.reason() : "Initial rule publication",
                snapshot,
                Instant.now()
        );
        auditRepository.save(audit);

        return savedRule;
    }

    @Transactional
    public PricingRule publishDraftRule(UUID draftRuleId, String reason, UUID actorId) {
        PricingRule rule = pricingRuleRepository.findById(draftRuleId)
                .orElseThrow(() -> new PricingRuleNotFoundException("Pricing rule not found: " + draftRuleId));

        if (rule.getStatus() == PricingRuleStatus.PUBLISHED) {
            return rule;
        }
        if (rule.getStatus() == PricingRuleStatus.ARCHIVED) {
            throw new PricingRuleImmutableException("Cannot publish an archived pricing rule: " + draftRuleId);
        }

        String scopeKey = rule.getServiceType() + ":" + rule.getVehicleType() + ":" + rule.getRegionId();
        scopeLockManager.acquireScopeLock(scopeKey);

        // Validate conflicts at exact publication time (FR-PRI-004)
        List<PricingRule> conflicting = pricingRuleRepository.findConflictingRules(
                rule.getServiceType(),
                rule.getVehicleType(),
                rule.getRegionId(),
                rule.getEffectiveFrom(),
                rule.getEffectiveTo(),
                rule.getId()
        );
        if (!conflicting.isEmpty()) {
            PricingRule conflict = conflicting.getFirst();
            throw new PricingRuleConflictException(String.format(
                    "Pricing rule conflict detected with existing rule v%d (%s to %s) for [%s:%s:%s]",
                    conflict.getVersion(),
                    conflict.getEffectiveFrom(),
                    conflict.getEffectiveTo() != null ? conflict.getEffectiveTo() : "infinity",
                    rule.getServiceType(),
                    rule.getVehicleType(),
                    rule.getRegionId()
            ));
        }

        // Monotonic version assignment
        int nextVersion = pricingRuleRepository.findMaxVersion(
                rule.getServiceType(), rule.getVehicleType(), rule.getRegionId()
        ) + 1;
        rule.setVersion(nextVersion);
        rule.setStatus(PricingRuleStatus.PUBLISHED);

        PricingRule published = pricingRuleRepository.save(rule);

        // Append audit log atomically (FR-PRI-012)
        PricingRuleAudit audit = new PricingRuleAudit(
                UUID.randomUUID(),
                published.getId(),
                published.getVersion(),
                PricingAuditAction.RULE_PUBLISHED,
                actorId,
                reason != null ? reason : "Draft rule published",
                buildSnapshot(published),
                Instant.now()
        );
        auditRepository.save(audit);

        return published;
    }

    @Transactional
    public PricingRule archiveRule(UUID ruleId, String reason, UUID actorId) {
        PricingRule rule = pricingRuleRepository.findById(ruleId)
                .orElseThrow(() -> new PricingRuleNotFoundException("Pricing rule not found: " + ruleId));

        if (rule.getStatus() == PricingRuleStatus.ARCHIVED) {
            return rule;
        }

        String scopeKey = rule.getServiceType() + ":" + rule.getVehicleType() + ":" + rule.getRegionId();
        scopeLockManager.acquireScopeLock(scopeKey);

        Instant now = Instant.now();
        rule.setStatus(PricingRuleStatus.ARCHIVED);

        // Only truncate effectiveTo if rule has already started (effectiveFrom <= now).
        // If effectiveFrom is in the future, setting effectiveTo = now would violate the
        // check constraint ck_pricing_rules_dates (effective_to > effective_from).
        // Since status is ARCHIVED, findEffectiveRules will never pick this rule regardless.
        if (!rule.getEffectiveFrom().isAfter(now)) {
            if (rule.getEffectiveTo() == null || rule.getEffectiveTo().isAfter(now)) {
                rule.setEffectiveTo(now);
            }
        }

        PricingRule updated = pricingRuleRepository.save(rule);

        PricingRuleAudit audit = new PricingRuleAudit(
                UUID.randomUUID(),
                updated.getId(),
                updated.getVersion(),
                PricingAuditAction.RULE_ARCHIVED,
                actorId,
                reason != null ? reason : "Archived by administrator",
                buildSnapshot(updated),
                Instant.now()
        );
        auditRepository.save(audit);

        return updated;
    }

    @Transactional(readOnly = true)
    public PricingRule getRuleById(UUID ruleId) {
        return pricingRuleRepository.findById(ruleId)
                .orElseThrow(() -> new PricingRuleNotFoundException("Pricing rule not found: " + ruleId));
    }

    @Transactional(readOnly = true)
    public List<PricingRule> listRules(ServiceType serviceType, VehicleType vehicleType, String regionId) {
        if (serviceType != null && vehicleType != null && regionId != null) {
            return pricingRuleRepository.findByServiceTypeAndVehicleTypeAndRegionIdOrderByVersionDesc(
                    serviceType, vehicleType, regionId.trim().toUpperCase()
            );
        }
        return pricingRuleRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<PricingRuleAudit> getRuleAudits(UUID ruleId) {
        if (!pricingRuleRepository.existsById(ruleId)) {
            throw new PricingRuleNotFoundException("Pricing rule not found: " + ruleId);
        }
        return auditRepository.findByRuleIdOrderByCreatedAtDesc(ruleId);
    }

    private String buildSnapshot(PricingRule rule) {
        return String.format(
                "{\"ruleId\":\"%s\",\"version\":%d,\"serviceType\":\"%s\",\"vehicleType\":\"%s\",\"regionId\":\"%s\"," +
                        "\"baseFare\":%s,\"includedDistanceKm\":%s,\"pricePerKm\":%s,\"pricePerMinute\":%s,\"minimumFare\":%s," +
                        "\"currency\":\"%s\",\"status\":\"%s\",\"effectiveFrom\":\"%s\",\"effectiveTo\":%s}",
                rule.getId(),
                rule.getVersion(),
                rule.getServiceType(),
                rule.getVehicleType(),
                rule.getRegionId(),
                rule.getBaseFare(),
                rule.getIncludedDistanceKm(),
                rule.getPricePerKm(),
                rule.getPricePerMinute(),
                rule.getMinimumFare(),
                rule.getCurrency(),
                rule.getStatus(),
                rule.getEffectiveFrom(),
                rule.getEffectiveTo() != null ? "\"" + rule.getEffectiveTo() + "\"" : "null"
        );
    }
}
