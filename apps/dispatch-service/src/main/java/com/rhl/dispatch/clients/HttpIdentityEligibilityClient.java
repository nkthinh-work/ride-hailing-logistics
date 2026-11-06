package com.rhl.dispatch.clients;

import com.rhl.dispatch.configs.DispatchServiceEndpoints;
import com.rhl.dispatch.exceptions.IdentityServiceUnavailableException;
import com.rhl.dispatch.models.VehicleType;
import com.rhl.dispatch.providers.DriverEligibilityProvider;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST client implementing DriverEligibilityProvider for Identity Service communication (FR-MAT-002, FR-DRV-008).
 */
@Component
public class HttpIdentityEligibilityClient implements DriverEligibilityProvider {

    private static final Logger log = LoggerFactory.getLogger(HttpIdentityEligibilityClient.class);

    private final RestClient restClient;
    private final String internalSecret;

    @Autowired
    public HttpIdentityEligibilityClient(DispatchServiceEndpoints endpoints) {
        this.internalSecret = endpoints.internalSecret();

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(endpoints.connectTimeoutMs()));
        requestFactory.setReadTimeout(Duration.ofMillis(endpoints.readTimeoutMs()));

        this.restClient = RestClient.builder()
                .baseUrl(endpoints.identityBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    public HttpIdentityEligibilityClient(RestClient restClient, String internalSecret) {
        this.restClient = restClient;
        this.internalSecret = internalSecret;
    }

    @Override
    public DriverEligibilityInfo getEligibility(UUID driverId) {
        try {
            EligibilityDto dto = restClient.get()
                    .uri("/internal/v1/drivers/{userId}/eligibility", driverId)
                    .header("X-Internal-Secret", internalSecret)
                    .retrieve()
                    .body(EligibilityDto.class);

            if (dto == null) {
                return new DriverEligibilityInfo(driverId, false, "UNKNOWN", null, false, List.of("UNKNOWN_DRIVER"), List.of());
            }

            List<VehicleSummary> vehicles = fetchVehicles(driverId);
            return mapToInfo(dto, vehicles);

        } catch (ResourceAccessException ex) {
            log.error("Network or timeout failure contacting Identity Service for driver {}: {}", driverId, ex.getMessage());
            throw new IdentityServiceUnavailableException("Identity service unreachable or timed out", ex);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) {
                return new DriverEligibilityInfo(driverId, false, "NOT_FOUND", null, false, List.of("USER_NOT_FOUND"), List.of());
            }
            log.error("Identity service returned status {}: {}", ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new IdentityServiceUnavailableException("Identity service returned error: " + ex.getStatusCode(), ex);
        } catch (Exception ex) {
            log.error("Unexpected error contacting Identity Service for driver {}: {}", driverId, ex.getMessage(), ex);
            throw new IdentityServiceUnavailableException("Identity service communication failure: " + ex.getMessage(), ex);
        }
    }

    @Override
    public Map<UUID, DriverEligibilityInfo> getBatchEligibility(List<UUID> driverIds) {
        if (driverIds == null || driverIds.isEmpty()) {
            return Map.of();
        }

        try {
            EligibilityDto[] response = restClient.post()
                    .uri("/internal/v1/drivers/eligibility/batch")
                    .header("X-Internal-Secret", internalSecret)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new BatchEligibilityRequest(driverIds))
                    .retrieve()
                    .body(EligibilityDto[].class);

            if (response == null || response.length == 0) {
                return Map.of();
            }

            Map<UUID, DriverEligibilityInfo> result = new HashMap<>();
            for (EligibilityDto dto : response) {
                List<VehicleSummary> vehicles = dto.eligible() ? fetchVehicles(dto.userId()) : List.of();
                result.put(dto.userId(), mapToInfo(dto, vehicles));
            }

            return result;

        } catch (ResourceAccessException ex) {
            log.error("Network or timeout failure contacting Identity Service during batch check: {}", ex.getMessage());
            throw new IdentityServiceUnavailableException("Identity service unreachable or timed out during batch check", ex);
        } catch (RestClientResponseException ex) {
            log.error("Identity service returned status {} during batch check: {}", ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new IdentityServiceUnavailableException("Identity service returned error during batch check: " + ex.getStatusCode(), ex);
        } catch (Exception ex) {
            log.error("Unexpected error contacting Identity Service for batch eligibility: {}", ex.getMessage(), ex);
            throw new IdentityServiceUnavailableException("Identity service batch check communication failure: " + ex.getMessage(), ex);
        }
    }

    private List<VehicleSummary> fetchVehicles(UUID driverId) {
        try {
            VehicleDto[] vehicleDtos = restClient.get()
                    .uri("/internal/v1/drivers/{userId}/vehicles", driverId)
                    .header("X-Internal-Secret", internalSecret)
                    .retrieve()
                    .body(VehicleDto[].class);

            if (vehicleDtos == null || vehicleDtos.length == 0) {
                return List.of();
            }

            return Arrays.stream(vehicleDtos)
                    .map(v -> new VehicleSummary(
                            v.id(),
                            mapVehicleType(v.type()),
                            v.plate(),
                            v.status()
                    ))
                    .toList();
        } catch (Exception ex) {
            log.warn("Failed to fetch vehicles for driver {}: {}", driverId, ex.getMessage());
            return List.of();
        }
    }

    private VehicleType mapVehicleType(String typeStr) {
        if (typeStr == null) return null;
        try {
            return VehicleType.valueOf(typeStr.toUpperCase().trim());
        } catch (IllegalArgumentException ex) {
            log.warn("Unknown vehicle type from Identity Service: {}", typeStr);
            return null;
        }
    }

    private DriverEligibilityInfo mapToInfo(EligibilityDto dto, List<VehicleSummary> vehicles) {
        return new DriverEligibilityInfo(
                dto.userId(),
                dto.eligible(),
                dto.accountStatus(),
                dto.applicationStatus(),
                dto.hasDriverRole(),
                dto.reasons() != null ? dto.reasons() : List.of(),
                vehicles != null ? vehicles : List.of()
        );
    }

    public record BatchEligibilityRequest(List<UUID> userIds) {}

    public record EligibilityDto(
            UUID userId,
            boolean eligible,
            String accountStatus,
            String applicationStatus,
            boolean hasDriverRole,
            List<String> reasons
    ) {}

    public record VehicleDto(
            UUID id,
            UUID driverProfileId,
            String type,
            String plate,
            String make,
            String model,
            String color,
            String status
    ) {}
}
