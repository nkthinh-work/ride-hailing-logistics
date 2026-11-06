package com.rhl.payment.entities;

import com.rhl.payment.enums.OutboxStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Transactional Outbox Event for reliable financial messaging (SRS Section 4.9, CON-07, FR-EVT-001..008).
 * Stored atomically within the same database transaction as the financial state mutation.
 */
@Entity
@Table(name = "payment_outbox_events")
public class PaymentOutboxEvent {

    @Id
    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "correlation_id", length = 100)
    private String correlationId;

    @Column(name = "recipient_id")
    private UUID recipientId;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OutboxStatus status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "max_retries", nullable = false)
    private int maxRetries;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    protected PaymentOutboxEvent() {
        // JPA
    }

    public PaymentOutboxEvent(
            UUID eventId,
            String eventType,
            String aggregateType,
            UUID aggregateId,
            String correlationId,
            UUID recipientId,
            String payload,
            Instant createdAt
    ) {
        this.eventId = eventId != null ? eventId : UUID.randomUUID();
        this.eventType = Objects.requireNonNull(eventType, "eventType is required");
        this.aggregateType = aggregateType != null ? aggregateType : "PAYMENT";
        this.aggregateId = Objects.requireNonNull(aggregateId, "aggregateId is required");
        this.correlationId = correlationId;
        this.recipientId = recipientId;
        this.payload = Objects.requireNonNull(payload, "payload is required");
        this.status = OutboxStatus.PENDING;
        this.retryCount = 0;
        this.maxRetries = 5;
        Instant now = createdAt != null ? createdAt : Instant.now();
        this.createdAt = now;
        this.nextRetryAt = now;
    }

    public void markSent(Instant sentAt) {
        this.status = OutboxStatus.SENT;
        this.sentAt = sentAt != null ? sentAt : Instant.now();
    }

    public void claim(Instant claimUntil) {
        this.nextRetryAt = claimUntil;
    }

    public void markFailed(String error, Instant nextRetryAt) {
        this.retryCount++;
        this.lastError = (error != null && error.length() > 500) ? error.substring(0, 497) + "..." : error;
        if (this.retryCount >= this.maxRetries) {
            this.status = OutboxStatus.FAILED;
        } else {
            this.nextRetryAt = nextRetryAt;
        }
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public UUID getRecipientId() {
        return recipientId;
    }

    public String getPayload() {
        return payload;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public Instant getNextRetryAt() {
        return nextRetryAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public String getLastError() {
        return lastError;
    }

    public Long getVersion() {
        return version;
    }
}
