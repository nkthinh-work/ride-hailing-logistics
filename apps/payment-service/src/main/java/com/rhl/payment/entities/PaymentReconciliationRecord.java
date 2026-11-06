package com.rhl.payment.entities;

import com.rhl.payment.enums.ReconciliationMismatchType;
import com.rhl.payment.enums.ReconciliationResolutionState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Audit and reconciliation record tracking discrepancies between local financial state
 * and external payment gateway authoritative state.
 */
@Entity
@Table(name = "payment_reconciliation_records")
public class PaymentReconciliationRecord {

    @Id
    private UUID id;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "provider", nullable = false, length = 50)
    private String provider;

    @Column(name = "provider_order_id", length = 100)
    private String providerOrderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "mismatch_type", nullable = false, length = 50)
    private ReconciliationMismatchType mismatchType;

    @Column(name = "local_status", nullable = false, length = 30)
    private String localStatus;

    @Column(name = "provider_status", length = 30)
    private String providerStatus;

    @Column(name = "local_amount", precision = 12, scale = 2)
    private BigDecimal localAmount;

    @Column(name = "provider_amount", precision = 12, scale = 2)
    private BigDecimal providerAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_state", nullable = false, length = 30)
    private ReconciliationResolutionState resolutionState;

    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_by", length = 100)
    private String resolvedBy;

    protected PaymentReconciliationRecord() {
        // JPA
    }

    public PaymentReconciliationRecord(
            UUID paymentId,
            String provider,
            String providerOrderId,
            ReconciliationMismatchType mismatchType,
            String localStatus,
            String providerStatus,
            BigDecimal localAmount,
            BigDecimal providerAmount,
            ReconciliationResolutionState resolutionState,
            String details,
            Instant checkedAt
    ) {
        this.id = UUID.randomUUID();
        this.paymentId = paymentId;
        this.provider = Objects.requireNonNull(provider, "provider is required");
        this.providerOrderId = providerOrderId;
        this.mismatchType = Objects.requireNonNull(mismatchType, "mismatchType is required");
        this.localStatus = Objects.requireNonNull(localStatus, "localStatus is required");
        this.providerStatus = providerStatus;
        this.localAmount = localAmount;
        this.providerAmount = providerAmount;
        this.resolutionState = resolutionState != null ? resolutionState : ReconciliationResolutionState.PENDING;
        this.details = details;
        this.checkedAt = checkedAt != null ? checkedAt : Instant.now();
    }

    public void markResolved(ReconciliationResolutionState resolutionState, String resolvedBy, Instant resolvedAt) {
        this.resolutionState = resolutionState;
        this.resolvedBy = resolvedBy;
        this.resolvedAt = resolvedAt != null ? resolvedAt : Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public String getProvider() {
        return provider;
    }

    public String getProviderOrderId() {
        return providerOrderId;
    }

    public ReconciliationMismatchType getMismatchType() {
        return mismatchType;
    }

    public String getLocalStatus() {
        return localStatus;
    }

    public String getProviderStatus() {
        return providerStatus;
    }

    public BigDecimal getLocalAmount() {
        return localAmount;
    }

    public BigDecimal getProviderAmount() {
        return providerAmount;
    }

    public ReconciliationResolutionState getResolutionState() {
        return resolutionState;
    }

    public String getDetails() {
        return details;
    }

    public Instant getCheckedAt() {
        return checkedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public String getResolvedBy() {
        return resolvedBy;
    }
}
