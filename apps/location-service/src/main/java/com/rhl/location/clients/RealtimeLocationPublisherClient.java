package com.rhl.location.clients;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * P0 Internal Client: Forwards validated and committed driver GPS telemetry to Realtime Service.
 * Fails safely without rolling back authoritative location in Redis if Realtime Service is unavailable.
 */
@Component
public class RealtimeLocationPublisherClient {

    private static final Logger log = LoggerFactory.getLogger(RealtimeLocationPublisherClient.class);

    private final RestClient restClient;

    @Autowired
    public RealtimeLocationPublisherClient(
            @Value("${realtime.service.base-url:http://localhost:8086}") String baseUrl,
            @Value("${realtime.service.internal-secret:rhl-internal-secret-2026}") String internalSecret,
            @Value("${realtime.service.timeout-ms:1000}") int timeoutMs
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(timeoutMs));

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("X-Internal-Secret", internalSecret)
                .defaultHeader("X-Caller-Service", "location-service")
                .build();
    }

    public RealtimeLocationPublisherClient(RestClient restClient) {
        this.restClient = restClient;
    }

    public void publishDriverLocation(
            UUID driverId,
            double latitude,
            double longitude,
            Double headingDegrees,
            Double speedMetersPerSecond,
            Long sequenceNumber,
            UUID sessionId,
            Instant observedAt
    ) {
        try {
            Map<String, Object> body = Map.of(
                    "driverId", driverId.toString(),
                    "latitude", latitude,
                    "longitude", longitude,
                    "sequenceNumber", sequenceNumber != null ? sequenceNumber : 0L,
                    "sessionId", sessionId != null ? sessionId.toString() : "",
                    "observedAt", observedAt != null ? observedAt.toString() : Instant.now().toString()
            );

            restClient.post()
                    .uri("/internal/v1/realtime/publish/driver-location")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception ex) {
            log.warn("Non-fatal: Failed to forward committed GPS telemetry to realtime service for driver {}: {}",
                    driverId, ex.getMessage());
        }
    }
}
