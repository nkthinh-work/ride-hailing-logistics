package com.rhl.payment.enums;

/**
 * Types of discrepancies identified during payment and settlement reconciliation.
 */
public enum ReconciliationMismatchType {
    LOCAL_PROCESSING_PROVIDER_SUCCESS,
    LOCAL_FAILED_PROVIDER_SUCCESS,
    LOCAL_SUCCESS_PROVIDER_REFUNDED,
    AMOUNT_MISMATCH,
    UNKNOWN_TRANSACTION,
    TIMEOUT_UNRESOLVED,
    CASH_NON_DIGITAL
}
