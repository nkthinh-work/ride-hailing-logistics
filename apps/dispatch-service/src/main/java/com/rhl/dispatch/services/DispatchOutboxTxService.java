package com.rhl.dispatch.services;

import com.rhl.dispatch.entities.DispatchOutboxEvent;
import com.rhl.dispatch.models.OutboxStatus;
import com.rhl.dispatch.repositories.DispatchOutboxRepository;
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
 * Dedicated short-lived transaction boundary service for Dispatch Outbox.
 * Eliminates Spring self-invocation, isolates DB transactions from Kafka network I/O,
 * and guarantees atomic, fenced outbox claims at the database level.
 */
@Service
public class DispatchOutboxTxService {

    private static final Logger log = LoggerFactory.getLogger(DispatchOutboxTxService.class);

    private final DispatchOutboxRepository outboxRepository;

    public DispatchOutboxTxService(DispatchOutboxRepository outboxRepository) {
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository is required");
    }

    /**
     * Atomically claims an outbox event by updating nextRetryAt to claimUntil ONLY IF
     * the event is currently PENDING and nextRetryAt <= now.
     * Prevents multiple concurrent workers from claiming the same event.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<DispatchOutboxEvent> claimEvent(UUID id, Instant now, Instant claimUntil) {
        int updated = outboxRepository.claimEventAtomic(id, OutboxStatus.PENDING, now, claimUntil);
        if (updated == 0) {
            log.debug("Event {} was already claimed or no longer pending at {}", id, now);
            return Optional.empty();
        }
        return outboxRepository.findById(id);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<DispatchOutboxEvent> claimEvent(UUID id, Instant claimUntil) {
        return claimEvent(id, Instant.now(), claimUntil);
    }

    /**
     * Marks the outbox event as SENT with claim lease fencing.
     * Rejects updates from stale workers whose lease has expired and been re-claimed.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markSent(UUID id, Instant claimUntil, Instant sentAt) {
        int updated = outboxRepository.markSentAtomic(id, OutboxStatus.SENT, OutboxStatus.PENDING, claimUntil, sentAt);
        if (updated > 0) {
            log.info("Marked dispatch outbox event {} as SENT at {}", id, sentAt);
            return true;
        } else {
            log.warn("Fencing rejected markSent for dispatch outbox event {}: stale lease or already completed", id);
            return false;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSent(UUID id, Instant sentAt) {
        outboxRepository.findById(id).ifPresent(event -> {
            event.markSent(sentAt);
            outboxRepository.save(event);
            log.info("Marked dispatch outbox event {} as SENT at {}", id, sentAt);
        });
    }

    /**
     * Marks the outbox event as failed with claim lease fencing and exponential backoff retry.
     * Rejects updates from stale workers.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markFailed(UUID id, Instant claimUntil, String error, Instant nextRetryAt) {
        int updated = outboxRepository.markFailedAtomic(id, OutboxStatus.PENDING, claimUntil, error, nextRetryAt);
        if (updated > 0) {
            log.warn("Marked dispatch outbox event {} as failed (retry scheduled at {}): {}", id, nextRetryAt, error);
            return true;
        } else {
            log.warn("Fencing rejected markFailed for dispatch outbox event {}: stale lease or already completed", id);
            return false;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID id, String error, Instant nextRetryAt) {
        outboxRepository.findById(id).ifPresent(event -> {
            event.markFailed(error, nextRetryAt);
            outboxRepository.save(event);
            log.warn("Marked dispatch outbox event {} as failed (retryCount={}, nextRetryAt={}): {}",
                    id, event.getRetryCount(), nextRetryAt, error);
        });
    }
}
