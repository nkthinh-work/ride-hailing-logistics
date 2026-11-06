package com.rhl.realtime.services;

import com.rhl.realtime.models.TripMembershipInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P0 Security Service: Authorizes STOMP SUBSCRIBE requests to dynamic Trip Rooms (/topic/trips.{tripId}.location).
 * Enforces authoritative trip ownership, assigned driver membership, active streamable state,
 * and completely prevents eavesdropping, wildcards, and unauthorized public broadcast listening.
 */
@Service
public class TripRoomAuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(TripRoomAuthorizationService.class);

    // Strictly matches: /topic/trips.<UUID>.location
    private static final Pattern TRIP_LOCATION_DEST_PATTERN = Pattern.compile(
            "^/topic/trips\\.([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\\.location$"
    );

    private final TripMembershipProvider membershipProvider;

    public TripRoomAuthorizationService(TripMembershipProvider membershipProvider) {
        this.membershipProvider = Objects.requireNonNull(membershipProvider, "membershipProvider is required");
    }

    public boolean isTripRoomDestination(String destination) {
        if (destination == null) {
            return false;
        }
        return destination.startsWith("/topic/trips.");
    }

    public UUID extractTripIdFromDestination(String destination) {
        if (destination == null) {
            return null;
        }
        Matcher matcher = TRIP_LOCATION_DEST_PATTERN.matcher(destination);
        if (matcher.matches()) {
            return UUID.fromString(matcher.group(1));
        }
        return null;
    }

    public void authorizeSubscription(UUID userId, String destination) {
        Objects.requireNonNull(userId, "userId is required");
        if (destination == null || destination.isBlank()) {
            throw new AccessDeniedException("Destination cannot be blank");
        }

        UUID tripId = extractTripIdFromDestination(destination);
        if (tripId == null) {
            log.warn("STOMP SUBSCRIBE rejected: Malformed trip room format or wildcard destination: {}", destination);
            throw new AccessDeniedException("Access Denied: Malformed trip room destination or wildcard subscription forbidden: " + destination);
        }

        TripMembershipInfo info = membershipProvider.getTripMembership(tripId)
                .orElseThrow(() -> {
                    log.warn("STOMP SUBSCRIBE rejected: Trip {} not found for user {}", tripId, userId);
                    return new AccessDeniedException("Access Denied: Trip not found: " + tripId);
                });

        if (!info.isMember(userId)) {
            log.warn("STOMP SUBSCRIBE rejected: User {} is neither Customer ({}) nor Driver ({}) for trip {}",
                    userId, info.customerId(), info.driverId(), tripId);
            throw new AccessDeniedException("Access Denied: You are not an authorized member of trip " + tripId);
        }

        if (!info.isStreamable()) {
            log.warn("STOMP SUBSCRIBE rejected: Trip {} is in non-streamable status {} for user {}",
                    tripId, info.status(), userId);
            throw new AccessDeniedException("Access Denied: Trip " + tripId + " is not active for live tracking (status: " + info.status() + ")");
        }

        log.info("Authorized STOMP SUBSCRIBE: user {} joined trip room {}", userId, destination);
    }

    public boolean canDeliverLocation(UUID tripId, UUID driverId) {
        if (tripId == null || driverId == null) {
            return false;
        }
        Optional<TripMembershipInfo> optInfo = membershipProvider.getTripMembership(tripId);
        if (optInfo.isEmpty()) {
            return false;
        }
        TripMembershipInfo info = optInfo.get();
        if (!info.isStreamable()) {
            return false;
        }
        return info.driverId() != null && info.driverId().equals(driverId);
    }
}
