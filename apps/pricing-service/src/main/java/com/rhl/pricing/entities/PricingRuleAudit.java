package com.rhl.pricing.entities;

import com.rhl.pricing.models.PricingAuditAction;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "pricing_rule_audits")
public class PricingRuleAudit {

    @Id
    private UUID id;

    @Column(name = "rule_id", nullable = false)
    private UUID ruleId;

    @Column(name = "version", nullable = false)
    private int version;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 30)
    private PricingAuditAction action;

    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    @Column(name = "reason", length = 255)
    private String reason;

    @Column(name = "snapshot", nullable = false, columnDefinition = "TEXT")
    private String snapshot;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PricingRuleAudit() {
    }

    public PricingRuleAudit(
            UUID id,
            UUID ruleId,
            int version,
            PricingAuditAction action,
            UUID actorId,
            String reason,
            String snapshot,
            Instant createdAt) {
        this.id = id != null ? id : UUID.randomUUID();
        this.ruleId = Objects.requireNonNull(ruleId, "ruleId must not be null");
        this.version = version;
        this.action = Objects.requireNonNull(action, "action must not be null");
        this.actorId = Objects.requireNonNull(actorId, "actorId must not be null");
        this.reason = reason;
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
        this.createdAt = createdAt != null ? createdAt : Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getRuleId() {
        return ruleId;
    }

    public int getVersion() {
        return version;
    }

    public PricingAuditAction getAction() {
        return action;
    }

    public UUID getActorId() {
        return actorId;
    }

    public String getReason() {
        return reason;
    }

    public String getSnapshot() {
        return snapshot;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
