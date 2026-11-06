package com.rhl.payment.dtos;

import com.rhl.payment.entities.PaymentAttempt;
import com.rhl.payment.enums.PaymentAttemptStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentAttemptResponse(
        UUID attemptId,
        UUID paymentId,
        Integer attemptNumber,
        String provider,
        String providerOrderId,
        String providerTransactionId,
        BigDecimal amount,
        String currency,
        PaymentAttemptStatus status,
        String checkoutUrl,
        String idempotencyReference,
        String failureCode,
        String failureReason,
        Instant createdAt,
        Instant updatedAt,
        Instant processedAt
) {
    public static PaymentAttemptResponse from(PaymentAttempt attempt) {
        if (attempt == null) {
            return null;
        }
        return new PaymentAttemptResponse(
                attempt.getId(),
                attempt.getPaymentId(),
                attempt.getAttemptNumber(),
                attempt.getProvider(),
                attempt.getProviderOrderId(),
                attempt.getProviderTransactionId(),
                attempt.getAmount(),
                attempt.getCurrency(),
                attempt.getStatus(),
                attempt.getCheckoutUrl(),
                attempt.getIdempotencyReference(),
                attempt.getFailureCode(),
                attempt.getFailureReason(),
                attempt.getCreatedAt(),
                attempt.getUpdatedAt(),
                attempt.getProcessedAt()
        );
    }
}
