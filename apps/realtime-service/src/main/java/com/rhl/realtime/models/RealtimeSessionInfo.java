package com.rhl.realtime.models;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Tracks an active WebSocket session in memory.
 */
public record RealtimeSessionInfo(
        String sessionId,
        UUID userId,
        Set<String> roles,
        Instant connectedAt
) {
}
