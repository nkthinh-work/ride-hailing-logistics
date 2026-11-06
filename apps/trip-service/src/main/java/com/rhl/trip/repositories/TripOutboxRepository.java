package com.rhl.trip.repositories;

import com.rhl.trip.entities.TripOutboxEvent;
import com.rhl.trip.models.OutboxStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TripOutboxRepository extends JpaRepository<TripOutboxEvent, UUID> {

    @Query("SELECT e FROM TripOutboxEvent e WHERE e.status = :status AND e.nextRetryAt <= :now ORDER BY e.createdAt ASC")
    List<TripOutboxEvent> findPendingEvents(
            @Param("status") OutboxStatus status,
            @Param("now") Instant now,
            Pageable pageable
    );

    Optional<TripOutboxEvent> findByEventId(UUID eventId);

    List<TripOutboxEvent> findByAggregateId(UUID aggregateId);

    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE TripOutboxEvent e SET e.nextRetryAt = :claimUntil WHERE e.id = :id AND e.status = :status AND e.nextRetryAt <= :now")
    int claimEventAtomic(
            @Param("id") UUID id,
            @Param("status") OutboxStatus status,
            @Param("now") Instant now,
            @Param("claimUntil") Instant claimUntil
    );

    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE TripOutboxEvent e SET e.status = :sentStatus, e.sentAt = :sentAt WHERE e.id = :id AND e.status = :pendingStatus AND e.nextRetryAt = :expectedClaimUntil")
    int markSentAtomic(
            @Param("id") UUID id,
            @Param("sentStatus") OutboxStatus sentStatus,
            @Param("sentAt") Instant sentAt,
            @Param("pendingStatus") OutboxStatus pendingStatus,
            @Param("expectedClaimUntil") Instant expectedClaimUntil
    );

    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE TripOutboxEvent e SET e.retryCount = e.retryCount + 1, e.lastError = :error, e.nextRetryAt = :nextRetryAt WHERE e.id = :id AND e.status = :pendingStatus AND e.nextRetryAt = :expectedClaimUntil")
    int markFailedAtomic(
            @Param("id") UUID id,
            @Param("error") String error,
            @Param("nextRetryAt") Instant nextRetryAt,
            @Param("pendingStatus") OutboxStatus pendingStatus,
            @Param("expectedClaimUntil") Instant expectedClaimUntil
    );
}
