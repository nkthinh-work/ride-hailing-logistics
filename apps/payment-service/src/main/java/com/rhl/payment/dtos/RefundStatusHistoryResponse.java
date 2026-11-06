package com.rhl.payment.dtos;

import com.rhl.payment.entities.RefundStatusHistory;
import com.rhl.payment.enums.RefundStatus;

import java.time.Instant;

public record RefundStatusHistoryResponse(
        RefundStatus previousStatus,
        RefundStatus newStatus,
        String actor,
        String actorRole,
        String reason,
        Instant createdAt
) {
    public static RefundStatusHistoryResponse from(RefundStatusHistory history) {
        return new RefundStatusHistoryResponse(
                history.getPreviousStatus(),
                history.getNewStatus(),
                history.getActor(),
                history.getActorRole(),
                history.getReason(),
                history.getCreatedAt()
        );
    }
}
