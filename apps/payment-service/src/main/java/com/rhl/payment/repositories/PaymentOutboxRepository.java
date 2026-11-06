package com.rhl.payment.repositories;

import com.rhl.payment.entities.PaymentOutboxEvent;
import com.rhl.payment.enums.OutboxStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface PaymentOutboxRepository extends JpaRepository<PaymentOutboxEvent, UUID> {

    @Query("SELECT o FROM PaymentOutboxEvent o WHERE o.status = :status AND (o.nextRetryAt IS NULL OR o.nextRetryAt <= :now) ORDER BY o.createdAt ASC")
    List<PaymentOutboxEvent> findPendingEvents(
            @Param("status") OutboxStatus status,
            @Param("now") Instant now,
            Pageable pageable
    );

    List<PaymentOutboxEvent> findByAggregateId(UUID aggregateId);

    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE PaymentOutboxEvent e SET e.nextRetryAt = :claimUntil WHERE e.eventId = :eventId AND e.status = :status AND (e.nextRetryAt IS NULL OR e.nextRetryAt <= :now)")
    int claimEventAtomic(
            @Param("eventId") UUID eventId,
            @Param("status") OutboxStatus status,
            @Param("now") Instant now,
            @Param("claimUntil") Instant claimUntil
    );

    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE PaymentOutboxEvent e SET e.status = :sentStatus, e.sentAt = :sentAt WHERE e.eventId = :eventId AND e.status = :pendingStatus AND e.nextRetryAt = :expectedClaimUntil")
    int markSentAtomic(
            @Param("eventId") UUID eventId,
            @Param("sentStatus") OutboxStatus sentStatus,
            @Param("sentAt") Instant sentAt,
            @Param("pendingStatus") OutboxStatus pendingStatus,
            @Param("expectedClaimUntil") Instant expectedClaimUntil
    );

    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE PaymentOutboxEvent e SET e.retryCount = e.retryCount + 1, e.lastError = :error, e.nextRetryAt = :nextRetryAt WHERE e.eventId = :eventId AND e.status = :pendingStatus AND e.nextRetryAt = :expectedClaimUntil")
    int markFailedAtomic(
            @Param("eventId") UUID eventId,
            @Param("error") String error,
            @Param("nextRetryAt") Instant nextRetryAt,
            @Param("pendingStatus") OutboxStatus pendingStatus,
            @Param("expectedClaimUntil") Instant expectedClaimUntil
    );
}
