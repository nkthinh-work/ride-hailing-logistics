package com.rhl.payment.dtos;

import com.rhl.payment.entities.PaymentAttempt;
import com.rhl.payment.entities.PaymentTransaction;
import com.rhl.payment.enums.PaymentMethod;
import com.rhl.payment.enums.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PaymentResponse(
        UUID paymentId,
        UUID tripId,
        UUID customerId,
        BigDecimal amount,
        String currency,
        PaymentMethod paymentMethod,
        PaymentStatus status,
        String idempotencyKey,
        String providerReference,
        String checkoutUrl,
        String failureReason,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt,
        List<PaymentStatusHistoryResponse> statusHistory,
        List<PaymentAttemptResponse> attempts
) {
    /**
     * Backwards-compatible 14-parameter constructor for existing tests and consumers.
     */
    public PaymentResponse(
            UUID paymentId,
            UUID tripId,
            UUID customerId,
            BigDecimal amount,
            String currency,
            PaymentMethod paymentMethod,
            PaymentStatus status,
            String idempotencyKey,
            String providerReference,
            String failureReason,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt,
            List<PaymentStatusHistoryResponse> statusHistory
    ) {
        this(
                paymentId, tripId, customerId, amount, currency, paymentMethod, status,
                idempotencyKey, providerReference, null, failureReason,
                createdAt, updatedAt, completedAt, statusHistory, List.of()
        );
    }

    public static PaymentResponse from(PaymentTransaction payment) {
        return from(payment, null, List.of());
    }

    public static PaymentResponse from(
            PaymentTransaction payment,
            String checkoutUrl,
            List<PaymentAttempt> attempts) {

        List<PaymentStatusHistoryResponse> historyResponses = payment.getStatusHistory() != null
                ? payment.getStatusHistory().stream().map(PaymentStatusHistoryResponse::from).toList()
                : List.of();

        List<PaymentAttemptResponse> attemptResponses = attempts != null
                ? attempts.stream().map(PaymentAttemptResponse::from).toList()
                : List.of();

        return new PaymentResponse(
                payment.getId(),
                payment.getTripId(),
                payment.getCustomerId(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getPaymentMethod(),
                payment.getStatus(),
                payment.getIdempotencyKey(),
                payment.getProviderReference(),
                checkoutUrl,
                payment.getFailureReason(),
                payment.getCreatedAt(),
                payment.getUpdatedAt(),
                payment.getCompletedAt(),
                historyResponses,
                attemptResponses
        );
    }
}
