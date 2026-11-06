package com.rhl.realtime.security;

import com.rhl.realtime.services.RealtimeSessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * P0 Security Interceptor: Authenticates STOMP CONNECT using trusted RS256 JWT.
 * Enforces token signature, expiration, issuer, subject UUID validation, and
 * prevents elevation of privilege (ROLE_INTERNAL_SERVICE).
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StompAuthChannelInterceptor.class);

    private final JwtDecoder jwtDecoder;
    private final JwtAuthenticationConverter jwtAuthenticationConverter;
    private final RealtimeSessionRegistry sessionRegistry;

    public StompAuthChannelInterceptor(
            JwtDecoder jwtDecoder,
            JwtAuthenticationConverter jwtAuthenticationConverter,
            RealtimeSessionRegistry sessionRegistry
    ) {
        this.jwtDecoder = Objects.requireNonNull(jwtDecoder, "jwtDecoder is required");
        this.jwtAuthenticationConverter = Objects.requireNonNull(jwtAuthenticationConverter, "jwtAuthenticationConverter is required");
        this.sessionRegistry = Objects.requireNonNull(sessionRegistry, "sessionRegistry is required");
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            accessor = StompHeaderAccessor.wrap(message);
        }

        StompHeaderAccessor workingAccessor = accessor.isMutable() ? accessor : StompHeaderAccessor.wrap(message);

        StompCommand command = workingAccessor.getCommand();
        if (StompCommand.CONNECT.equals(command)) {
            authenticateConnect(workingAccessor);
        } else if (StompCommand.DISCONNECT.equals(command)) {
            handleDisconnect(workingAccessor);
        }

        if (!accessor.isMutable()) {
            return org.springframework.messaging.support.MessageBuilder.createMessage(
                    message.getPayload(),
                    workingAccessor.getMessageHeaders()
            );
        }

        return message;
    }

    private void authenticateConnect(StompHeaderAccessor accessor) {
        String token = extractBearerToken(accessor);
        if (token == null || token.isBlank()) {
            log.warn("STOMP CONNECT rejected: missing Bearer token in headers");
            throw new BadCredentialsException("Missing Bearer token in STOMP CONNECT headers");
        }

        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(token);
        } catch (JwtException e) {
            log.warn("STOMP CONNECT rejected: JWT validation failed: {}", e.getMessage());
            throw new BadCredentialsException("Invalid or expired JWT token: " + e.getMessage(), e);
        }

        UUID userId;
        try {
            userId = UUID.fromString(jwt.getSubject());
        } catch (Exception e) {
            log.warn("STOMP CONNECT rejected: Subject is not a valid UUID: {}", jwt.getSubject());
            throw new BadCredentialsException("Invalid JWT subject: must be a valid UUID");
        }

        Collection<GrantedAuthority> authorities = new ArrayList<>(
                Objects.requireNonNull(jwtAuthenticationConverter.convert(jwt)).getAuthorities()
        );

        // Security invariant: Prevent granting ROLE_INTERNAL_SERVICE via client WebSocket
        boolean hasInternal = authorities.stream()
                .anyMatch(a -> "ROLE_INTERNAL_SERVICE".equals(a.getAuthority()));
        if (hasInternal) {
            log.warn("STOMP CONNECT rejected: Client JWT cannot contain ROLE_INTERNAL_SERVICE");
            throw new BadCredentialsException("Access Denied: ROLE_INTERNAL_SERVICE is not allowed on WebSocket transport");
        }

        JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt, authorities, userId.toString());
        accessor.setUser(authentication);

        Set<String> roleCodes = new HashSet<>();
        List<String> rolesClaim = jwt.getClaimAsStringList("roles");
        if (rolesClaim != null) {
            roleCodes.addAll(rolesClaim);
        }

        sessionRegistry.registerSession(accessor.getSessionId(), userId, roleCodes);
    }

    private void handleDisconnect(StompHeaderAccessor accessor) {
        if (accessor.getSessionId() != null) {
            sessionRegistry.removeSession(accessor.getSessionId());
        }
    }

    private String extractBearerToken(StompHeaderAccessor accessor) {
        String authHeader = accessor.getFirstNativeHeader("Authorization");
        if (authHeader == null || authHeader.isBlank()) {
            authHeader = accessor.getFirstNativeHeader("X-Authorization");
        }

        if (authHeader != null) {
            authHeader = authHeader.trim();
            if (authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
                String token = authHeader.substring(7).trim();
                return token.isEmpty() ? null : token;
            }
        }
        return null;
    }
}
