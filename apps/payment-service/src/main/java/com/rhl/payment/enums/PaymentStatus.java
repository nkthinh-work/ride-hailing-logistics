package com.rhl.payment.enums;

import java.util.Set;

/**
 * Payment Transaction Lifecycle States (SRS Section 4.8.1, FR-PAY-007).
 */
public enum PaymentStatus {
    /**
     * Payment record created, pending processing or authorization.
     */
    PENDING,

    /**
     * Payment dispatched to provider or cash collection in progress by driver.
     */
    PROCESSING,

    /**
     * Payment captured and confirmed successfully. Terminal success state.
     */
    SUCCEEDED,

    /**
     * Payment attempt failed (declined, network error, timeout).
     */
    FAILED,

    /**
     * Payment cancelled before completion. Terminal cancellation state.
     */
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == CANCELLED;
    }

    public boolean canTransitionTo(PaymentStatus next) {
        if (this == next) {
            return false;
        }
        return switch (this) {
            case PENDING -> next == PROCESSING || next == CANCELLED || next == FAILED || next == SUCCEEDED;
            case PROCESSING -> next == SUCCEEDED || next == FAILED || next == CANCELLED;
            case FAILED -> next == PROCESSING || next == CANCELLED;
            case SUCCEEDED, CANCELLED -> false; // Terminal states cannot regress
        };
    }
}
