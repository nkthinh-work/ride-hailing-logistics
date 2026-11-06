package com.rhl.realtime.integration;

import com.rhl.realtime.consumers.DispatchOfferKafkaListener;
import com.rhl.realtime.consumers.RealtimeDeadLetterListener;
import com.rhl.realtime.consumers.TripLifecycleKafkaListener;
import com.rhl.realtime.services.RealtimeEventPublisher;
import com.rhl.realtime.services.TripMembershipProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "realtime.kafka.enabled=true",
        "realtime.kafka.consumer.auto-offset-reset=earliest",
        "spring.main.allow-bean-definition-overriding=true"
})
@EmbeddedKafka(
        partitions = 3,
        topics = {
                "rhl.dispatch.offer.v1",
                "rhl.dispatch.offer.v1.dlt",
                "rhl.trip.lifecycle.v1",
                "rhl.trip.lifecycle.v1.dlt"
        },
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
@DisplayName("Realtime Kafka Consumer End-to-End Integration Test with Embedded Kafka")
class RealtimeKafkaE2EIntegrationTest {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @MockitoBean
    private RealtimeEventPublisher realtimeEventPublisher;

    @MockitoBean
    private TripMembershipProvider membershipProvider;

    @Autowired
    private DispatchOfferKafkaListener dispatchOfferKafkaListener;

    @Autowired
    private TripLifecycleKafkaListener tripLifecycleKafkaListener;

    @Autowired
    private RealtimeDeadLetterListener deadLetterListener;

    @Test
    @DisplayName("Should consume dispatch offer from Kafka and dispatch to driver via STOMP")
    void testDispatchOfferEndToEnd() {
        UUID tripId = UUID.randomUUID();
        UUID driverId = UUID.randomUUID();
        UUID offerId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        String json = """
                {
                  "eventId": "%s",
                  "eventType": "DRIVER_OFFER_CREATED",
                  "payload": {
                    "offerId": "%s",
                    "tripId": "%s",
                    "driverId": "%s",
                    "serviceType": "RIDE",
                    "pickupLatitude": 10.7769,
                    "pickupLongitude": 106.7009,
                    "pickupAddress": "123 Le Loi",
                    "dropoffAddress": "456 Nguyen Hue",
                    "estimatedDistanceKm": 3.0,
                    "estimatedFare": 50000.00,
                    "offeredAt": "2026-10-27T10:00:00Z",
                    "expiresAt": "2026-10-27T23:59:59Z",
                    "status": "CREATED"
                  }
                }
                """.formatted(eventId, offerId, tripId, driverId);

        kafkaTemplate.send("rhl.dispatch.offer.v1", tripId.toString(), json);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(realtimeEventPublisher, atLeastOnce()).sendOfferToDriver(eq(driverId), any());
        });
    }

    @Test
    @DisplayName("Should consume trip completed from Kafka, revoke room, and dispatch to customer")
    void testTripCompletedEndToEnd() {
        UUID tripId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        String json = """
                {
                  "eventId": "%s",
                  "eventType": "TRIP_COMPLETED",
                  "payload": {
                    "tripId": "%s",
                    "customerId": "%s",
                    "driverId": "%s",
                    "status": "COMPLETED",
                    "actor": "DRIVER",
                    "occurredAt": "2026-10-27T10:30:00Z",
                    "sequence": 5
                  }
                }
                """.formatted(eventId, tripId, customerId, UUID.randomUUID());

        kafkaTemplate.send("rhl.trip.lifecycle.v1", tripId.toString(), json);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(realtimeEventPublisher, atLeastOnce()).sendTripUpdateToCustomer(eq(customerId), any());
            verify(membershipProvider, atLeastOnce()).invalidateTrip(eq(tripId));
            assertThat(tripLifecycleKafkaListener.isTripTerminal(tripId)).isTrue();
        });
    }

    @Test
    @DisplayName("Should route malformed poison pill message to DLT topic without crashing consumer")
    void testPoisonPillRoutingToDlt() {
        String poisonPill = "{\"bad\": \"malformed_json_without_required_fields\"}";

        kafkaTemplate.send("rhl.dispatch.offer.v1", "corrupt-key", poisonPill);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(deadLetterListener.getDeadLetterCount()).isGreaterThanOrEqualTo(1);
        });
    }
}
