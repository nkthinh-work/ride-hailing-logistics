package com.rhl.location.clients;

import com.rhl.location.dtos.BatchEligibilityRequest;
import com.rhl.location.dtos.DriverEligibilityClientResponse;
import com.rhl.location.exceptions.IdentityServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Component
public class IdentityDriverClient {

    private static final Logger log = LoggerFactory.getLogger(IdentityDriverClient.class);

    private final RestClient restClient;

    @Autowired
    public IdentityDriverClient(
            @Value("${identity.service.base-url:http://localhost:8081}") String baseUrl,
            @Value("${identity.service.internal-secret:rhl-internal-secret-2026}") String internalSecret,
            @Value("${identity.service.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${identity.service.read-timeout-ms:3000}") int readTimeoutMs
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("X-Internal-Secret", internalSecret)
                .build();
    }

    public IdentityDriverClient(RestClient restClient) {
        this.restClient = restClient;
    }

    public DriverEligibilityClientResponse getEligibility(UUID driverId) {
        try {
            return restClient.get()
                    .uri("/internal/v1/drivers/{userId}/eligibility", driverId)
                    .retrieve()
                    .body(DriverEligibilityClientResponse.class);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) {
                return new DriverEligibilityClientResponse(
                        driverId,
                        false,
                        "NOT_FOUND",
                        null,
                        false,
                        List.of("USER_NOT_FOUND")
                );
            }
            log.error("Identity service returned status {} for driver {}: {}",
                    ex.getStatusCode(), driverId, ex.getResponseBodyAsString());
            throw new IdentityServiceUnavailableException("Failed to verify driver eligibility with identity service", ex);
        } catch (ResourceAccessException ex) {
            log.error("Network or timeout failure contacting identity service for driver {}: {}",
                    driverId, ex.getMessage());
            throw new IdentityServiceUnavailableException("Identity service unreachable or timed out", ex);
        } catch (Exception ex) {
            log.error("Unexpected error contacting identity service for driver {}: {}",
                    driverId, ex.getMessage());
            throw new IdentityServiceUnavailableException("Unexpected error communicating with identity service", ex);
        }
    }

    public List<DriverEligibilityClientResponse> getBatchEligibility(List<UUID> driverIds) {
        if (driverIds == null || driverIds.isEmpty()) {
            return List.of();
        }

        try {
            DriverEligibilityClientResponse[] responses = restClient.post()
                    .uri("/internal/v1/drivers/eligibility/batch")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new BatchEligibilityRequest(driverIds))
                    .retrieve()
                    .body(DriverEligibilityClientResponse[].class);

            return responses != null ? Arrays.asList(responses) : List.of();
        } catch (ResourceAccessException ex) {
            log.error("Network or timeout failure contacting identity service for batch eligibility: {}",
                    ex.getMessage());
            throw new IdentityServiceUnavailableException("Identity service unreachable or timed out during batch check", ex);
        } catch (Exception ex) {
            log.error("Failed to fetch batch driver eligibility from identity service: {}", ex.getMessage());
            throw new IdentityServiceUnavailableException("Failed to verify batch driver eligibility with identity service", ex);
        }
    }

    public List<com.rhl.location.dtos.VehicleClientResponse> getDriverVehicles(UUID driverId) {
        if (driverId == null) {
            return List.of();
        }

        try {
            com.rhl.location.dtos.VehicleClientResponse[] responses = restClient.get()
                    .uri("/internal/v1/drivers/{userId}/vehicles", driverId)
                    .retrieve()
                    .body(com.rhl.location.dtos.VehicleClientResponse[].class);

            return responses != null ? Arrays.asList(responses) : List.of();
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) {
                return List.of();
            }
            log.error("Identity service returned status {} for vehicles of driver {}: {}",
                    ex.getStatusCode(), driverId, ex.getResponseBodyAsString());
            throw new IdentityServiceUnavailableException("Failed to verify driver vehicles with identity service", ex);
        } catch (ResourceAccessException ex) {
            log.error("Network or timeout failure contacting identity service for vehicles of driver {}: {}",
                    driverId, ex.getMessage());
            throw new IdentityServiceUnavailableException("Identity service unreachable or timed out", ex);
        } catch (Exception ex) {
            log.error("Unexpected error contacting identity service for vehicles of driver {}: {}",
                    driverId, ex.getMessage());
            throw new IdentityServiceUnavailableException("Unexpected error communicating with identity service for vehicles", ex);
        }
    }
}
