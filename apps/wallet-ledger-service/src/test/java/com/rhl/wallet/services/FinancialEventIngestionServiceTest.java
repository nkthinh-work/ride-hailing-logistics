package com.rhl.wallet.services;

import com.rhl.wallet.configs.WalletProperties;
import com.rhl.wallet.domain.FinancialInboxEvent;
import com.rhl.wallet.domain.InboxStatus;
import com.rhl.wallet.dtos.FinancialEventIngestRequest;
import com.rhl.wallet.dtos.FinancialEventIngestResponse;
import com.rhl.wallet.exceptions.DuplicateFinancialEventException;
import com.rhl.wallet.repositories.FinancialInboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("FinancialEventIngestionService Unit Tests — CASH vs DIGITAL Accounting Semantics")
class FinancialEventIngestionServiceTest {

    @Mock
    private FinancialInboxEventRepository inboxRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WalletProperties walletProperties = new WalletProperties();
    private final Instant fixedTime = Instant.parse("2026-10-27T08:30:00Z");
    private final Clock clock = Clock.fixed(fixedTime, ZoneOffset.UTC);

    private FinancialEventIngestionService ingestionService;

    @BeforeEach
    void setUp() {
        ingestionService = new FinancialEventIngestionService(inboxRepository, walletProperties, objectMapper, clock);
    }

    @Test
    @DisplayName("CASH Payment Event: Marks PENDING_POLICY (COMMISSION_POLICY_PENDING) and does not inflate withdrawable balance")
    void ingestEvent_cashPayment_marksPendingPolicy_doesNotInflateBalance() {
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        String payload = """
                {
                    "paymentId": "%s",
                    "tripId": "%s",
                    "customerId": "%s",
                    "amount": 100000.00,
                    "currency": "VND",
                    "paymentMethod": "CASH",
                    "status": "SUCCEEDED"
                }
                """.formatted(paymentId, UUID.randomUUID(), UUID.randomUUID());

        when(inboxRepository.findByEventId(eventId)).thenReturn(Optional.empty());
        when(inboxRepository.save(any(FinancialInboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        FinancialEventIngestRequest request = new FinancialEventIngestRequest(
                eventId,
                "PAYMENT_SUCCEEDED",
                "payment-service",
                paymentId,
                "corr-cash",
                payload
        );

        FinancialEventIngestResponse response = ingestionService.ingestEvent(request);

        assertThat(response.eventId()).isEqualTo(eventId);
        assertThat(response.status()).isEqualTo("PENDING_POLICY");
        assertThat(response.message()).contains("COMMISSION_POLICY_PENDING");

        ArgumentCaptor<FinancialInboxEvent> captor = ArgumentCaptor.forClass(FinancialInboxEvent.class);
        verify(inboxRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(InboxStatus.PENDING_POLICY);
        assertThat(captor.getValue().getFailureReason()).contains("Driver physically collected cash");
    }

    @Test
    @DisplayName("DIGITAL Payment Event: Marks PENDING_POLICY (SETTLEMENT_POLICY_PENDING)")
    void ingestEvent_digitalPayment_marksPendingPolicy() {
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        String payload = """
                {
                    "paymentId": "%s",
                    "tripId": "%s",
                    "customerId": "%s",
                    "amount": 120000.00,
                    "currency": "VND",
                    "paymentMethod": "DIGITAL_WALLET",
                    "status": "SUCCEEDED"
                }
                """.formatted(paymentId, UUID.randomUUID(), UUID.randomUUID());

        when(inboxRepository.findByEventId(eventId)).thenReturn(Optional.empty());
        when(inboxRepository.save(any(FinancialInboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        FinancialEventIngestRequest request = new FinancialEventIngestRequest(
                eventId,
                "PAYMENT_SUCCEEDED",
                "payment-service",
                paymentId,
                "corr-digital",
                payload
        );

        FinancialEventIngestResponse response = ingestionService.ingestEvent(request);

        assertThat(response.eventId()).isEqualTo(eventId);
        assertThat(response.status()).isEqualTo("PENDING_POLICY");
        assertThat(response.message()).contains("SETTLEMENT_POLICY_PENDING");
    }

    @Test
    @DisplayName("Duplicate eventId with same payload returns DUPLICATE status without error")
    void ingestEvent_duplicateEventId_returnsDuplicateReplay() {
        UUID eventId = UUID.randomUUID();
        String payload = "{\"paymentMethod\":\"CASH\",\"amount\":50000.00}";

        FinancialInboxEvent existing = new FinancialInboxEvent(
                eventId,
                "PAYMENT_SUCCEEDED",
                "payment-service",
                UUID.randomUUID(),
                "corr-dup",
                payload,
                InboxStatus.PENDING_POLICY,
                "Policy pending note",
                fixedTime,
                fixedTime
        );
        when(inboxRepository.findByEventId(eventId)).thenReturn(Optional.of(existing));

        FinancialEventIngestRequest request = new FinancialEventIngestRequest(
                eventId,
                "PAYMENT_SUCCEEDED",
                "payment-service",
                existing.getEntityId(),
                "corr-dup",
                payload
        );

        FinancialEventIngestResponse response = ingestionService.ingestEvent(request);

        assertThat(response.eventId()).isEqualTo(eventId);
        assertThat(response.status()).isEqualTo("DUPLICATE");
    }

    @Test
    @DisplayName("Duplicate eventId with conflicting payload throws DuplicateFinancialEventException")
    void ingestEvent_conflictingPayload_throwsDuplicateFinancialEventException() {
        UUID eventId = UUID.randomUUID();
        FinancialInboxEvent existing = new FinancialInboxEvent(
                eventId,
                "PAYMENT_SUCCEEDED",
                "payment-service",
                UUID.randomUUID(),
                "corr-1",
                "{\"amount\":50000.00}",
                InboxStatus.PROCESSED,
                null,
                fixedTime,
                fixedTime
        );
        when(inboxRepository.findByEventId(eventId)).thenReturn(Optional.of(existing));

        FinancialEventIngestRequest conflictingRequest = new FinancialEventIngestRequest(
                eventId,
                "PAYMENT_SUCCEEDED",
                "payment-service",
                existing.getEntityId(),
                "corr-1",
                "{\"amount\":999999.00}" // different amount
        );

        assertThatThrownBy(() -> ingestionService.ingestEvent(conflictingRequest))
                .isInstanceOf(DuplicateFinancialEventException.class)
                .hasMessageContaining("already exists with differing payload content");
    }
}
