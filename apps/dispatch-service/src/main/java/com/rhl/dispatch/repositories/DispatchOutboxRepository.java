package com.rhl.dispatch.repositories;

import com.rhl.dispatch.entities.DispatchOutboxEvent;
import com.rhl.dispatch.models.OutboxStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DispatchOutboxRepository extends JpaRepository<DispatchOutboxEvent, UUID> {

    @Query("SELECT e FROM DispatchOutboxEvent e WHERE e.status = :status AND e.nextRetryAt <= :now ORDER BY e.createdAt ASC")
    List<DispatchOutboxEvent> findPendingEvents(
            @Param("status") OutboxStatus status,
            @Param("now") Instant now,
            Pageable pageable
    );

    Optional<DispatchOutboxEvent> findByEventId(UUID eventId);

    List<DispatchOutboxEvent> findByAggregateId(UUID aggregateId);

    @Modifying
    @Query("UPDATE DispatchOutboxEvent e SET e.nextRetryAt = :claimUntil " +
           "WHERE e.id = :id AND e.status = :pendingStatus AND e.nextRetryAt <= :now")
    int claimEventAtomic(
            @Param("id") UUID id,
            @Param("pendingStatus") OutboxStatus pendingStatus,
            @Param("now") Instant now,
            @Param("claimUntil") Instant claimUntil
    );

    @Modifying
    @Query("UPDATE DispatchOutboxEvent e SET e.status = :sentStatus, e.sentAt = :sentAt " +
           "WHERE e.id = :id AND e.status = :pendingStatus AND e.nextRetryAt = :claimUntil")
    int markSentAtomic(
            @Param("id") UUID id,
            @Param("sentStatus") OutboxStatus sentStatus,
            @Param("pendingStatus") OutboxStatus pendingStatus,
            @Param("claimUntil") Instant claimUntil,
            @Param("sentAt") Instant sentAt
    );

    @Modifying
    @Query("UPDATE DispatchOutboxEvent e SET e.retryCount = e.retryCount + 1, e.lastError = :error, " +
           "e.status = CASE WHEN e.retryCount + 1 >= e.maxRetries THEN com.rhl.dispatch.models.OutboxStatus.FAILED ELSE com.rhl.dispatch.models.OutboxStatus.PENDING END, " +
           "e.nextRetryAt = :nextRetryAt " +
           "WHERE e.id = :id AND e.status = :pendingStatus AND e.nextRetryAt = :claimUntil")
    int markFailedAtomic(
            @Param("id") UUID id,
            @Param("pendingStatus") OutboxStatus pendingStatus,
            @Param("claimUntil") Instant claimUntil,
            @Param("error") String error,
            @Param("nextRetryAt") Instant nextRetryAt
    );
}
