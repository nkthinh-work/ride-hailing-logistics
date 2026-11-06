package com.rhl.payment.services;

import com.rhl.payment.enums.OutboxStatus;
import com.rhl.payment.repositories.PaymentOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Isolated transactional helper for outbox row state transitions (CON-07, FR-EVT-001..008).
 * Uses REQUIRES_NEW short database transactions to strictly decouple database lock lifecycles
 * from Kafka / external network dispatch.
 */
@Service
public class PaymentOutboxTxService {

    private static final Logger log = LoggerFactory.getLogger(PaymentOutboxTxService.class);

    private final PaymentOutboxRepository outboxRepository;

    public PaymentOutboxTxService(PaymentOutboxRepository outboxRepository) {
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository is required");
    }

    /**
     * Atomically claims a payment outbox event in PostgreSQL using a conditional UPDATE.
     * Prevents race condition between concurrent outbox workers.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claimEvent(UUID eventId, Instant now, Instant claimUntil) {
        int updated = outboxRepository.claimEventAtomic(eventId, OutboxStatus.PENDING, now, claimUntil);
        return updated > 0;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claimEvent(UUID eventId, Instant claimUntil) {
        return claimEvent(eventId, Instant.now(), claimUntil);
    }

    /**
     * Marks event as SENT with lease fencing.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markSent(UUID eventId, Instant expectedClaimUntil, Instant sentAt) {
        int updated = outboxRepository.markSentAtomic(eventId, OutboxStatus.SENT, sentAt, OutboxStatus.PENDING, expectedClaimUntil);
        if (updated > 0) {
            log.info("Outbox event {} marked SENT at {} (fenced)", eventId, sentAt);
            return true;
        }
        log.warn("Fencing check failed for payment outbox event {}: claim expired or re-claimed", eventId);
        return false;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSent(UUID eventId, Instant sentAt) {
        outboxRepository.findById(eventId).ifPresent(event -> {
            event.markSent(sentAt);
            outboxRepository.save(event);
            log.info("Outbox event {} marked SENT at {}", eventId, sentAt);
        });
    }

    /**
     * Marks event as failed with lease fencing.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markFailed(UUID eventId, Instant expectedClaimUntil, String errorMessage, Instant nextRetryAt) {
        int updated = outboxRepository.markFailedAtomic(eventId, errorMessage, nextRetryAt, OutboxStatus.PENDING, expectedClaimUntil);
        if (updated > 0) {
            log.warn("Outbox event {} marked FAILED/RETRY (fenced): {}", eventId, errorMessage);
            return true;
        }
        log.warn("Fencing check failed when marking payment outbox event {} as failed: claim expired", eventId);
        return false;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID eventId, String errorMessage, Instant nextRetryAt) {
        outboxRepository.findById(eventId).ifPresent(event -> {
            event.markFailed(errorMessage, nextRetryAt);
            outboxRepository.save(event);
            log.warn("Outbox event {} marked FAILED/RETRY (count {}/{}): {}",
                    eventId, event.getRetryCount(), event.getMaxRetries(), errorMessage);
        });
    }
}
