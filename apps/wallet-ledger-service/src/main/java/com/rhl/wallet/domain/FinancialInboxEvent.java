package com.rhl.wallet.domain;

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
 * Idempotent Inbox entry for cross-service financial events (FR-EVT-001, FR-EVT-002).
 */
@Entity
@Table(name = "financial_inbox_events")
public class FinancialInboxEvent {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "source_service", nullable = false, length = 50)
    private String sourceService;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    @Column(name = "correlation_id", length = 100)
    private String correlationId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private InboxStatus status;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    protected FinancialInboxEvent() {
    }

    public FinancialInboxEvent(
            UUID eventId,
            String eventType,
            String sourceService,
            UUID entityId,
            String correlationId,
            String payload,
            InboxStatus status,
            String failureReason,
            Instant receivedAt,
            Instant processedAt
    ) {
        this.eventId = Objects.requireNonNull(eventId, "eventId must not be null");
        this.eventType = Objects.requireNonNull(eventType, "eventType must not be null");
        this.sourceService = Objects.requireNonNull(sourceService, "sourceService must not be null");
        this.entityId = Objects.requireNonNull(entityId, "entityId must not be null");
        this.correlationId = correlationId;
        this.payload = Objects.requireNonNull(payload, "payload must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.failureReason = failureReason;
        this.receivedAt = Objects.requireNonNull(receivedAt, "receivedAt must not be null");
        this.processedAt = processedAt;
    }

    public static FinancialInboxEvent received(
            UUID eventId,
            String eventType,
            String sourceService,
            UUID entityId,
            String correlationId,
            String payload,
            Instant now
    ) {
        return new FinancialInboxEvent(
                eventId,
                eventType,
                sourceService,
                entityId,
                correlationId,
                payload,
                InboxStatus.PENDING_POLICY,
                null,
                now,
                null
        );
    }

    public void markProcessed(Instant now) {
        this.status = InboxStatus.PROCESSED;
        this.processedAt = now;
        this.failureReason = null;
    }

    public void markPendingPolicy(String reason, Instant now) {
        this.status = InboxStatus.PENDING_POLICY;
        this.processedAt = now;
        this.failureReason = reason;
    }

    public void markFailed(String reason, Instant now) {
        this.status = InboxStatus.FAILED;
        this.failureReason = reason;
        this.processedAt = now;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getSourceService() {
        return sourceService;
    }

    public UUID getEntityId() {
        return entityId;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getPayload() {
        return payload;
    }

    public InboxStatus getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
