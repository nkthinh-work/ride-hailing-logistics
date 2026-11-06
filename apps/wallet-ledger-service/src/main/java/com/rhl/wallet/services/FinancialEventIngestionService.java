package com.rhl.wallet.services;

import com.rhl.wallet.configs.WalletProperties;
import com.rhl.wallet.domain.FinancialInboxEvent;
import com.rhl.wallet.domain.InboxStatus;
import com.rhl.wallet.dtos.FinancialEventIngestRequest;
import com.rhl.wallet.dtos.FinancialEventIngestResponse;
import com.rhl.wallet.exceptions.DuplicateFinancialEventException;
import com.rhl.wallet.repositories.FinancialInboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Ingests financial domain events from Payment Service transactional outbox (CON-07, FR-EVT-001..008).
 * Enforces atomic idempotency and refuses to fabricate fake earnings or platform-held balances
 * when commission or settlement policies are pending.
 */
@Service
public class FinancialEventIngestionService {

    private static final Logger log = LoggerFactory.getLogger(FinancialEventIngestionService.class);

    private final FinancialInboxEventRepository inboxRepository;
    private final WalletProperties walletProperties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public FinancialEventIngestionService(
            FinancialInboxEventRepository inboxRepository,
            WalletProperties walletProperties,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.inboxRepository = Objects.requireNonNull(inboxRepository, "inboxRepository is required");
        this.walletProperties = Objects.requireNonNull(walletProperties, "walletProperties is required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public FinancialEventIngestionService(
            FinancialInboxEventRepository inboxRepository,
            WalletProperties walletProperties,
            ObjectMapper objectMapper
    ) {
        this(inboxRepository, walletProperties, objectMapper, Clock.systemUTC());
    }

    @Transactional
    public FinancialEventIngestResponse ingestEvent(FinancialEventIngestRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(request.eventId(), "eventId must not be null");
        Objects.requireNonNull(request.eventType(), "eventType must not be null");

        Instant now = clock.instant();

        // 1. Idempotency Check in Inbox
        Optional<FinancialInboxEvent> existingOpt = inboxRepository.findByEventId(request.eventId());
        if (existingOpt.isPresent()) {
            FinancialInboxEvent existing = existingOpt.get();
            if (isSamePayload(existing.getPayload(), request.payload())) {
                log.info("Idempotent replay detected for financial event {}", request.eventId());
                return new FinancialEventIngestResponse(
                        request.eventId(),
                        "DUPLICATE",
                        "Event already ingested and recorded in inbox",
                        existing.getProcessedAt() != null ? existing.getProcessedAt() : existing.getReceivedAt()
                );
            } else {
                log.warn("Conflicting payload detected for existing event {}", request.eventId());
                throw new DuplicateFinancialEventException(
                        "Event ID " + request.eventId() + " already exists with differing payload content");
            }
        }

        // 2. Ingest into Inbox
        FinancialInboxEvent inboxEvent = FinancialInboxEvent.received(
                request.eventId(),
                request.eventType(),
                request.sourceService() != null ? request.sourceService() : "payment-service",
                request.entityId() != null ? request.entityId() : request.eventId(),
                request.correlationId(),
                request.payload() != null ? request.payload() : "{}",
                now
        );

        // 3. Process Accounting Policy Gating (Group 1 Scope: Invariant Protection)
        if ("PAYMENT_SUCCEEDED".equalsIgnoreCase(request.eventType())) {
            String paymentMethod = extractPaymentMethod(request.payload());

            if ("CASH".equalsIgnoreCase(paymentMethod)) {
                // CASH Trip: Customer paid driver directly. Driver physically holds the money.
                // Platform holds 0.00 VND. We do NOT credit platform-held withdrawable wallet balance.
                String note = "COMMISSION_POLICY_PENDING: Driver physically collected cash; platform commission deduction and driver net earnings awaiting approved policy";
                inboxEvent.markPendingPolicy(note, now);
                log.info("CASH payment event {} ingested and marked PENDING_POLICY (no balance inflation)", request.eventId());
            } else {
                // DIGITAL Trip: Provider captured funds, but merchant settlement and commission policy pending
                String note = "SETTLEMENT_POLICY_PENDING: Provider payment captured; bank settlement and platform commission awaiting approved policy";
                inboxEvent.markPendingPolicy(note, now);
                log.info("Digital payment event {} ingested and marked PENDING_POLICY (no premature balance credit)", request.eventId());
            }
        } else if ("PAYMENT_REFUNDED".equalsIgnoreCase(request.eventType())) {
            String note = "REFUND_POLICY_PENDING: Refund event recorded; compensating journal awaiting approved policy";
            inboxEvent.markPendingPolicy(note, now);
            log.info("Refund event {} ingested and marked PENDING_POLICY", request.eventId());
        } else {
            inboxEvent.markProcessed(now);
            log.info("Financial event {} of type {} ingested and marked PROCESSED", request.eventId(), request.eventType());
        }

        FinancialInboxEvent saved = inboxRepository.save(inboxEvent);

        return new FinancialEventIngestResponse(
                saved.getEventId(),
                saved.getStatus().name(),
                saved.getFailureReason() != null ? saved.getFailureReason() : "Financial event persisted into inbox",
                saved.getProcessedAt() != null ? saved.getProcessedAt() : now
        );
    }

    private String extractPaymentMethod(String payload) {
        if (payload == null || payload.isBlank()) {
            return "UNKNOWN";
        }
        try {
            JsonNode node = objectMapper.readTree(payload);
            if (node.has("paymentMethod")) {
                return node.get("paymentMethod").asText();
            }
        } catch (Exception ex) {
            log.debug("Could not parse paymentMethod from payload: {}", ex.getMessage());
        }
        return "UNKNOWN";
    }

    private boolean isSamePayload(String existingPayload, String incomingPayload) {
        if (existingPayload == null && incomingPayload == null) return true;
        if (existingPayload == null || incomingPayload == null) return false;
        try {
            JsonNode tree1 = objectMapper.readTree(existingPayload);
            JsonNode tree2 = objectMapper.readTree(incomingPayload);
            return tree1.equals(tree2);
        } catch (Exception e) {
            return existingPayload.trim().equals(incomingPayload.trim());
        }
    }
}
