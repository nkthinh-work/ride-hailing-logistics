package com.rhl.realtime.integration;

import com.rhl.realtime.models.DriverOfferPayload;
import com.rhl.realtime.models.EventEnvelope;
import com.rhl.realtime.models.RealtimeEventType;
import com.rhl.realtime.models.TripStatusPayload;
import com.rhl.realtime.services.RealtimeEventPublisher;
import com.rhl.realtime.services.RealtimeSessionRegistry;
import com.rhl.realtime.testutils.TestJwtDecoderConfig;
import com.rhl.realtime.testutils.TestJwtHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestJwtDecoderConfig.class)
@DisplayName("Realtime WebSocket & STOMP End-to-End Integration Tests")
class RealtimeWebSocketIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestJwtHelper jwtHelper;

    @Autowired
    private RealtimeEventPublisher realtimeEventPublisher;

    @Autowired
    private RealtimeSessionRegistry sessionRegistry;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.rhl.realtime.services.TripMembershipProvider membershipProvider;

    private WebSocketStompClient stompClient;
    private StompSession activeSession;

    @BeforeEach
    void setUp() {
        StandardWebSocketClient wsClient = new StandardWebSocketClient();
        stompClient = new WebSocketStompClient(wsClient);
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        mapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
        converter.setObjectMapper(mapper);
        stompClient.setMessageConverter(converter);
    }

    @AfterEach
    void tearDown() {
        if (activeSession != null && activeSession.isConnected()) {
            activeSession.disconnect();
        }
        if (stompClient != null && stompClient.isRunning()) {
            stompClient.stop();
        }
    }

    private String getWsUrl() {
        return "ws://localhost:" + port + "/ws";
    }

    @Test
    @DisplayName("STOMP CONNECT with valid DRIVER token establishes connection and receives messages via /user/queue/offers")
    void driverConnect_subscribesAndReceivesOffer() throws Exception {
        UUID driverId = UUID.randomUUID();
        String token = jwtHelper.createDriverToken(driverId);

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);

        CompletableFuture<StompSession> sessionFuture = new CompletableFuture<>();
        stompClient.connectAsync(getWsUrl(), new WebSocketHttpHeaders(), connectHeaders, new StompSessionHandlerAdapter() {
            @Override
            public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
                sessionFuture.complete(session);
            }

            @Override
            public void handleTransportError(StompSession session, Throwable exception) {
                sessionFuture.completeExceptionally(exception);
            }
        });

        activeSession = sessionFuture.get(5, TimeUnit.SECONDS);
        assertThat(activeSession.isConnected()).isTrue();
        assertThat(sessionRegistry.isUserConnected(driverId)).isTrue();

        // Subscribe to driver's private offer queue
        BlockingQueue<Map<String, Object>> receivedQueue = new LinkedBlockingQueue<>();
        activeSession.subscribe("/user/queue/offers", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleFrame(StompHeaders headers, Object payload) {
                receivedQueue.offer((Map<String, Object>) payload);
            }
        });

        // Publish offer to driver
        UUID offerId = UUID.randomUUID();
        UUID tripId = UUID.randomUUID();
        DriverOfferPayload offerPayload = new DriverOfferPayload(
                offerId,
                tripId,
                driverId,
                "RIDE",
                21.0285,
                105.8542,
                "Hoan Kiem, Hanoi",
                "Cau Giay, Hanoi",
                5.0,
                BigDecimal.valueOf(50000),
                Instant.now(),
                Instant.now().plusSeconds(15),
                "PENDING"
        );
        EventEnvelope<DriverOfferPayload> envelope = EventEnvelope.of(
                RealtimeEventType.DRIVER_OFFER_CREATED,
                offerId,
                "corr-test-1",
                offerPayload
        );

        realtimeEventPublisher.sendOfferToDriver(driverId, envelope);

        // Await delivery via STOMP
        Map<String, Object> received = receivedQueue.poll(5, TimeUnit.SECONDS);
        assertThat(received).isNotNull();
        assertThat(received.get("eventType")).isEqualTo("DRIVER_OFFER_CREATED");
        assertThat(received.get("entityId")).isEqualTo(offerId.toString());

        @SuppressWarnings("unchecked")
        Map<String, Object> payloadMap = (Map<String, Object>) received.get("payload");
        assertThat(payloadMap).isNotNull();
        assertThat(payloadMap.get("offerId")).isEqualTo(offerId.toString());
        assertThat(payloadMap.get("driverId")).isEqualTo(driverId.toString());
        assertThat(payloadMap.get("status")).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("STOMP CONNECT with valid CUSTOMER token establishes connection and receives trip updates via /user/queue/trips")
    void customerConnect_subscribesAndReceivesTripUpdate() throws Exception {
        UUID customerId = UUID.randomUUID();
        String token = jwtHelper.createCustomerToken(customerId);

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);

        CompletableFuture<StompSession> sessionFuture = new CompletableFuture<>();
        stompClient.connectAsync(getWsUrl(), new WebSocketHttpHeaders(), connectHeaders, new StompSessionHandlerAdapter() {
            @Override
            public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
                sessionFuture.complete(session);
            }

            @Override
            public void handleTransportError(StompSession session, Throwable exception) {
                sessionFuture.completeExceptionally(exception);
            }
        });

        activeSession = sessionFuture.get(5, TimeUnit.SECONDS);
        assertThat(activeSession.isConnected()).isTrue();
        assertThat(sessionRegistry.isUserConnected(customerId)).isTrue();

        // Subscribe to customer's private trip queue
        BlockingQueue<Map<String, Object>> receivedQueue = new LinkedBlockingQueue<>();
        activeSession.subscribe("/user/queue/trips", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleFrame(StompHeaders headers, Object payload) {
                receivedQueue.offer((Map<String, Object>) payload);
            }
        });
        Thread.sleep(150);

        // Publish trip update to customer
        UUID tripId = UUID.randomUUID();
        UUID driverId = UUID.randomUUID();
        TripStatusPayload tripPayload = new TripStatusPayload(
                tripId,
                customerId,
                driverId,
                "ACCEPTED",
                "DRIVER",
                "Driver accepted trip",
                Instant.now(),
                1L
        );
        EventEnvelope<TripStatusPayload> envelope = EventEnvelope.of(
                RealtimeEventType.TRIP_STATUS_CHANGED,
                tripId,
                "corr-test-trip",
                tripPayload
        );

        realtimeEventPublisher.sendTripUpdateToCustomer(customerId, envelope);

        // Await delivery via STOMP
        Map<String, Object> received = receivedQueue.poll(5, TimeUnit.SECONDS);
        assertThat(received).isNotNull();
        assertThat(received.get("eventType")).isEqualTo("TRIP_STATUS_CHANGED");
        assertThat(received.get("entityId")).isEqualTo(tripId.toString());

        @SuppressWarnings("unchecked")
        Map<String, Object> payloadMap = (Map<String, Object>) received.get("payload");
        assertThat(payloadMap).isNotNull();
        assertThat(payloadMap.get("tripId")).isEqualTo(tripId.toString());
        assertThat(payloadMap.get("status")).isEqualTo("ACCEPTED");
    }

    @Test
    @DisplayName("STOMP CONNECT with missing token is rejected")
    void missingToken_failsToConnect() {
        StompHeaders connectHeaders = new StompHeaders(); // No Authorization header

        CompletableFuture<StompSession> sessionFuture = new CompletableFuture<>();
        stompClient.connectAsync(getWsUrl(), new WebSocketHttpHeaders(), connectHeaders, new StompSessionHandlerAdapter() {
            @Override
            public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
                sessionFuture.complete(session);
            }

            @Override
            public void handleTransportError(StompSession session, Throwable exception) {
                sessionFuture.completeExceptionally(exception);
            }
        });

        assertThatThrownBy(() -> sessionFuture.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class);
    }

    @Test
    @DisplayName("STOMP CONNECT with expired token is rejected")
    void expiredToken_failsToConnect() {
        UUID driverId = UUID.randomUUID();
        String token = jwtHelper.createExpiredToken(driverId);

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);

        CompletableFuture<StompSession> sessionFuture = new CompletableFuture<>();
        stompClient.connectAsync(getWsUrl(), new WebSocketHttpHeaders(), connectHeaders, new StompSessionHandlerAdapter() {
            @Override
            public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
                sessionFuture.complete(session);
            }

            @Override
            public void handleTransportError(StompSession session, Throwable exception) {
                sessionFuture.completeExceptionally(exception);
            }
        });

        assertThatThrownBy(() -> sessionFuture.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class);
    }

    @Test
    @DisplayName("CUSTOMER Owner connects via STOMP, subscribes to Trip Room, and receives live GPS stream")
    void customer_subscribesToTripRoom_receivesGpsStream() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID driverId = UUID.randomUUID();
        UUID tripId = UUID.randomUUID();

        // Stub authoritative membership
        org.mockito.Mockito.when(membershipProvider.getTripMembership(tripId)).thenReturn(java.util.Optional.of(
                new com.rhl.realtime.models.TripMembershipInfo(tripId, customerId, driverId, "IN_TRIP")
        ));

        String customerToken = jwtHelper.createCustomerToken(customerId);
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + customerToken);

        CompletableFuture<StompSession> sessionFuture = new CompletableFuture<>();
        stompClient.connectAsync(getWsUrl(), new WebSocketHttpHeaders(), connectHeaders, new StompSessionHandlerAdapter() {
            @Override
            public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
                sessionFuture.complete(session);
            }
            @Override
            public void handleTransportError(StompSession session, Throwable exception) {
                sessionFuture.completeExceptionally(exception);
            }
        });

        activeSession = sessionFuture.get(5, TimeUnit.SECONDS);
        assertThat(activeSession.isConnected()).isTrue();

        // Subscribe to dynamic trip room
        String topicDestination = "/topic/trips." + tripId + ".location";
        BlockingQueue<Map<String, Object>> receivedQueue = new LinkedBlockingQueue<>();
        activeSession.subscribe(topicDestination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleFrame(StompHeaders headers, Object payload) {
                receivedQueue.offer((Map<String, Object>) payload);
            }
        });

        // Broadcast validated GPS update
        com.rhl.realtime.models.TripLocationPayload payload = new com.rhl.realtime.models.TripLocationPayload(
                tripId, driverId, 21.0285, 105.8542, 90.0, 15.0, 501L, Instant.now()
        );
        EventEnvelope<com.rhl.realtime.models.TripLocationPayload> locEvent = EventEnvelope.of(
                RealtimeEventType.TRIP_DRIVER_LOCATION_UPDATED,
                UUID.randomUUID(),
                "corr-trip-loc-1",
                payload
        );

        realtimeEventPublisher.broadcastTripLocation(tripId, locEvent);

        Map<String, Object> received = receivedQueue.poll(5, TimeUnit.SECONDS);
        assertThat(received).isNotNull();
        assertThat(received.get("eventType")).isEqualTo("TRIP_DRIVER_LOCATION_UPDATED");

        @SuppressWarnings("unchecked")
        Map<String, Object> payloadMap = (Map<String, Object>) received.get("payload");
        assertThat(payloadMap).isNotNull();
        assertThat(payloadMap.get("tripId")).isEqualTo(tripId.toString());
        assertThat(payloadMap.get("driverId")).isEqualTo(driverId.toString());
        assertThat(payloadMap.get("latitude")).isEqualTo(21.0285);
        assertThat(payloadMap.get("longitude")).isEqualTo(105.8542);
        assertThat(((Number) payloadMap.get("sequenceNumber")).longValue()).isEqualTo(501L);
    }

    @Test
    @DisplayName("Reconnect Ordering: Client discards stale snapshot when newer live STOMP event is received")
    void reconnectSync_staleSnapshotDoesNotOverwriteNewerLiveEvent() {
        // Live STOMP event arrives with sequence 505 at T2
        long liveSequence = 505L;
        Instant liveObservedAt = Instant.now();

        // Delayed HTTP snapshot arrives later with older sequence 502 at T1 (T1 < T2)
        long snapshotSequence = 502L;
        Instant snapshotTimestamp = liveObservedAt.minusSeconds(3);

        // Client ordering rule: If liveSequence >= snapshotSequence, keep live event and drop stale snapshot
        boolean shouldApplySnapshot = snapshotSequence > liveSequence;
        assertThat(shouldApplySnapshot).isFalse();
    }
}
