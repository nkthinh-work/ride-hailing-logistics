package com.rhl.dispatch.configs;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Endpoint configurations for external microservice clients (Location, Identity, Trip).
 */
@ConfigurationProperties(prefix = "dispatch.services")
public record DispatchServiceEndpoints(
        String locationBaseUrl,
        String identityBaseUrl,
        String tripBaseUrl,
        String internalSecret,
        int connectTimeoutMs,
        int readTimeoutMs
) {
    public DispatchServiceEndpoints {
        if (locationBaseUrl == null || locationBaseUrl.isBlank()) {
            locationBaseUrl = "http://localhost:8082";
        }
        if (identityBaseUrl == null || identityBaseUrl.isBlank()) {
            identityBaseUrl = "http://localhost:8081";
        }
        if (tripBaseUrl == null || tripBaseUrl.isBlank()) {
            tripBaseUrl = "http://localhost:8084";
        }
        if (internalSecret == null || internalSecret.isBlank()) {
            internalSecret = "rhl-internal-secret-2026";
        }
        if (connectTimeoutMs <= 0) {
            connectTimeoutMs = 2000;
        }
        if (readTimeoutMs <= 0) {
            readTimeoutMs = 3000;
        }
    }

    public DispatchServiceEndpoints() {
        this("http://localhost:8082", "http://localhost:8081", "http://localhost:8084", "rhl-internal-secret-2026", 2000, 3000);
    }
}
