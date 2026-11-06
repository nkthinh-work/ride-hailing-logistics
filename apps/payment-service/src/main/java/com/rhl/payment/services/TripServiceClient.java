package com.rhl.payment.services;

import com.rhl.payment.dtos.TripInternalResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Internal Client communicating with Trip Service (:8084) to fetch authoritative trip status,
 * customer ownership, and immutable monetary payable amounts (DR-004, FR-PAY-001..003).
 */
@Service
public class TripServiceClient {

    private static final Logger log = LoggerFactory.getLogger(TripServiceClient.class);

    private final RestClient restClient;

    @Autowired
    public TripServiceClient(
            @Value("${trip.service.base-url:http://localhost:8084}") String baseUrl,
            @Value("${trip.service.internal-secret:rhl-internal-secret-2026}") String internalSecret,
            @Value("${trip.service.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${trip.service.read-timeout-ms:3000}") int readTimeoutMs
    ) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader("X-Internal-Secret", internalSecret)
                .defaultHeader("X-Caller-Service", "payment-service")
                .build();
    }

    public TripServiceClient(RestClient restClient) {
        this.restClient = restClient;
    }

    public Optional<TripInternalResponse> getTrip(UUID tripId) {
        if (tripId == null) {
            return Optional.empty();
        }

        try {
            TripInternalResponse response = restClient.get()
                    .uri("/internal/v1/trips/{tripId}", tripId)
                    .retrieve()
                    .body(TripInternalResponse.class);

            return Optional.ofNullable(response);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                log.info("Trip {} not found in Trip Service", tripId);
                return Optional.empty();
            }
            log.error("Trip Service returned HTTP {} for trip {}: {}", e.getStatusCode(), tripId, e.getResponseBodyAsString());
            throw new RuntimeException("Trip service error: " + e.getStatusCode(), e);
        } catch (Exception e) {
            log.error("Failed to connect to Trip Service for trip {}: {}", tripId, e.getMessage());
            throw new RuntimeException("Trip service unavailable: " + e.getMessage(), e);
        }
    }
}
