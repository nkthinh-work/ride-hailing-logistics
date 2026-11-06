package com.rhl.realtime.services;

import com.rhl.realtime.models.RealtimeSessionInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages WebSocket/STOMP connection lifecycles in memory.
 * Single-instance in-memory registry (M07 Group 1 baseline).
 */
@Component
public class RealtimeSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(RealtimeSessionRegistry.class);

    private final Map<String, RealtimeSessionInfo> sessionsById = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> sessionIdsByUserId = new ConcurrentHashMap<>();

    public void registerSession(String sessionId, UUID userId, Set<String> roles) {
        RealtimeSessionInfo session = new RealtimeSessionInfo(
                sessionId,
                userId,
                Collections.unmodifiableSet(new HashSet<>(roles)),
                Instant.now()
        );
        sessionsById.put(sessionId, session);
        sessionIdsByUserId.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(sessionId);

        log.info("STOMP session CONNECTED: sessionId={}, userId={}, roles={}", sessionId, userId, roles);
    }

    public void removeSession(String sessionId) {
        RealtimeSessionInfo removed = sessionsById.remove(sessionId);
        if (removed != null) {
            Set<String> userSessions = sessionIdsByUserId.get(removed.userId());
            if (userSessions != null) {
                userSessions.remove(sessionId);
                if (userSessions.isEmpty()) {
                    sessionIdsByUserId.remove(removed.userId());
                }
            }
            log.info("STOMP session DISCONNECTED: sessionId={}, userId={}", sessionId, removed.userId());
        }
    }

    public boolean isUserConnected(UUID userId) {
        Set<String> sessions = sessionIdsByUserId.get(userId);
        return sessions != null && !sessions.isEmpty();
    }

    public int getActiveSessionCount() {
        return sessionsById.size();
    }

    public Set<String> getSessionsForUser(UUID userId) {
        Set<String> sessions = sessionIdsByUserId.get(userId);
        return sessions != null ? Collections.unmodifiableSet(sessions) : Collections.emptySet();
    }

    public Optional<RealtimeSessionInfo> getSession(String sessionId) {
        return Optional.ofNullable(sessionsById.get(sessionId));
    }
}
