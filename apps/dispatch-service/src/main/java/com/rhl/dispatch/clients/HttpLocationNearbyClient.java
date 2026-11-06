package com.rhl.dispatch.clients;

import com.rhl.dispatch.configs.DispatchServiceEndpoints;
import com.rhl.dispatch.exceptions.LocationServiceUnavailableException;
import com.rhl.dispatch.providers.LocationNearbyProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * REST client implementing LocationNearbyProvider for Location Service communication (FR-LOC-004).
 */
@Component
public class HttpLocationNearbyClient implements LocationNearbyProvider {

    private static final Logger log = LoggerFactory.getLogger(HttpLocationNearbyClient.class);

    private final RestClient restClient;
    private final String internalSecret;

    @Autowired
    public HttpLocationNearbyClient(DispatchServiceEndpoints endpoints) {
        this.internalSecret = endpoints.internalSecret();

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(endpoints.connectTimeoutMs()));
        requestFactory.setReadTimeout(Duration.ofMillis(endpoints.readTimeoutMs()));

        this.restClient = RestClient.builder()
                .baseUrl(endpoints.locationBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    public HttpLocationNearbyClient(RestClient restClient, String internalSecret) {
        this.restClient = restClient;
        this.internalSecret = internalSecret;
    }

    @Override
    public List<NearbyDriverLocation> findNearbyDrivers(double longitude, double latitude, double radiusKm, int limit) {
        try {
            NearbyLocationDto[] response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/internal/v1/locations/nearby")
                            .queryParam("longitude", longitude)
                            .queryParam("latitude", latitude)
                            .queryParam("radiusKm", radiusKm)
                            .queryParam("limit", limit)
                            .build())
                    .header("X-Internal-Secret", internalSecret)
                    .retrieve()
                    .body(NearbyLocationDto[].class);

            if (response == null || response.length == 0) {
                return List.of();
            }

            return Arrays.stream(response)
                    .map(dto -> new NearbyDriverLocation(
                            dto.driverId(),
                            dto.longitude(),
                            dto.latitude(),
                            dto.distanceKm(),
                            dto.lastSeenTimestamp(),
                            dto.selectedVehicleId()
                    ))
                    .toList();

        } catch (ResourceAccessException ex) {
            log.error("Network or timeout failure contacting Location Service: {}", ex.getMessage());
            throw new LocationServiceUnavailableException("Location service is unreachable or timed out", ex);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().is5xxServerError()) {
                log.error("Location service returned server error {}: {}", ex.getStatusCode(), ex.getResponseBodyAsString());
                throw new LocationServiceUnavailableException("Location service server error: " + ex.getStatusCode(), ex);
            }
            log.warn("Location service returned status {}: {}", ex.getStatusCode(), ex.getResponseBodyAsString());
            return List.of();
        } catch (Exception ex) {
            log.error("Unexpected error contacting Location Service: {}", ex.getMessage(), ex);
            throw new LocationServiceUnavailableException("Location service communication failure: " + ex.getMessage(), ex);
        }
    }

    public record NearbyLocationDto(
            UUID driverId,
            double longitude,
            double latitude,
            double distanceKm,
            Instant lastSeenTimestamp,
            UUID selectedVehicleId
    ) {
    }
}
