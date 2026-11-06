package com.rhl.payment.providers;

import com.rhl.payment.enums.PaymentAttemptStatus;

import java.math.BigDecimal;

public record PaymentWebhookResult(
        String provider,
        String providerOrderId,
        String providerTransactionId,
        BigDecimal amount,
        String currency,
        boolean successful,
        String resultCode,
        String message,
        String rawResponse,
        PaymentAttemptStatus status
) {
    public PaymentWebhookResult(
            String provider,
            String providerOrderId,
            String providerTransactionId,
            BigDecimal amount,
            String currency,
            boolean successful,
            String resultCode,
            String message,
            String rawResponse
    ) {
        this(
                provider,
                providerOrderId,
                providerTransactionId,
                amount,
                currency,
                successful,
                resultCode,
                message,
                rawResponse,
                successful ? PaymentAttemptStatus.SUCCEEDED : PaymentAttemptStatus.FAILED
        );
    }
}
