package com.rhl.payment.dtos;

import com.rhl.payment.entities.PaymentStatusHistory;
import com.rhl.payment.enums.PaymentStatus;

import java.time.Instant;
import java.util.UUID;

public record PaymentStatusHistoryResponse(
        UUID id,
        UUID paymentId,
        PaymentStatus fromStatus,
        PaymentStatus toStatus,
        String actor,
        String actorRole,
        String reason,
        Instant createdAt
) {
    public static PaymentStatusHistoryResponse from(PaymentStatusHistory history) {
        return new PaymentStatusHistoryResponse(
                history.getId(),
                history.getPaymentId(),
                history.getFromStatus(),
                history.getToStatus(),
                history.getActor(),
                history.getActorRole(),
                history.getReason(),
                history.getCreatedAt()
        );
    }
}
