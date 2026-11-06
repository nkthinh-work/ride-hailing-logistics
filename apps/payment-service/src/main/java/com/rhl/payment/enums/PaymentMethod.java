package com.rhl.payment.enums;

/**
 * Payment Methods supported according to SRS-RHL-002 (Section 4.8.1, A-06).
 */
public enum PaymentMethod {
    /**
     * Cash payment directly settled with driver upon trip completion.
     */
    CASH,

    /**
     * Digital wallet sandbox/electronic payment.
     */
    DIGITAL_WALLET,

    /**
     * Credit/Debit card sandbox payment.
     */
    CREDIT_CARD
}
