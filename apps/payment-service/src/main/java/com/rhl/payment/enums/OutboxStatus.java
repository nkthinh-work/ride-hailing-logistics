package com.rhl.payment.enums;

/**
 * Status of transactional outbox events (SRS Section 4.9, CON-07, FR-EVT-001..008).
 */
public enum OutboxStatus {
    PENDING,
    SENT,
    FAILED
}
