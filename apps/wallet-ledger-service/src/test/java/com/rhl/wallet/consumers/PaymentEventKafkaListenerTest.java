package com.rhl.wallet.consumers;

import com.rhl.wallet.dtos.FinancialEventIngestRequest;
import com.rhl.wallet.dtos.FinancialEventIngestResponse;
import com.rhl.wallet.exceptions.DuplicateFinancialEventException;
import com.rhl.wallet.services.FinancialEventIngestionService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentEventKafkaListener Unit Tests — Contract Validation & Ingestion")
class PaymentEventKafkaListenerTest {

    @Mock
    private FinancialEventIngestionService ingestionService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private PaymentEventKafkaListener listener;

    @BeforeEach
    void setUp() {
        listener = new PaymentEventKafkaListener(ingestionService, objectMapper);
    }

    @Test
    @DisplayName("Parses valid PAYMENT_SUCCEEDED event and invokes ingestionService")
    void onMessage_validSucceededEvent_success() {
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        String json = """
                {
                  "eventId": "%s",
                  "eventType": "PAYMENT_SUCCEEDED",
                  "eventVersion": 1,
                  "occurredAt": "2026-10-27T10:00:00Z",
                  "correlationId": "corr-101",
                  "entityId": "%s",
                  "payload": {
                    "paymentId": "%s",
                    "tripId": "11111111-1111-1111-1111-111111111111",
                    "amount": 50000,
                    "currency": "VND",
                    "paymentMethod": "CASH"
                  }
                }
                """.formatted(eventId, paymentId, paymentId);

        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "rhl.payment.events.v1", 0, 10L, paymentId.toString(), json
        );

        when(ingestionService.ingestEvent(any(FinancialEventIngestRequest.class)))
                .thenReturn(new FinancialEventIngestResponse(eventId, "PENDING_POLICY", "Saved", Instant.now()));

        listener.onMessage(record);

        ArgumentCaptor<FinancialEventIngestRequest> captor = ArgumentCaptor.forClass(FinancialEventIngestRequest.class);
        verify(ingestionService).ingestEvent(captor.capture());
        FinancialEventIngestRequest req = captor.getValue();

        assertThat(req.eventId()).isEqualTo(eventId);
        assertThat(req.eventType()).isEqualTo("PAYMENT_SUCCEEDED");
        assertThat(req.entityId()).isEqualTo(paymentId);
        assertThat(req.correlationId()).isEqualTo("corr-101");
        assertThat(req.payload()).contains("CASH");
    }

    @Test
    @DisplayName("Handles idempotent duplicate replay safely without error")
    void onMessage_duplicateEvent_handledIdempotently() {
        UUID eventId = UUID.randomUUID();
        String json = """
                {
                  "eventId": "%s",
                  "eventType": "PAYMENT_SUCCEEDED",
                  "payload": {"amount": 50000}
                }
                """.formatted(eventId);

        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "rhl.payment.events.v1", 0, 15L, "key", json
        );

        when(ingestionService.ingestEvent(any()))
                .thenReturn(new FinancialEventIngestResponse(eventId, "DUPLICATE", "Already exists", Instant.now()));

        listener.onMessage(record);

        verify(ingestionService).ingestEvent(any());
    }

    @Test
    @DisplayName("Throws IllegalArgumentException when eventId is missing (routes to DLT)")
    void onMessage_missingEventId_throwsIllegalArgument() {
        String json = """
                {
                  "eventType": "PAYMENT_SUCCEEDED",
                  "payload": {"amount": 50000}
                }
                """;
        ConsumerRecord<String, String> record = new ConsumerRecord<>("rhl.payment.events.v1", 0, 20L, "key", json);

        assertThatThrownBy(() -> listener.onMessage(record))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing required field 'eventId'");
    }

    @Test
    @DisplayName("Throws IllegalArgumentException for unsupported eventType (routes to DLT)")
    void onMessage_unsupportedEventType_throwsIllegalArgument() {
        UUID eventId = UUID.randomUUID();
        String json = """
                {
                  "eventId": "%s",
                  "eventType": "ORDER_SHIPPED",
                  "payload": {}
                }
                """.formatted(eventId);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("rhl.payment.events.v1", 0, 25L, "key", json);

        assertThatThrownBy(() -> listener.onMessage(record))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported eventType");
    }

    @Test
    @DisplayName("Propagates DuplicateFinancialEventException when payload conflicts")
    void onMessage_conflictingPayload_propagatesException() {
        UUID eventId = UUID.randomUUID();
        String json = """
                {
                  "eventId": "%s",
                  "eventType": "PAYMENT_SUCCEEDED",
                  "payload": {"amount": 99999}
                }
                """.formatted(eventId);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("rhl.payment.events.v1", 0, 30L, "key", json);

        when(ingestionService.ingestEvent(any()))
                .thenThrow(new DuplicateFinancialEventException("Payload conflict for event " + eventId));

        assertThatThrownBy(() -> listener.onMessage(record))
                .isInstanceOf(DuplicateFinancialEventException.class)
                .hasMessageContaining("Payload conflict");
    }
}
