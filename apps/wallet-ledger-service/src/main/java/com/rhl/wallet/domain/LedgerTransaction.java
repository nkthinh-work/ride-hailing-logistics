package com.rhl.wallet.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Double-entry journal transaction representing an atomic, immutable financial journal.
 */
@Entity
@Table(name = "ledger_transactions")
public class LedgerTransaction {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 50)
    private LedgerTransactionType transactionType;

    @Column(name = "business_reference", nullable = false, unique = true, length = 100)
    private String businessReference;

    @Column(name = "external_event_id", unique = true)
    private UUID externalEventId;

    @Column(name = "source_service", nullable = false, length = 50)
    private String sourceService;

    @Column(length = 255)
    private String description;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private LedgerTransactionStatus status;

    @Column(name = "correlation_id", length = 100)
    private String correlationId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "posted_at", nullable = false)
    private Instant postedAt;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    @Column(name = "reversed_transaction_id")
    private UUID reversedTransactionId;

    @OneToMany(mappedBy = "transaction", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<LedgerEntry> entries = new ArrayList<>();

    protected LedgerTransaction() {
    }

    public LedgerTransaction(
            UUID id,
            LedgerTransactionType transactionType,
            String businessReference,
            UUID externalEventId,
            String sourceService,
            String description,
            String currency,
            String correlationId,
            Instant occurredAt,
            Instant postedAt,
            String createdBy,
            UUID reversedTransactionId
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.transactionType = Objects.requireNonNull(transactionType, "transactionType must not be null");
        this.businessReference = Objects.requireNonNull(businessReference, "businessReference must not be null");
        this.externalEventId = externalEventId;
        this.sourceService = Objects.requireNonNull(sourceService, "sourceService must not be null");
        this.description = description;
        this.currency = Objects.requireNonNull(currency, "currency must not be null").toUpperCase();
        this.status = LedgerTransactionStatus.POSTED;
        this.correlationId = correlationId;
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        this.postedAt = Objects.requireNonNull(postedAt, "postedAt must not be null");
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy must not be null");
        this.reversedTransactionId = reversedTransactionId;
    }

    public void addEntry(LedgerEntry entry) {
        entries.add(Objects.requireNonNull(entry, "entry must not be null"));
        entry.setTransaction(this);
    }

    public void markReversed() {
        this.status = LedgerTransactionStatus.REVERSED;
    }

    public UUID getId() {
        return id;
    }

    public LedgerTransactionType getTransactionType() {
        return transactionType;
    }

    public String getBusinessReference() {
        return businessReference;
    }

    public UUID getExternalEventId() {
        return externalEventId;
    }

    public String getSourceService() {
        return sourceService;
    }

    public String getDescription() {
        return description;
    }

    public String getCurrency() {
        return currency;
    }

    public LedgerTransactionStatus getStatus() {
        return status;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getPostedAt() {
        return postedAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public UUID getReversedTransactionId() {
        return reversedTransactionId;
    }

    public List<LedgerEntry> getEntries() {
        return Collections.unmodifiableList(entries);
    }
}
