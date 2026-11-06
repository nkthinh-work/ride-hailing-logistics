package com.rhl.payment.enums;

/**
 * Lifecycle states for Payment Refunds (SRS Section 4.8.1, FR-PAY-012..013).
 */
public enum RefundStatus {
    /**
     * Refund requested by customer or submitted by support staff, awaiting policy approval or dispatch.
     */
    REQUESTED,

    /**
     * Refund approved by authorized finance staff / policy check, ready for provider execution.
     */
    APPROVED,

    /**
     * Refund dispatched to provider or settlement in progress.
     */
    PROCESSING,

    /**
     * Refund successfully completed and confirmed by provider or manual cash settlement. Terminal.
     */
    SUCCEEDED,

    /**
     * Refund declined by provider or failed. Terminal.
     */
    FAILED,

    /**
     * Refund request cancelled or rejected before completion. Terminal.
     */
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }

    public boolean canTransitionTo(RefundStatus next) {
        if (this == next) {
            return false;
        }
        return switch (this) {
            case REQUESTED -> next == APPROVED || next == PROCESSING || next == CANCELLED || next == FAILED;
            case APPROVED -> next == PROCESSING || next == CANCELLED || next == FAILED;
            case PROCESSING -> next == SUCCEEDED || next == FAILED || next == CANCELLED;
            case SUCCEEDED, FAILED, CANCELLED -> false; // Terminal states cannot transition
        };
    }
}
