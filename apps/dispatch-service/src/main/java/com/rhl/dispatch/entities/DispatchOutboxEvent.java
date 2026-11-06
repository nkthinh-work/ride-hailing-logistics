package com.rhl.dispatch.entities;

import com.rhl.dispatch.models.OutboxStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "dispatch_outbox_events")
public class DispatchOutboxEvent {

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "aggregate_type", nullable = false, length = 32)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "recipient_id", nullable = false)
    private UUID recipientId;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OutboxStatus status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "max_retries", nullable = false)
    private int maxRetries;

    @Column(name = "next_retry_at", nullable = false)
    private Instant nextRetryAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "last_error")
    private String lastError;

    protected DispatchOutboxEvent() {}

    public DispatchOutboxEvent(
            UUID id,
            UUID eventId,
            String eventType,
            String aggregateType,
            UUID aggregateId,
            UUID recipientId,
            String correlationId,
            String payload,
            Instant createdAt
    ) {
        this.id = id != null ? id : UUID.randomUUID();
        this.eventId = eventId != null ? eventId : UUID.randomUUID();
        this.eventType = eventType;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.recipientId = recipientId;
        this.correlationId = correlationId;
        this.payload = payload;
        this.status = OutboxStatus.PENDING;
        this.retryCount = 0;
        this.maxRetries = 5;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.nextRetryAt = this.createdAt;
    }

    public void claim(Instant claimUntil) {
        this.nextRetryAt = claimUntil;
    }

    public void markSent(Instant sentAt) {
        this.status = OutboxStatus.SENT;
        this.sentAt = sentAt;
    }

    public void markFailed(String error, Instant nextRetryAt) {
        this.retryCount++;
        this.lastError = error;
        if (this.retryCount >= this.maxRetries) {
            this.status = OutboxStatus.FAILED;
        } else {
            this.nextRetryAt = nextRetryAt;
        }
    }

    public UUID getId() { return id; }
    public UUID getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public String getAggregateType() { return aggregateType; }
    public UUID getAggregateId() { return aggregateId; }
    public UUID getRecipientId() { return recipientId; }
    public String getCorrelationId() { return correlationId; }
    public String getPayload() { return payload; }
    public OutboxStatus getStatus() { return status; }
    public int getRetryCount() { return retryCount; }
    public int getMaxRetries() { return maxRetries; }
    public Instant getNextRetryAt() { return nextRetryAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSentAt() { return sentAt; }
    public String getLastError() { return lastError; }
}
