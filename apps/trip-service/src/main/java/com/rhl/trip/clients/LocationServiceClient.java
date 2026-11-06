package com.rhl.trip.clients;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * P0 Internal Client: Queries Location Service for authoritative driver GPS snapshots (FR-RT-008).
 */
@Component
public class LocationServiceClient {

    private static final Logger log = LoggerFactory.getLogger(LocationServiceClient.class);

    private final RestClient restClient;

    @Autowired
    public LocationServiceClient(
            @Value("${location.service.base-url:http://localhost:8082}") String baseUrl,
            @Value("${app.internal-secret:rhl-internal-secret-2026}") String internalSecret,
            @Value("${location.service.timeout-ms:1500}") int timeoutMs
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

    public LocationServiceClient(RestClient restClient) {
        this.restClient = restClient;
    }

    public Optional<DriverLocationSnapshot> getDriverLocation(UUID driverId) {
        if (driverId == null) {
            return Optional.empty();
        }

        try {
            DriverLocationSnapshot response = restClient.get()
                    .uri("/internal/v1/locations/drivers/{driverId}", driverId)
                    .retrieve()
                    .body(DriverLocationSnapshot.class);

            return Optional.ofNullable(response);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) {
                return Optional.empty();
            }
            log.warn("Location service returned {} for driver {}: {}", ex.getStatusCode(), driverId, ex.getMessage());
            return Optional.empty();
        } catch (Exception ex) {
            log.warn("Network error contacting location service for driver {}: {}", driverId, ex.getMessage());
            return Optional.empty();
        }
    }

    public record DriverLocationSnapshot(
            UUID driverId,
            String status,
            Double longitude,
            Double latitude,
            Instant recordedAt,
            UUID sessionId,
            UUID vehicleId
    ) {}
}
