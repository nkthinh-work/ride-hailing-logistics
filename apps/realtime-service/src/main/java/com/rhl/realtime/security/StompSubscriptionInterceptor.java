package com.rhl.realtime.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.Objects;
import java.util.UUID;
import com.rhl.realtime.services.TripRoomAuthorizationService;

/**
 * P0 Security Interceptor: Enforces destination-level authorization on STOMP SUBSCRIBE and SEND.
 * Protects private driver offer queues, customer trip status queues, and dynamic trip rooms (/topic/trips.{tripId}.location)
 * from unauthorized eavesdropping and prevents clients from publishing unauthorized system events.
 */
@Component
public class StompSubscriptionInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StompSubscriptionInterceptor.class);

    private final TripRoomAuthorizationService tripRoomAuthorizationService;

    public StompSubscriptionInterceptor(TripRoomAuthorizationService tripRoomAuthorizationService) {
        this.tripRoomAuthorizationService = Objects.requireNonNull(tripRoomAuthorizationService, "tripRoomAuthorizationService is required");
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            accessor = StompHeaderAccessor.wrap(message);
        }

        StompCommand command = accessor.getCommand();
        if (StompCommand.SUBSCRIBE.equals(command)) {
            authorizeSubscription(accessor);
        } else if (StompCommand.SEND.equals(command)) {
            authorizeSend(accessor);
        }

        return message;
    }

    private void authorizeSubscription(StompHeaderAccessor accessor) {
        Principal userPrincipal = accessor.getUser();
        if (userPrincipal == null || !(userPrincipal instanceof Authentication auth) || !auth.isAuthenticated()) {
            log.warn("STOMP SUBSCRIBE rejected: unauthenticated session");
            throw new AccessDeniedException("Unauthenticated subscription is forbidden");
        }

        String destination = accessor.getDestination();
        if (destination == null || destination.isBlank()) {
            throw new AccessDeniedException("Subscription destination cannot be blank");
        }

        log.debug("Authorizing STOMP SUBSCRIBE: user={}, destination={}, authorities={}",
                auth.getName(), destination, auth.getAuthorities());

        // Rule 1: Reject direct subscription to raw broker queues (e.g., /queue/offers-user123)
        if (destination.startsWith("/queue/")) {
            log.warn("STOMP SUBSCRIBE rejected: direct /queue/ access blocked for user {}", auth.getName());
            throw new AccessDeniedException("Direct subscription to broker queue is forbidden; use /user/queue/...");
        }

        // Rule 2: Dynamic Trip Rooms (/topic/trips.{tripId}.location)
        if (destination.startsWith("/topic/")) {
            if (tripRoomAuthorizationService.isTripRoomDestination(destination)) {
                UUID userId = UUID.fromString(auth.getName());
                tripRoomAuthorizationService.authorizeSubscription(userId, destination);
                return;
            }
            log.warn("STOMP SUBSCRIBE rejected: public topic access blocked for user {}: {}", auth.getName(), destination);
            throw new AccessDeniedException("Subscription to public topic is forbidden");
        }

        // Rule 3: Protected User Destinations
        if (destination.startsWith("/user/queue/offers") || destination.startsWith("/user/queue/offer-updates")) {
            if (!hasRole(auth, "ROLE_DRIVER")) {
                log.warn("STOMP SUBSCRIBE rejected: user {} lacks ROLE_DRIVER for {}", auth.getName(), destination);
                throw new AccessDeniedException("Access Denied: Only DRIVER can subscribe to driver offer channels");
            }
            return;
        }

        if (destination.startsWith("/user/queue/trips")) {
            if (!hasRole(auth, "ROLE_CUSTOMER")) {
                log.warn("STOMP SUBSCRIBE rejected: user {} lacks ROLE_CUSTOMER for {}", auth.getName(), destination);
                throw new AccessDeniedException("Access Denied: Only CUSTOMER can subscribe to customer trip channels");
            }
            return;
        }

        if (destination.startsWith("/user/queue/notifications")) {
            if (!hasRole(auth, "ROLE_DRIVER") && !hasRole(auth, "ROLE_CUSTOMER") && !hasRole(auth, "ROLE_ADMINISTRATOR")) {
                log.warn("STOMP SUBSCRIBE rejected: user {} lacks notification role for {}", auth.getName(), destination);
                throw new AccessDeniedException("Access Denied: Unauthorized to subscribe to notifications");
            }
            return;
        }

        // Any other unrecognized destination is rejected (fail-closed)
        log.warn("STOMP SUBSCRIBE rejected: unrecognized destination {} for user {}", destination, auth.getName());
        throw new AccessDeniedException("Access Denied: Destination is not permitted: " + destination);
    }

    private void authorizeSend(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        log.warn("STOMP SEND rejected: client attempted to publish to destination {}", destination);
        throw new AccessDeniedException("Client publishing is forbidden in Realtime Transport Foundation");
    }

    private boolean hasRole(Authentication auth, String roleName) {
        for (GrantedAuthority authority : auth.getAuthorities()) {
            if (roleName.equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
