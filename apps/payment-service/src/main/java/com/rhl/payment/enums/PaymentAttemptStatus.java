package com.rhl.payment.enums;

/**
 * State machine for individual provider charge attempts (SRS Section 4.8.1, FR-PAY-004..006).
 */
public enum PaymentAttemptStatus {
    /**
     * Intent registered, preparing dispatch to provider.
     */
    PENDING,

    /**
     * Dispatched to provider, awaiting customer capture or webhook notification.
     */
    PROCESSING,

    /**
     * Provider confirmed funds captured successfully. Terminal success.
     */
    SUCCEEDED,

    /**
     * Provider declined, payment expired, or explicit terminal failure.
     */
    FAILED,

    /**
     * Customer cancelled payment flow before completion.
     */
    CANCELLED,

    /**
     * Network timeout or unknown remote response. Requires reconciliation before new attempt.
     */
    UNKNOWN;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }

    public boolean isActive() {
        return this == PENDING || this == PROCESSING || this == UNKNOWN;
    }

    public boolean isUnresolved() {
        return this == PENDING || this == PROCESSING || this == UNKNOWN;
    }
}
