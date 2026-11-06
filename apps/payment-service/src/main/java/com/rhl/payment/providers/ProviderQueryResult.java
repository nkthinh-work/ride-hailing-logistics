package com.rhl.payment.providers;

import com.rhl.payment.enums.PaymentAttemptStatus;

public record ProviderQueryResult(
        String providerOrderId,
        String providerTransactionId,
        PaymentAttemptStatus status,
        String resultCode,
        String message
) {}
