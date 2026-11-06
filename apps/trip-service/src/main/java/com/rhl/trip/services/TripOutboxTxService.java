package com.rhl.trip.services;

import com.rhl.trip.entities.TripOutboxEvent;
import com.rhl.trip.models.OutboxStatus;
import com.rhl.trip.repositories.TripOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Dedicated short-lived transaction boundary service for Trip Outbox.
 * Eliminates Spring self-invocation and isolates DB transactions from Kafka network I/O.
 */
@Service
public class TripOutboxTxService {

    private static final Logger log = LoggerFactory.getLogger(TripOutboxTxService.class);

    private final TripOutboxRepository outboxRepository;

    public TripOutboxTxService(TripOutboxRepository outboxRepository) {
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository is required");
    }

    /**
     * Atomically claims an outbox event in PostgreSQL using a conditional UPDATE.
     * Guarantees that two concurrent workers cannot both claim the same event.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<TripOutboxEvent> claimEvent(UUID id, Instant now, Instant claimUntil) {
        int updated = outboxRepository.claimEventAtomic(id, OutboxStatus.PENDING, now, claimUntil);
        if (updated > 0) {
            return outboxRepository.findById(id);
        }
        log.debug("Trip outbox event {} was already claimed or updated by another worker", id);
        return Optional.empty();
    }

    /**
     * Backward-compatible overload.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<TripOutboxEvent> claimEvent(UUID id, Instant claimUntil) {
        return claimEvent(id, Instant.now(), claimUntil);
    }

    /**
     * Marks event as SENT with lease fencing.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markSent(UUID id, Instant expectedClaimUntil, Instant sentAt) {
        int updated = outboxRepository.markSentAtomic(
                id,
                com.rhl.trip.models.OutboxStatus.SENT,
                sentAt,
                com.rhl.trip.models.OutboxStatus.PENDING,
                expectedClaimUntil
        );
        if (updated > 0) {
            log.info("Marked trip outbox event {} as SENT at {} (fenced)", id, sentAt);
            return true;
        }
        log.warn("Fencing check failed for trip outbox event {}: claim expired or re-claimed by another worker", id);
        return false;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSent(UUID id, Instant sentAt) {
        outboxRepository.findById(id).ifPresent(event -> {
            event.markSent(sentAt);
            outboxRepository.save(event);
            log.info("Marked trip outbox event {} as SENT at {}", id, sentAt);
        });
    }

    /**
     * Marks event as failed with lease fencing.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markFailed(UUID id, Instant expectedClaimUntil, String error, Instant nextRetryAt) {
        int updated = outboxRepository.markFailedAtomic(
                id,
                error,
                nextRetryAt,
                com.rhl.trip.models.OutboxStatus.PENDING,
                expectedClaimUntil
        );
        if (updated > 0) {
            log.warn("Marked trip outbox event {} as failed (fenced, nextRetryAt={}): {}", id, nextRetryAt, error);
            return true;
        }
        log.warn("Fencing check failed when marking trip outbox event {} as failed: lease expired or re-claimed", id);
        return false;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID id, String error, Instant nextRetryAt) {
        outboxRepository.findById(id).ifPresent(event -> {
            event.markFailed(error, nextRetryAt);
            outboxRepository.save(event);
            log.warn("Marked trip outbox event {} as failed (retryCount={}, nextRetryAt={}): {}",
                    id, event.getRetryCount(), nextRetryAt, error);
        });
    }
}
