package com.rhl.payment.providers;

import com.rhl.payment.enums.PaymentAttemptStatus;

public record PaymentIntentResult(
        String providerOrderId,
        String checkoutUrl,
        String providerReference,
        PaymentAttemptStatus status,
        String message
) {}
