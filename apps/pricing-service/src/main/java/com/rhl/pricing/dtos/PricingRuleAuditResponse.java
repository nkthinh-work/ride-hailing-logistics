package com.rhl.pricing.dtos;

import com.rhl.pricing.entities.PricingRuleAudit;
import com.rhl.pricing.models.PricingAuditAction;

import java.time.Instant;
import java.util.UUID;

public record PricingRuleAuditResponse(
        UUID id,
        UUID ruleId,
        int version,
        PricingAuditAction action,
        UUID actorId,
        String reason,
        String snapshot,
        Instant createdAt
) {
    public static PricingRuleAuditResponse fromEntity(PricingRuleAudit audit) {
        return new PricingRuleAuditResponse(
                audit.getId(),
                audit.getRuleId(),
                audit.getVersion(),
                audit.getAction(),
                audit.getActorId(),
                audit.getReason(),
                audit.getSnapshot(),
                audit.getCreatedAt()
        );
    }
}
