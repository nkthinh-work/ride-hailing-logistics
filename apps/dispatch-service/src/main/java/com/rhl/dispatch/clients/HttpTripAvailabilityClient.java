package com.rhl.dispatch.clients;

import com.rhl.dispatch.configs.DispatchServiceEndpoints;
import com.rhl.dispatch.exceptions.TripServiceUnavailableException;
import com.rhl.dispatch.providers.DriverAvailabilityProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * REST client implementing DriverAvailabilityProvider for Trip Service communication (FR-MAT-005, FR-DRV-009).
 */
@Component
public class HttpTripAvailabilityClient implements DriverAvailabilityProvider {

    private static final Logger log = LoggerFactory.getLogger(HttpTripAvailabilityClient.class);

    private final RestClient restClient;
    private final String internalSecret;

    @Autowired
    public HttpTripAvailabilityClient(DispatchServiceEndpoints endpoints) {
        this.internalSecret = endpoints.internalSecret();

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(endpoints.connectTimeoutMs()));
        requestFactory.setReadTimeout(Duration.ofMillis(endpoints.readTimeoutMs()));

        this.restClient = RestClient.builder()
                .baseUrl(endpoints.tripBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    public HttpTripAvailabilityClient(RestClient restClient, String internalSecret) {
        this.restClient = restClient;
        this.internalSecret = internalSecret;
    }

    @Override
    public boolean isDriverBusy(UUID driverId) {
        try {
            DriverBusyResponse response = restClient.get()
                    .uri("/internal/v1/trips/drivers/{driverId}/busy", driverId)
                    .header("X-Internal-Secret", internalSecret)
                    .retrieve()
                    .body(DriverBusyResponse.class);

            return response != null && response.busy();

        } catch (ResourceAccessException ex) {
            log.error("Network or timeout failure contacting Trip Service for driver {}: {}", driverId, ex.getMessage());
            throw new TripServiceUnavailableException("Trip service unreachable or timed out", ex);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) {
                return false;
            }
            log.error("Trip service returned status {}: {}", ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new TripServiceUnavailableException("Trip service returned error: " + ex.getStatusCode(), ex);
        } catch (Exception ex) {
            log.error("Unexpected error contacting Trip Service for driver {}: {}", driverId, ex.getMessage(), ex);
            throw new TripServiceUnavailableException("Trip service communication failure: " + ex.getMessage(), ex);
        }
    }

    @Override
    public Set<UUID> findBusyDrivers(List<UUID> driverIds) {
        if (driverIds == null || driverIds.isEmpty()) {
            return Set.of();
        }

        try {
            UUID[] busyDriverIds = restClient.post()
                    .uri("/internal/v1/trips/drivers/busy")
                    .header("X-Internal-Secret", internalSecret)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new BatchDriverBusyRequest(driverIds))
                    .retrieve()
                    .body(UUID[].class);

            if (busyDriverIds == null || busyDriverIds.length == 0) {
                return Set.of();
            }

            return new HashSet<>(Arrays.asList(busyDriverIds));

        } catch (ResourceAccessException ex) {
            log.error("Network or timeout failure contacting Trip Service during batch availability check: {}", ex.getMessage());
            throw new TripServiceUnavailableException("Trip service unreachable or timed out during availability check", ex);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) {
                // Endpoint not yet deployed on Trip Service in dev -> fallback to checking individual or empty
                log.warn("Trip service batch busy endpoint returned 404; treating as zero busy trips");
                return Set.of();
            }
            log.error("Trip service returned status {} during batch busy check: {}", ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new TripServiceUnavailableException("Trip service returned error during availability check: " + ex.getStatusCode(), ex);
        } catch (Exception ex) {
            log.error("Unexpected error contacting Trip Service for batch availability: {}", ex.getMessage(), ex);
            throw new TripServiceUnavailableException("Trip service communication failure: " + ex.getMessage(), ex);
        }
    }

    public record BatchDriverBusyRequest(List<UUID> driverIds) {}
    public record DriverBusyResponse(UUID driverId, boolean busy) {}
}
