package com.rhl.realtime.configs;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "realtime.websocket")
public record RealtimeProperties(
        String endpoint,
        String allowedOriginPatterns,
        long heartbeatOutgoingMs,
        long heartbeatIncomingMs
) {
    public RealtimeProperties {
        if (endpoint == null || endpoint.isBlank()) {
            endpoint = "/ws";
        }
        if (allowedOriginPatterns == null || allowedOriginPatterns.isBlank()) {
            allowedOriginPatterns = "*";
        }
        if (heartbeatOutgoingMs <= 0) {
            heartbeatOutgoingMs = 10000;
        }
        if (heartbeatIncomingMs <= 0) {
            heartbeatIncomingMs = 10000;
        }
    }
}
