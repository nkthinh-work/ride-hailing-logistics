package com.rhl.payment.dtos;

import com.rhl.payment.entities.PaymentRefund;
import com.rhl.payment.enums.RefundStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RefundResponse(
        UUID refundId,
        UUID paymentId,
        UUID customerId,
        UUID tripId,
        BigDecimal amount,
        String currency,
        RefundStatus status,
        String reason,
        String requestedBy,
        String approvedBy,
        String provider,
        String providerRefundOrderId,
        String providerRefundTransactionId,
        String policyNote,
        Instant createdAt,
        Instant completedAt,
        List<RefundStatusHistoryResponse> statusHistory
) {
    public static RefundResponse from(PaymentRefund refund) {
        List<RefundStatusHistoryResponse> history = refund.getStatusHistory() != null
                ? refund.getStatusHistory().stream().map(RefundStatusHistoryResponse::from).toList()
                : List.of();

        return new RefundResponse(
                refund.getId(),
                refund.getPaymentId(),
                refund.getCustomerId(),
                refund.getTripId(),
                refund.getAmount(),
                refund.getCurrency(),
                refund.getStatus(),
                refund.getReason(),
                refund.getRequestedBy(),
                refund.getApprovedBy(),
                refund.getProvider(),
                refund.getProviderRefundOrderId(),
                refund.getProviderRefundTransactionId(),
                refund.getPolicyNote(),
                refund.getCreatedAt(),
                refund.getCompletedAt(),
                history
        );
    }
}
