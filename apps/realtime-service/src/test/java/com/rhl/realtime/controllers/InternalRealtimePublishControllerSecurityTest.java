package com.rhl.realtime.controllers;

import com.rhl.realtime.configs.HttpSecurityConfig;
import com.rhl.realtime.models.DriverOfferPayload;
import com.rhl.realtime.models.EventEnvelope;
import com.rhl.realtime.models.RealtimeEventType;
import com.rhl.realtime.services.RealtimeEventPublisher;
import com.rhl.realtime.services.RealtimeSessionRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = InternalRealtimePublishController.class)
@Import(HttpSecurityConfig.class)
@DisplayName("InternalRealtimePublishController M2M Security Tests")
class InternalRealtimePublishControllerSecurityTest {

    private static final String INTERNAL_SECRET = "rhl-internal-secret-2026";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private RealtimeEventPublisher realtimeEventPublisher;

    @MockitoBean
    private RealtimeSessionRegistry sessionRegistry;

    @MockitoBean
    private com.rhl.realtime.services.TripRoomAuthorizationService tripRoomAuthorizationService;

    @MockitoBean
    private com.rhl.realtime.services.TripMembershipProvider membershipProvider;

    @Test
    @DisplayName("POST /internal/v1/realtime/publish/offer with valid X-Internal-Secret succeeds (200 OK)")
    void publishOffer_validSecret_succeeds() throws Exception {
        UUID driverId = UUID.randomUUID();
        UUID offerId = UUID.randomUUID();
        UUID tripId = UUID.randomUUID();

        DriverOfferPayload payload = new DriverOfferPayload(
                offerId,
                tripId,
                driverId,
                "RIDE",
                21.0285,
                105.8542,
                "Hoan Kiem, Hanoi",
                "Cau Giay, Hanoi",
                5.2,
                BigDecimal.valueOf(55000),
                Instant.now(),
                Instant.now().plusSeconds(15),
                "PENDING"
        );

        EventEnvelope<DriverOfferPayload> envelope = EventEnvelope.of(
                RealtimeEventType.DRIVER_OFFER_CREATED,
                offerId,
                "corr-123",
                payload
        );

        when(sessionRegistry.isUserConnected(driverId)).thenReturn(true);

        mockMvc.perform(post("/internal/v1/realtime/publish/offer")
                        .header("X-Internal-Secret", INTERNAL_SECRET)
                        .header("X-Caller-Service", "dispatch-service")
                        .param("driverId", driverId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(envelope)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.recipientOnline").value(true));

        verify(realtimeEventPublisher).sendOfferToDriver(driverId, envelope);
    }

    @Test
    @DisplayName("POST /internal/v1/realtime/publish/offer without X-Internal-Secret is FORBIDDEN (403)")
    void publishOffer_missingSecret_forbidden() throws Exception {
        UUID driverId = UUID.randomUUID();
        UUID offerId = UUID.randomUUID();

        DriverOfferPayload payload = new DriverOfferPayload(
                offerId,
                UUID.randomUUID(),
                driverId,
                "RIDE",
                21.0285,
                105.8542,
                "Hoan Kiem",
                "Cau Giay",
                5.0,
                BigDecimal.valueOf(50000),
                Instant.now(),
                Instant.now().plusSeconds(15),
                "PENDING"
        );

        EventEnvelope<DriverOfferPayload> envelope = EventEnvelope.of(
                RealtimeEventType.DRIVER_OFFER_CREATED,
                offerId,
                "corr-no-sec",
                payload
        );

        mockMvc.perform(post("/internal/v1/realtime/publish/offer")
                        .param("driverId", driverId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(envelope)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /internal/v1/realtime/sessions/{userId} with invalid secret is FORBIDDEN (403)")
    void getSession_wrongSecret_forbidden() throws Exception {
        UUID userId = UUID.randomUUID();
        mockMvc.perform(get("/internal/v1/realtime/sessions/" + userId)
                        .header("X-Internal-Secret", "wrong-secret"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /internal/v1/realtime/publish/trip-location from location-service succeeds (200 DELIVERED)")
    void publishTripLocation_validLocationCaller_succeeds() throws Exception {
        UUID tripId = UUID.randomUUID();
        UUID driverId = UUID.randomUUID();

        when(tripRoomAuthorizationService.canDeliverLocation(tripId, driverId)).thenReturn(true);

        com.rhl.realtime.dtos.PublishTripLocationRequest request = new com.rhl.realtime.dtos.PublishTripLocationRequest(
                tripId, driverId, 21.0285, 105.8542, 90.0, 15.0, 101L, Instant.now()
        );

        mockMvc.perform(post("/internal/v1/realtime/publish/trip-location")
                        .header("X-Internal-Secret", INTERNAL_SECRET)
                        .header("X-Caller-Service", "location-service")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.tripId").value(tripId.toString()))
                .andExpect(jsonPath("$.room").value("/topic/trips." + tripId + ".location"));

        verify(realtimeEventPublisher).broadcastTripLocation(eq(tripId), any());
    }

    @Test
    @DisplayName("POST /internal/v1/realtime/publish/trip-location from dispatch-service is FORBIDDEN (403)")
    void publishTripLocation_invalidCaller_forbidden() throws Exception {
        UUID tripId = UUID.randomUUID();
        UUID driverId = UUID.randomUUID();

        com.rhl.realtime.dtos.PublishTripLocationRequest request = new com.rhl.realtime.dtos.PublishTripLocationRequest(
                tripId, driverId, 21.0285, 105.8542, 90.0, 15.0, 101L, Instant.now()
        );

        mockMvc.perform(post("/internal/v1/realtime/publish/trip-location")
                        .header("X-Internal-Secret", INTERNAL_SECRET)
                        .header("X-Caller-Service", "dispatch-service")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /internal/v1/realtime/publish/trip-location when trip is non-streamable drops delivery (DROPPED_REVOKED)")
    void publishTripLocation_revokedState_dropped() throws Exception {
        UUID tripId = UUID.randomUUID();
        UUID driverId = UUID.randomUUID();

        when(tripRoomAuthorizationService.canDeliverLocation(tripId, driverId)).thenReturn(false);

        com.rhl.realtime.dtos.PublishTripLocationRequest request = new com.rhl.realtime.dtos.PublishTripLocationRequest(
                tripId, driverId, 21.0285, 105.8542, 90.0, 15.0, 101L, Instant.now()
        );

        mockMvc.perform(post("/internal/v1/realtime/publish/trip-location")
                        .header("X-Internal-Secret", INTERNAL_SECRET)
                        .header("X-Caller-Service", "location-service")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DROPPED_REVOKED"));
    }

    @Test
    @DisplayName("POST /internal/v1/realtime/publish/driver-location from location-service resolves active trip and broadcasts")
    void publishDriverLocation_validLocationCaller_succeeds() throws Exception {
        UUID tripId = UUID.randomUUID();
        UUID driverId = UUID.randomUUID();

        when(membershipProvider.findActiveTripByDriverId(driverId)).thenReturn(java.util.Optional.of(tripId));
        when(tripRoomAuthorizationService.canDeliverLocation(tripId, driverId)).thenReturn(true);

        com.rhl.realtime.dtos.PublishDriverLocationRequest request = new com.rhl.realtime.dtos.PublishDriverLocationRequest(
                driverId, 21.0285, 105.8542, 90.0, 15.0, 102L, UUID.randomUUID(), Instant.now()
        );

        mockMvc.perform(post("/internal/v1/realtime/publish/driver-location")
                        .header("X-Internal-Secret", INTERNAL_SECRET)
                        .header("X-Caller-Service", "location-service")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.tripId").value(tripId.toString()));

        verify(realtimeEventPublisher).broadcastTripLocation(eq(tripId), any());
    }

    @Test
    @DisplayName("POST /internal/v1/realtime/publish/trip from trip-service succeeds (200 DELIVERED)")
    void publishTrip_validTripCaller_succeeds() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID tripId = UUID.randomUUID();

        com.rhl.realtime.models.TripStatusPayload payload = new com.rhl.realtime.models.TripStatusPayload(
                tripId, customerId, UUID.randomUUID(), "MATCHING", "SYSTEM", "Trip created", Instant.now(), 1L
        );
        EventEnvelope<com.rhl.realtime.models.TripStatusPayload> envelope = EventEnvelope.of(
                RealtimeEventType.TRIP_STATUS_CHANGED,
                UUID.randomUUID(),
                "corr-trip-test",
                payload
        );

        when(sessionRegistry.isUserConnected(customerId)).thenReturn(true);

        mockMvc.perform(post("/internal/v1/realtime/publish/trip")
                        .header("X-Internal-Secret", INTERNAL_SECRET)
                        .header("X-Caller-Service", "trip-service")
                        .param("customerId", customerId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(envelope)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.recipientId").value(customerId.toString()));

        verify(realtimeEventPublisher).sendTripUpdateToCustomer(customerId, envelope);
    }

    @Test
    @DisplayName("POST /internal/v1/realtime/publish/offer with missing X-Caller-Service is FORBIDDEN (403)")
    void publishOffer_missingCallerService_forbidden() throws Exception {
        UUID driverId = UUID.randomUUID();
        UUID offerId = UUID.randomUUID();

        DriverOfferPayload payload = new DriverOfferPayload(
                offerId, UUID.randomUUID(), driverId, "RIDE",
                21.0285, 105.8542, "A", "B", 5.0,
                BigDecimal.valueOf(50000), Instant.now(), Instant.now().plusSeconds(15), "PENDING"
        );
        EventEnvelope<DriverOfferPayload> envelope = EventEnvelope.of(
                RealtimeEventType.DRIVER_OFFER_CREATED, offerId, "corr-missing-caller", payload
        );

        mockMvc.perform(post("/internal/v1/realtime/publish/offer")
                        .header("X-Internal-Secret", INTERNAL_SECRET)
                        .param("driverId", driverId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(envelope)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /internal/v1/realtime/publish/offer with duplicate eventId returns ALREADY_DELIVERED (idempotent)")
    void publishOffer_duplicateEventId_returnsAlreadyDelivered() throws Exception {
        UUID driverId = UUID.randomUUID();
        UUID offerId = UUID.randomUUID();
        UUID duplicateEventId = UUID.randomUUID();

        DriverOfferPayload payload = new DriverOfferPayload(
                offerId, UUID.randomUUID(), driverId, "RIDE",
                21.0285, 105.8542, "A", "B", 5.0,
                BigDecimal.valueOf(50000), Instant.now(), Instant.now().plusSeconds(15), "PENDING"
        );
        EventEnvelope<DriverOfferPayload> envelope = new EventEnvelope<>(
                duplicateEventId,
                RealtimeEventType.DRIVER_OFFER_CREATED,
                1,
                Instant.now(),
                "corr-dup",
                offerId,
                payload
        );

        when(sessionRegistry.isUserConnected(driverId)).thenReturn(true);

        // First delivery -> DELIVERED
        mockMvc.perform(post("/internal/v1/realtime/publish/offer")
                        .header("X-Internal-Secret", INTERNAL_SECRET)
                        .header("X-Caller-Service", "dispatch-service")
                        .param("driverId", driverId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(envelope)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));

        // Second delivery -> ALREADY_DELIVERED
        mockMvc.perform(post("/internal/v1/realtime/publish/offer")
                        .header("X-Internal-Secret", INTERNAL_SECRET)
                        .header("X-Caller-Service", "dispatch-service")
                        .param("driverId", driverId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(envelope)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ALREADY_DELIVERED"))
                .andExpect(jsonPath("$.eventId").value(duplicateEventId.toString()));
    }

    @Test
    @DisplayName("POST /internal/v1/realtime/rooms/{tripId}/revoke from trip-service invalidates cached room")
    void revokeTripRoom_validTripCaller_succeeds() throws Exception {
        UUID tripId = UUID.randomUUID();

        mockMvc.perform(post("/internal/v1/realtime/rooms/{tripId}/revoke", tripId)
                        .header("X-Internal-Secret", INTERNAL_SECRET)
                        .header("X-Caller-Service", "trip-service"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.tripId").value(tripId.toString()));

        verify(membershipProvider).invalidateTrip(tripId);
    }
}
