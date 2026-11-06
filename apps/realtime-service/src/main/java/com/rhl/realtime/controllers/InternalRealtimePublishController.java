package com.rhl.realtime.controllers;

import com.rhl.realtime.dtos.PublishDriverLocationRequest;
import com.rhl.realtime.dtos.PublishTripLocationRequest;
import com.rhl.realtime.models.*;
import com.rhl.realtime.services.RealtimeEventPublisher;
import com.rhl.realtime.services.RealtimeSessionRegistry;
import com.rhl.realtime.services.TripMembershipProvider;
import com.rhl.realtime.services.TripRoomAuthorizationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/internal/v1/realtime")
@PreAuthorize("hasRole('INTERNAL_SERVICE')")
public class InternalRealtimePublishController {

    private final RealtimeEventPublisher realtimeEventPublisher;
    private final RealtimeSessionRegistry sessionRegistry;
    private final TripRoomAuthorizationService tripRoomAuthorizationService;
    private final TripMembershipProvider membershipProvider;

    public InternalRealtimePublishController(
            RealtimeEventPublisher realtimeEventPublisher,
            RealtimeSessionRegistry sessionRegistry,
            TripRoomAuthorizationService tripRoomAuthorizationService,
            TripMembershipProvider membershipProvider
    ) {
        this.realtimeEventPublisher = Objects.requireNonNull(realtimeEventPublisher, "realtimeEventPublisher is required");
        this.sessionRegistry = Objects.requireNonNull(sessionRegistry, "sessionRegistry is required");
        this.tripRoomAuthorizationService = Objects.requireNonNull(tripRoomAuthorizationService, "tripRoomAuthorizationService is required");
        this.membershipProvider = Objects.requireNonNull(membershipProvider, "membershipProvider is required");
    }

    private final Map<UUID, Instant> processedEvents = new java.util.concurrent.ConcurrentHashMap<>();

    private boolean isDuplicateEvent(UUID eventId) {
        if (eventId == null) return false;
        Instant now = Instant.now();
        if (processedEvents.size() > 5000) {
            Instant cutoff = now.minusSeconds(600);
            processedEvents.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
        }
        return processedEvents.putIfAbsent(eventId, now) != null;
    }

    @PostMapping("/publish/offer")
    public ResponseEntity<Map<String, Object>> publishDriverOffer(
            @RequestParam UUID driverId,
            @RequestHeader(value = "X-Caller-Service", required = false) String callerService,
            @Valid @RequestBody EventEnvelope<DriverOfferPayload> offerEvent
    ) {
        verifyCallerService(callerService, "dispatch-service");
        if (isDuplicateEvent(offerEvent.eventId())) {
            return ResponseEntity.ok(Map.of(
                    "status", "ALREADY_DELIVERED",
                    "recipientId", driverId,
                    "eventId", offerEvent.eventId()
            ));
        }
        realtimeEventPublisher.sendOfferToDriver(driverId, offerEvent);
        boolean isConnected = sessionRegistry.isUserConnected(driverId);
        return ResponseEntity.ok(Map.of(
                "status", "DELIVERED",
                "recipientId", driverId,
                "recipientOnline", isConnected
        ));
    }

    @PostMapping("/publish/trip")
    public ResponseEntity<Map<String, Object>> publishTripStatus(
            @RequestParam UUID customerId,
            @RequestHeader(value = "X-Caller-Service", required = false) String callerService,
            @Valid @RequestBody EventEnvelope<TripStatusPayload> tripEvent
    ) {
        verifyCallerService(callerService, "trip-service");
        if (isDuplicateEvent(tripEvent.eventId())) {
            return ResponseEntity.ok(Map.of(
                    "status", "ALREADY_DELIVERED",
                    "recipientId", customerId,
                    "eventId", tripEvent.eventId()
            ));
        }
        realtimeEventPublisher.sendTripUpdateToCustomer(customerId, tripEvent);
        boolean isConnected = sessionRegistry.isUserConnected(customerId);
        return ResponseEntity.ok(Map.of(
                "status", "DELIVERED",
                "recipientId", customerId,
                "recipientOnline", isConnected
        ));
    }

