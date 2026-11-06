package com.rhl.payment.services;

import com.rhl.payment.events.FinancialEventEnvelope;

/**
 * Consumer interface for published financial events (M08 -> M09 boundary).
 */
public interface FinancialEventConsumer {
    void onEvent(FinancialEventEnvelope<?> envelope);
}
