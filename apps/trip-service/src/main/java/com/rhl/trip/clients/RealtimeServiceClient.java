package com.rhl.trip.clients;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.UUID;

/**
 * P0 Internal Client: Notifies Realtime Service to revoke trip rooms on terminal transition (FR-RT-007).
 */
@Component
public class RealtimeServiceClient {

    private static final Logger log = LoggerFactory.getLogger(RealtimeServiceClient.class);

    private final RestClient restClient;

    @Autowired
    public RealtimeServiceClient(
            @Value("${realtime.service.base-url:http://localhost:8086}") String baseUrl,
            @Value("${app.internal-secret:rhl-internal-secret-2026}") String internalSecret,
            @Value("${realtime.service.timeout-ms:1500}") int timeoutMs
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(timeoutMs));

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("X-Internal-Secret", internalSecret)
                .defaultHeader("X-Caller-Service", "trip-service")
                .build();
    }

    public RealtimeServiceClient(RestClient restClient) {
        this.restClient = restClient;
    }

    public void publishTripStatus(UUID customerId, Object envelope) {
        if (customerId == null || envelope == null) {
            return;
        }

        try {
            restClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/internal/v1/realtime/publish/trip")
                            .queryParam("customerId", customerId)
                            .build())
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(envelope)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Successfully published trip status to realtime service for customerId={}", customerId);
        } catch (Exception ex) {
            log.warn("Failed to publish trip status to realtime service for customerId={}: {}",
                    customerId, ex.getMessage());
            throw new RuntimeException("Error publishing trip status to realtime service", ex);
        }
    }

    public void revokeTripRoom(UUID tripId) {
        if (tripId == null) {
            return;
        }

        try {
            restClient.post()
                    .uri("/internal/v1/realtime/rooms/{tripId}/revoke", tripId)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Notified realtime service of trip room revocation for tripId={}", tripId);
        } catch (Exception ex) {
            log.warn("Non-fatal: Failed to notify realtime service of trip room revocation for tripId={}: {}",
                    tripId, ex.getMessage());
        }
    }
}