    @PostMapping("/publish/trip-location")
    public ResponseEntity<Map<String, Object>> publishTripLocation(
            @RequestHeader(value = "X-Caller-Service", required = false) String callerService,
            @Valid @RequestBody PublishTripLocationRequest request
    ) {
        verifyCallerService(callerService, "location-service");

        if (!tripRoomAuthorizationService.canDeliverLocation(request.tripId(), request.driverId())) {
            return ResponseEntity.ok(Map.of(
                    "status", "DROPPED_REVOKED",
                    "tripId", request.tripId(),
                    "reason", "Trip is not active or driver is not assigned"
            ));
        }

        TripLocationPayload payload = new TripLocationPayload(
                request.tripId(),
                request.driverId(),
                request.latitude(),
                request.longitude(),
                request.headingDegrees(),
                request.speedMetersPerSecond(),
                request.sequenceNumber(),
                request.observedAt() != null ? request.observedAt() : Instant.now()
        );

        EventEnvelope<TripLocationPayload> envelope = EventEnvelope.of(
                RealtimeEventType.TRIP_DRIVER_LOCATION_UPDATED,
                UUID.randomUUID(),
                "corr-loc-" + request.tripId(),
                payload
        );

        realtimeEventPublisher.broadcastTripLocation(request.tripId(), envelope);

        return ResponseEntity.ok(Map.of(
                "status", "DELIVERED",
                "tripId", request.tripId(),
                "room", "/topic/trips." + request.tripId() + ".location"
        ));
    }

    @PostMapping("/publish/driver-location")
    public ResponseEntity<Map<String, Object>> publishDriverLocation(
            @RequestHeader(value = "X-Caller-Service", required = false) String callerService,
            @Valid @RequestBody PublishDriverLocationRequest request
    ) {
        verifyCallerService(callerService, "location-service");

        Optional<UUID> optTripId = membershipProvider.findActiveTripByDriverId(request.driverId());
        if (optTripId.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "status", "IGNORED_NO_ACTIVE_TRIP",
                    "driverId", request.driverId()
            ));
        }

        UUID tripId = optTripId.get();
        if (!tripRoomAuthorizationService.canDeliverLocation(tripId, request.driverId())) {
            return ResponseEntity.ok(Map.of(
                    "status", "DROPPED_REVOKED",
                    "tripId", tripId,
                    "driverId", request.driverId()
            ));
        }

        TripLocationPayload payload = new TripLocationPayload(
                tripId,
                request.driverId(),
                request.latitude(),
                request.longitude(),
                request.headingDegrees(),
                request.speedMetersPerSecond(),
                request.sequenceNumber(),
                request.observedAt() != null ? request.observedAt() : Instant.now()
        );

        EventEnvelope<TripLocationPayload> envelope = EventEnvelope.of(
                RealtimeEventType.TRIP_DRIVER_LOCATION_UPDATED,
                UUID.randomUUID(),
                "corr-loc-" + tripId,
                payload
        );

        realtimeEventPublisher.broadcastTripLocation(tripId, envelope);

        return ResponseEntity.ok(Map.of(
                "status", "DELIVERED",
                "tripId", tripId,
                "driverId", request.driverId(),
                "room", "/topic/trips." + tripId + ".location"
        ));
    }

    @PostMapping("/rooms/{tripId}/revoke")
    public ResponseEntity<Map<String, Object>> revokeTripRoom(
            @PathVariable UUID tripId,
            @RequestHeader(value = "X-Caller-Service", required = false) String callerService
    ) {
        verifyCallerService(callerService, "trip-service");
        membershipProvider.invalidateTrip(tripId);
        return ResponseEntity.ok(Map.of(
                "status", "REVOKED",
                "tripId", tripId
        ));
    }

    @GetMapping("/sessions/{userId}")
    public ResponseEntity<Map<String, Object>> getUserSessionStatus(@PathVariable UUID userId) {
        boolean isConnected = sessionRegistry.isUserConnected(userId);
        Set<String> sessions = sessionRegistry.getSessionsForUser(userId);
        return ResponseEntity.ok(Map.of(
                "userId", userId,
                "connected", isConnected,
                "sessionCount", sessions.size(),
                "sessions", sessions
        ));
    }

    private void verifyCallerService(String actualCaller, String expectedCaller) {
        if (actualCaller == null || actualCaller.isBlank()) {
            throw new AccessDeniedException("Missing required X-Caller-Service header");
        }
        if (!expectedCaller.equalsIgnoreCase(actualCaller.trim())) {
            throw new AccessDeniedException(
                    "Caller service '" + actualCaller + "' is not authorized to invoke this publishing endpoint (expected: " + expectedCaller + ")"
            );
        }
    }
}
