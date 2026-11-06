package com.rhl.trip.services;

import com.rhl.trip.clients.RealtimeServiceClient;
import com.rhl.trip.entities.TripOutboxEvent;
import com.rhl.trip.events.TripEventEnvelope;
import com.rhl.trip.events.TripStatusPayload;
import com.rhl.trip.models.OutboxStatus;
import com.rhl.trip.repositories.TripOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class TripOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(TripOutboxPublisher.class);

    private final TripOutboxRepository outboxRepository;
    private final TripOutboxTxService txService;
    private final KafkaTripEventPublisher kafkaPublisher;
    private final RealtimeServiceClient realtimeServiceClient;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String transport;
    private final long leaseDurationMs;

    public TripOutboxPublisher(
            TripOutboxRepository outboxRepository,
            TripOutboxTxService txService,
            KafkaTripEventPublisher kafkaPublisher,
            RealtimeServiceClient realtimeServiceClient,
            ObjectMapper objectMapper,
            @Value("${trip.outbox.enabled:true}") boolean enabled,
            @Value("${trip.outbox.transport:kafka}") String transport,
            @Value("${trip.outbox.lease-duration-ms:30000}") long leaseDurationMs
    ) {
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository is required");
        this.txService = Objects.requireNonNull(txService, "txService is required");
        this.kafkaPublisher = Objects.requireNonNull(kafkaPublisher, "kafkaPublisher is required");
        this.realtimeServiceClient = Objects.requireNonNull(realtimeServiceClient, "realtimeServiceClient is required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
        this.enabled = enabled;
        this.transport = transport;
        this.leaseDurationMs = leaseDurationMs;
    }

    @Scheduled(fixedDelayString = "${trip.outbox.poll-interval-ms:1000}")
    public void scheduledProcess() {
        if (!enabled) {
            return;
        }
        processPendingEvents();
    }

    public int processPendingEvents() {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        List<TripOutboxEvent> pendingEvents = outboxRepository.findPendingEvents(
                OutboxStatus.PENDING,
                now,
                PageRequest.of(0, 50)
        );

        if (pendingEvents.isEmpty()) {
            return 0;
        }

        int publishedCount = 0;
        for (TripOutboxEvent event : pendingEvents) {
            try {
                // 1. Lease claim in short DB transaction (REQUIRES_NEW) with atomic conditional UPDATE
                Instant claimUntil = now.plusMillis(leaseDurationMs).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
                Optional<TripOutboxEvent> claimedOpt = txService.claimEvent(event.getId(), now, claimUntil);
                if (claimedOpt.isEmpty()) {
                    continue;
                }
                TripOutboxEvent claimed = claimedOpt.get();

                // 2. Dispatch outside any DB transaction
                TripStatusPayload payload = objectMapper.readValue(claimed.getPayload(), TripStatusPayload.class);
                TripEventEnvelope<TripStatusPayload> envelope = new TripEventEnvelope<>(
                        claimed.getEventId(),
                        claimed.getEventType(),
                        1,
                        claimed.getCreatedAt(),
                        claimed.getCorrelationId(),
                        claimed.getAggregateId(),
                        payload
                );

                if ("kafka".equalsIgnoreCase(transport)) {
                    kafkaPublisher.publishTripEvent(payload.tripId(), envelope);
                } else {
                    realtimeServiceClient.publishTripStatus(claimed.getRecipientId(), envelope);
                    if ("TRIP_COMPLETED".equalsIgnoreCase(claimed.getEventType()) || "TRIP_CANCELLED".equalsIgnoreCase(claimed.getEventType())) {
                        try {
                            realtimeServiceClient.revokeTripRoom(claimed.getAggregateId());
                        } catch (Exception revokeEx) {
                            log.warn("Non-fatal legacy HTTP revoke error for trip {}: {}",
                                    claimed.getAggregateId(), revokeEx.getMessage());
                        }
                    }
                }

                // 3. Mark SENT in short DB transaction (REQUIRES_NEW) with lease fencing
                boolean sent = txService.markSent(claimed.getId(), claimUntil, Instant.now());
                if (sent) {
                    publishedCount++;
                    log.info("Successfully delivered trip outbox event {} ({}) via transport {}",
                            claimed.getEventId(), claimed.getEventType(), transport);
                }
            } catch (Exception ex) {
                // 4. Mark FAILED with exponential backoff in short DB transaction (REQUIRES_NEW) with lease fencing
                long backoffMs = Math.min(30000, 500L * (1L << event.getRetryCount()));
                Instant nextRetryAt = now.plusMillis(backoffMs);
                Instant claimUntil = now.plusMillis(leaseDurationMs).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
                txService.markFailed(event.getId(), claimUntil, ex.getMessage(), nextRetryAt);
                log.warn("Failed to publish trip outbox event {}: {}", event.getEventId(), ex.getMessage());
            }
        }
        return publishedCount;
    }
}
