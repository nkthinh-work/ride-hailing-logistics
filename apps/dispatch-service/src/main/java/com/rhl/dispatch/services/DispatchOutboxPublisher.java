package com.rhl.dispatch.services;

import com.rhl.dispatch.clients.RealtimePublishClient;
import com.rhl.dispatch.entities.DispatchOutboxEvent;
import com.rhl.dispatch.events.DispatchEventEnvelope;
import com.rhl.dispatch.events.DispatchOfferPayload;
import com.rhl.dispatch.models.OutboxStatus;
import com.rhl.dispatch.repositories.DispatchOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class DispatchOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(DispatchOutboxPublisher.class);

    private final DispatchOutboxRepository outboxRepository;
    private final DispatchOutboxTxService txService;
    private final KafkaDispatchEventPublisher kafkaPublisher;
    private final RealtimePublishClient realtimePublishClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final boolean enabled;
    private final String transport;
    private final long leaseDurationMs;

    public DispatchOutboxPublisher(
            DispatchOutboxRepository outboxRepository,
            DispatchOutboxTxService txService,
            KafkaDispatchEventPublisher kafkaPublisher,
            RealtimePublishClient realtimePublishClient,
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${dispatch.outbox.enabled:true}") boolean enabled,
            @Value("${dispatch.outbox.transport:kafka}") String transport,
            @Value("${dispatch.outbox.lease-duration-ms:30000}") long leaseDurationMs
    ) {
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository is required");
        this.txService = Objects.requireNonNull(txService, "txService is required");
        this.kafkaPublisher = Objects.requireNonNull(kafkaPublisher, "kafkaPublisher is required");
        this.realtimePublishClient = Objects.requireNonNull(realtimePublishClient, "realtimePublishClient is required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.enabled = enabled;
        this.transport = transport;
        this.leaseDurationMs = leaseDurationMs;
    }

    @Scheduled(fixedDelayString = "${dispatch.outbox.poll-interval-ms:1000}")
    public void scheduledProcess() {
        if (!enabled) {
            return;
        }
        processPendingEvents();
    }

    public int processPendingEvents() {
        Instant now = clock.instant();
        List<DispatchOutboxEvent> pendingEvents = outboxRepository.findPendingEvents(
                OutboxStatus.PENDING,
                now,
                PageRequest.of(0, 50)
        );

        if (pendingEvents.isEmpty()) {
            return 0;
        }

        int publishedCount = 0;
        for (DispatchOutboxEvent event : pendingEvents) {
            try {
                // 1. Lease claim in short DB transaction (REQUIRES_NEW) with atomic conditional UPDATE
                Instant claimUntil = now.plusMillis(leaseDurationMs).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
                Optional<DispatchOutboxEvent> claimedOpt = txService.claimEvent(event.getId(), now, claimUntil);
                if (claimedOpt.isEmpty()) {
                    continue;
                }
                DispatchOutboxEvent claimed = claimedOpt.get();

                // 2. Dispatch outside any DB transaction
                DispatchOfferPayload payload = objectMapper.readValue(claimed.getPayload(), DispatchOfferPayload.class);
                DispatchEventEnvelope<DispatchOfferPayload> envelope = new DispatchEventEnvelope<>(
                        claimed.getEventId(),
                        claimed.getEventType(),
                        1,
                        claimed.getCreatedAt(),
                        claimed.getCorrelationId(),
                        claimed.getAggregateId(),
                        payload
                );

                if ("kafka".equalsIgnoreCase(transport)) {
                    kafkaPublisher.publishOfferEvent(payload.tripId(), envelope);
                } else {
                    realtimePublishClient.publishOffer(claimed.getRecipientId(), envelope);
                }

                // 3. Mark SENT in short DB transaction (REQUIRES_NEW) with lease fencing
                boolean sent = txService.markSent(claimed.getId(), claimUntil, clock.instant());
                if (sent) {
                    publishedCount++;
                    log.info("Successfully delivered dispatch outbox event {} ({}) via transport {}",
                            claimed.getEventId(), claimed.getEventType(), transport);
                }
            } catch (Exception ex) {
                // 4. Mark FAILED with exponential backoff in short DB transaction (REQUIRES_NEW) with lease fencing
                long backoffMs = Math.min(30000, 500L * (1L << event.getRetryCount()));
                Instant nextRetryAt = now.plusMillis(backoffMs);
                Instant claimUntil = now.plusMillis(leaseDurationMs).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
                txService.markFailed(event.getId(), claimUntil, ex.getMessage(), nextRetryAt);
                log.warn("Failed to publish dispatch outbox event {}: {}", event.getEventId(), ex.getMessage());
            }
        }
        return publishedCount;
    }
}
