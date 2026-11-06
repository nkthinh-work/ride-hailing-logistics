package com.rhl.payment.providers;

import com.rhl.payment.enums.PaymentAttemptStatus;

public record ProviderRefundResult(
        String providerRefundOrderId,
        String providerRefundTransactionId,
        PaymentAttemptStatus status,
        String resultCode,
        String message
) {}
