package com.rhl.payment.enums;

/**
 * Resolution workflow state of financial reconciliation discrepancies.
 */
public enum ReconciliationResolutionState {
    PENDING,
    RESOLVED_AUTOMATICALLY,
    FLAGGED_MANUAL_REVIEW,
    RESOLVED_MANUALLY
}
