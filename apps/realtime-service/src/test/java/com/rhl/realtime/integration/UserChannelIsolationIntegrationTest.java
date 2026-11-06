package com.rhl.realtime.integration;

import com.rhl.realtime.models.DriverOfferPayload;
import com.rhl.realtime.models.EventEnvelope;
import com.rhl.realtime.models.RealtimeEventType;
import com.rhl.realtime.services.RealtimeEventPublisher;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestJwtDecoderConfig.class)
@DisplayName("User Channel Isolation Integration Tests (P0)")
class UserChannelIsolationIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestJwtHelper jwtHelper;

    @Autowired
    private RealtimeEventPublisher realtimeEventPublisher;

    private WebSocketStompClient clientA;
    private WebSocketStompClient clientB;
    private StompSession sessionA;
    private StompSession sessionB;

    @BeforeEach
    void setUp() {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        mapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
        converter.setObjectMapper(mapper);

        clientA = new WebSocketStompClient(new StandardWebSocketClient());
        clientA.setMessageConverter(converter);

        clientB = new WebSocketStompClient(new StandardWebSocketClient());
        clientB.setMessageConverter(converter);
    }

    @AfterEach
    void tearDown() {
        if (sessionA != null && sessionA.isConnected()) {
            sessionA.disconnect();
        }
        if (sessionB != null && sessionB.isConnected()) {
            sessionB.disconnect();
        }
        if (clientA != null && clientA.isRunning()) {
            clientA.stop();
        }
        if (clientB != null && clientB.isRunning()) {
            clientB.stop();
        }
    }

    private String getWsUrl() {
        return "ws://localhost:" + port + "/ws";
    }

    @Test
    @DisplayName("P0 Isolation: When Server sends Offer to Driver A, Driver A receives it and Driver B NEVER receives it")
    void userIsolation_offerSentToDriverA_neverReceivedByDriverB() throws Exception {
        UUID driverAId = UUID.randomUUID();
        UUID driverBId = UUID.randomUUID();

        String tokenA = jwtHelper.createDriverToken(driverAId);
        String tokenB = jwtHelper.createDriverToken(driverBId);

        // 1. Connect Driver A
        StompHeaders headersA = new StompHeaders();
        headersA.add("Authorization", "Bearer " + tokenA);
        CompletableFuture<StompSession> futureA = new CompletableFuture<>();
        clientA.connectAsync(getWsUrl(), new WebSocketHttpHeaders(), headersA, new StompSessionHandlerAdapter() {
            @Override
            public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
                futureA.complete(session);
            }
        });
        sessionA = futureA.get(5, TimeUnit.SECONDS);

        // 2. Connect Driver B
        StompHeaders headersB = new StompHeaders();
        headersB.add("Authorization", "Bearer " + tokenB);
        CompletableFuture<StompSession> futureB = new CompletableFuture<>();
        clientB.connectAsync(getWsUrl(), new WebSocketHttpHeaders(), headersB, new StompSessionHandlerAdapter() {
            @Override
            public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
                futureB.complete(session);
            }
        });
        sessionB = futureB.get(5, TimeUnit.SECONDS);

        assertThat(sessionA.isConnected()).isTrue();
        assertThat(sessionB.isConnected()).isTrue();

        // 3. Both drivers subscribe to /user/queue/offers
        BlockingQueue<Map<String, Object>> queueA = new LinkedBlockingQueue<>();
        sessionA.subscribe("/user/queue/offers", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleFrame(StompHeaders headers, Object payload) {
                queueA.offer((Map<String, Object>) payload);
            }
        });

        BlockingQueue<Map<String, Object>> queueB = new LinkedBlockingQueue<>();
        sessionB.subscribe("/user/queue/offers", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleFrame(StompHeaders headers, Object payload) {
                queueB.offer((Map<String, Object>) payload);
            }
        });

        // Small pause to allow subscriptions to be registered in Spring's UserDestinationMessageHandler
        Thread.sleep(500);

        // 4. Server publishes Offer dedicated ONLY to Driver A
        UUID offerId = UUID.randomUUID();
        UUID tripId = UUID.randomUUID();
        DriverOfferPayload payloadA = new DriverOfferPayload(
                offerId,
                tripId,
                driverAId,
                "RIDE",
                21.0285,
                105.8542,
                "Pickup A",
                "Dropoff A",
                4.5,
                BigDecimal.valueOf(45000),
                Instant.now(),
                Instant.now().plusSeconds(15),
                "PENDING"
        );
        EventEnvelope<DriverOfferPayload> envelopeA = EventEnvelope.of(
                RealtimeEventType.DRIVER_OFFER_CREATED,
                offerId,
                "corr-iso-A",
                payloadA
        );

        realtimeEventPublisher.sendOfferToDriver(driverAId, envelopeA);

        // 5. Driver A MUST receive the offer
        Map<String, Object> receivedA = queueA.poll(5, TimeUnit.SECONDS);
        assertThat(receivedA).isNotNull();
        assertThat(receivedA.get("entityId")).isEqualTo(offerId.toString());

        @SuppressWarnings("unchecked")
        Map<String, Object> innerPayload = (Map<String, Object>) receivedA.get("payload");
        assertThat(innerPayload.get("driverId")).isEqualTo(driverAId.toString());

        // 6. Driver B MUST NOT receive anything (queue is empty, returns null after timeout)
        Map<String, Object> receivedB = queueB.poll(1500, TimeUnit.MILLISECONDS);
        assertThat(receivedB).isNull();
    }
}
