package com.rhl.payment.services;

import com.rhl.payment.entities.PaymentOutboxEvent;
import com.rhl.payment.enums.OutboxStatus;
import com.rhl.payment.events.FinancialEventEnvelope;
import com.rhl.payment.repositories.PaymentOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Background outbox poller ensuring reliable at-least-once delivery of financial events (CON-07, FR-EVT-001..008).
 * Scans pending financial outbox rows and dispatches them with exponential backoff on transient failures.
 *
 * Transaction Boundary Architecture:
 * 1. Short REQUIRES_NEW transaction claims the outbox event with a 30s lease timeout.
 * 2. Kafka network dispatch is performed OUTSIDE of any database transaction.
 * 3. Short REQUIRES_NEW transaction marks the row SENT only after Kafka broker ACK is verified.
 * 4. Short REQUIRES_NEW transaction records retry state / backoff on failure.
 * 5. Crashed nodes leave a 30s expired lease that is automatically recovered by the next poller run.
 */
@Service
public class PaymentOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(PaymentOutboxPublisher.class);

    private final PaymentOutboxRepository outboxRepository;
    private final PaymentOutboxTxService txService;
    private final FinancialEventConsumer eventConsumer;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final boolean enabled;

    @Autowired
    public PaymentOutboxPublisher(
            PaymentOutboxRepository outboxRepository,
            PaymentOutboxTxService txService,
            @Autowired(required = false) FinancialEventConsumer eventConsumer,
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${payment.outbox.enabled:true}") boolean enabled
    ) {
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository is required");
        this.txService = Objects.requireNonNull(txService, "txService is required");
        this.eventConsumer = eventConsumer;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.enabled = enabled;
    }

    public PaymentOutboxPublisher(
            PaymentOutboxRepository outboxRepository,
            PaymentOutboxTxService txService,
            FinancialEventConsumer eventConsumer,
            ObjectMapper objectMapper
    ) {
        this(outboxRepository, txService, eventConsumer, objectMapper, Clock.systemUTC(), true);
    }

    @Scheduled(fixedDelayString = "${payment.outbox.poll-interval-ms:1000}")
    public void scheduledProcess() {
        if (!enabled) {
            return;
        }
        processPendingEvents();
    }

    public int processPendingEvents() {
        if (eventConsumer == null) {
            log.warn("No FinancialEventConsumer bean configured; skipping payment outbox dispatch without marking SENT");
            return 0;
        }
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        List<PaymentOutboxEvent> pendingEvents = outboxRepository.findPendingEvents(
                OutboxStatus.PENDING,
                now,
                PageRequest.of(0, 50)
        );

        if (pendingEvents.isEmpty()) {
            return 0;
        }

        int publishedCount = 0;
        for (PaymentOutboxEvent event : pendingEvents) {
            // 1. Claim event in short DB transaction with atomic conditional UPDATE
            Instant claimUntil = now.plusSeconds(30).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
            boolean claimed = txService.claimEvent(event.getEventId(), now, claimUntil);
            if (!claimed) {
                log.debug("Event {} already claimed or no longer pending", event.getEventId());
                continue;
            }

            // 2. Publish to Kafka outside DB transaction
            try {
                JsonNode payloadNode = objectMapper.readTree(event.getPayload());
                FinancialEventEnvelope<JsonNode> envelope = new FinancialEventEnvelope<>(
                        event.getEventId(),
                        event.getEventType(),
                        1,
                        event.getCreatedAt(),
                        event.getCorrelationId(),
                        event.getAggregateId(),
                        payloadNode
                );

                eventConsumer.onEvent(envelope);

                // 3. Mark SENT in short DB transaction after broker ACK with lease fencing
                boolean sent = txService.markSent(event.getEventId(), claimUntil, clock.instant());
                if (sent) {
                    publishedCount++;
                    log.info("Successfully dispatched financial event {} ({}) for aggregate {}",
                            event.getEventId(), event.getEventType(), event.getAggregateId());
                }
            } catch (Exception ex) {
                // 4. Mark FAILED / schedule retry in short DB transaction with lease fencing
                long backoffMs = Math.min(30000, 500L * (1L << event.getRetryCount()));
                Instant nextRetry = now.plusMillis(backoffMs);
                Instant claimUntilRetry = now.plusSeconds(30).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
                txService.markFailed(event.getEventId(), claimUntilRetry, ex.getMessage(), nextRetry);
                log.warn("Financial outbox event {} failed (retry {}/{}): {}",
                        event.getEventId(), event.getRetryCount() + 1, event.getMaxRetries(), ex.getMessage());
            }
        }
        return publishedCount;
    }
}
