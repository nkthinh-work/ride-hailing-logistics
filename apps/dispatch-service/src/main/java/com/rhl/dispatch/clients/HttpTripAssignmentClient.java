package com.rhl.dispatch.clients;

import com.rhl.dispatch.configs.DispatchServiceEndpoints;
import com.rhl.dispatch.exceptions.TripServiceUnavailableException;
import com.rhl.dispatch.exceptions.TripValidationException;
import com.rhl.dispatch.models.TripAssignmentResult;
import com.rhl.dispatch.models.TripMatchingCriteriaSnapshot;
import com.rhl.dispatch.providers.TripAssignmentProvider;
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
import java.util.UUID;

/**
 * HTTP REST Client implementing TripAssignmentProvider for M2M interactions with Trip Service (M06 Group 3).
 * Encapsulates timeout handling, authoritative state reconciliation on network partitions, and idempotency.
 */
@Component
public class HttpTripAssignmentClient implements TripAssignmentProvider {

    private static final Logger log = LoggerFactory.getLogger(HttpTripAssignmentClient.class);

    private final RestClient restClient;
    private final String internalSecret;

    @Autowired
    public HttpTripAssignmentClient(DispatchServiceEndpoints endpoints) {
        this.internalSecret = endpoints.internalSecret();

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(endpoints.connectTimeoutMs()));
        requestFactory.setReadTimeout(Duration.ofMillis(endpoints.readTimeoutMs()));

        this.restClient = RestClient.builder()
                .baseUrl(endpoints.tripBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    public HttpTripAssignmentClient(RestClient restClient, String internalSecret) {
        this.restClient = restClient;
        this.internalSecret = internalSecret;
    }

    @Override
    public TripAssignmentResult assignDriver(UUID tripId, UUID driverId, UUID vehicleId, UUID offerId) {
        AssignDriverRequestDto request = new AssignDriverRequestDto(driverId, vehicleId, offerId, "DRIVER");

        try {
            TripResponseDto response = restClient.post()
                    .uri("/internal/v1/trips/{tripId}/assign-driver", tripId)
                    .header("X-Internal-Secret", internalSecret)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(TripResponseDto.class);

            if (response != null && "ACCEPTED".equalsIgnoreCase(response.status())) {
                return TripAssignmentResult.success(tripId, response.driverId(), response.vehicleId(), response.status());
            }

            return TripAssignmentResult.failure(tripId, driverId, vehicleId, "UNEXPECTED_RESPONSE", "Invalid response from Trip Service");

        } catch (ResourceAccessException timeoutEx) {
            log.warn("Network timeout/connectivity issue calling assign-driver for trip {}. Entering authoritative reconciliation...", tripId, timeoutEx);
            return reconcileAssignment(tripId, driverId, vehicleId, request, timeoutEx);

        } catch (RestClientResponseException ex) {
            log.warn("Trip Service returned HTTP {} during assign-driver: {}", ex.getStatusCode(), ex.getResponseBodyAsString());
            if (ex.getStatusCode().value() == 404) {
                return TripAssignmentResult.failure(tripId, driverId, vehicleId, "TRIP_NOT_FOUND", "Trip " + tripId + " not found");
            }
            if (ex.getStatusCode().value() == 409) {
                return TripAssignmentResult.failure(tripId, driverId, vehicleId, "CONFLICT", ex.getResponseBodyAsString());
            }
            throw new TripServiceUnavailableException("Trip service returned error: " + ex.getStatusCode(), ex);
        } catch (Exception ex) {
            log.error("Unexpected error during assign-driver for trip {}: {}", tripId, ex.getMessage(), ex);
            throw new TripServiceUnavailableException("Trip service communication failure: " + ex.getMessage(), ex);
        }
    }

    /**
     * Reconciles in-doubt assignment when network timeout occurs (Scenario 2 & 3).
     */
    private TripAssignmentResult reconcileAssignment(UUID tripId, UUID driverId, UUID vehicleId,
                                                    AssignDriverRequestDto request, ResourceAccessException originalEx) {
        try {
            TripResponseDto authoritative = restClient.get()
                    .uri("/internal/v1/trips/{tripId}", tripId)
                    .header("X-Internal-Secret", internalSecret)
                    .retrieve()
                    .body(TripResponseDto.class);

            if (authoritative == null) {
                throw new TripServiceUnavailableException("Trip service returned null trip during reconciliation", originalEx);
            }

            // Case 1: Trip was committed as ACCEPTED with the same driver
            if ("ACCEPTED".equalsIgnoreCase(authoritative.status()) && driverId.equals(authoritative.driverId())) {
                log.info("Reconciliation SUCCESS: Trip {} was committed in Trip Service for driver {}.", tripId, driverId);
                return TripAssignmentResult.success(tripId, authoritative.driverId(), authoritative.vehicleId(), authoritative.status());
            }

            // Case 2: Trip was accepted by a different driver
            if ("ACCEPTED".equalsIgnoreCase(authoritative.status()) && !driverId.equals(authoritative.driverId())) {
                log.warn("Reconciliation CONFLICT: Trip {} was accepted by another driver {}.", tripId, authoritative.driverId());
                return TripAssignmentResult.failure(tripId, driverId, vehicleId, "CONFLICT_ANOTHER_DRIVER", "Trip was assigned to another driver");
            }

            // Case 3: Trip was CANCELLED
            if ("CANCELLED".equalsIgnoreCase(authoritative.status())) {
                log.warn("Reconciliation CANCELLED: Trip {} is CANCELLED.", tripId);
                return TripAssignmentResult.failure(tripId, driverId, vehicleId, "TRIP_CANCELLED", "Trip was cancelled");
            }

            // Case 4: Trip is still MATCHING - assignment was not committed; execute bounded idempotent retry
            if ("MATCHING".equalsIgnoreCase(authoritative.status())) {
                log.info("Reconciliation: Trip {} is still in MATCHING status. Executing idempotent retry...", tripId);
                TripResponseDto retryResponse = restClient.post()
                        .uri("/internal/v1/trips/{tripId}/assign-driver", tripId)
                        .header("X-Internal-Secret", internalSecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .body(TripResponseDto.class);

                if (retryResponse != null && "ACCEPTED".equalsIgnoreCase(retryResponse.status())) {
                    return TripAssignmentResult.success(tripId, retryResponse.driverId(), retryResponse.vehicleId(), retryResponse.status());
                }
            }

            return TripAssignmentResult.failure(tripId, driverId, vehicleId, "RECONCILIATION_FAILED", "Authoritative status is " + authoritative.status());

        } catch (ResourceAccessException retryEx) {
            log.error("Trip Service remains unreachable during reconciliation for trip {}. State is UNKNOWN.", tripId);
            throw new TripServiceUnavailableException("Trip service unreachable during assignment and reconciliation", originalEx);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 409) {
                return TripAssignmentResult.failure(tripId, driverId, vehicleId, "CONFLICT", ex.getResponseBodyAsString());
            }
            throw new TripServiceUnavailableException("Trip service returned error during reconciliation: " + ex.getStatusCode(), ex);
        }
    }

    @Override
    public TripMatchingCriteriaSnapshot startMatching(UUID tripId) {
        try {
            TripResponseDto response = restClient.post()
                    .uri("/internal/v1/trips/{tripId}/start-matching", tripId)
                    .header("X-Internal-Secret", internalSecret)
                    .retrieve()
                    .body(TripResponseDto.class);

            if (response == null) {
                throw new TripValidationException(404, "TRIP_NOT_FOUND", "Trip " + tripId + " not found");
            }

            return new TripMatchingCriteriaSnapshot(
                    response.tripId(),
                    response.customerId(),
                    response.status(),
                    response.pickupLatitude(),
                    response.pickupLongitude(),
                    response.serviceType(),
                    response.vehicleType()
            );

        } catch (TripValidationException ex) {
            throw ex;
        } catch (ResourceAccessException ex) {
            log.error("Network or timeout calling start-matching for trip {}: {}", tripId, ex.getMessage());
            throw new TripServiceUnavailableException("Trip service unreachable or timed out", ex);
        } catch (RestClientResponseException ex) {
            log.error("Trip service returned status {} during start-matching: {}", ex.getStatusCode(), ex.getResponseBodyAsString());
            if (ex.getStatusCode().value() == 404) {
                throw new TripValidationException(404, "TRIP_NOT_FOUND", "Trip " + tripId + " not found");
            }
            if (ex.getStatusCode().value() == 409) {
                throw new TripValidationException(409, "INVALID_STATE", "Trip " + tripId + " cannot start matching");
            }
            throw new TripServiceUnavailableException("Trip service returned error: " + ex.getStatusCode(), ex);
        } catch (Exception ex) {
            log.error("Unexpected error during start-matching for trip {}: {}", tripId, ex.getMessage(), ex);
            throw new TripServiceUnavailableException("Trip service communication failure: " + ex.getMessage(), ex);
        }
    }

    @Override
    public void markNoDriver(UUID tripId, String reason) {
        try {
            restClient.post()
                    .uri("/internal/v1/trips/{tripId}/no-driver", tripId)
                    .header("X-Internal-Secret", internalSecret)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new NoDriverRequestDto(reason))
                    .retrieve()
                    .toBodilessEntity();

            log.info("Successfully marked trip {} as NO_DRIVER (reason: {})", tripId, reason);

        } catch (ResourceAccessException ex) {
            log.error("Network or timeout calling no-driver for trip {}: {}", tripId, ex.getMessage());
            throw new TripServiceUnavailableException("Trip service unreachable or timed out", ex);
        } catch (RestClientResponseException ex) {
            log.error("Trip service returned status {} during no-driver: {}", ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new TripServiceUnavailableException("Trip service returned error: " + ex.getStatusCode(), ex);
        } catch (Exception ex) {
            log.error("Unexpected error calling no-driver for trip {}: {}", tripId, ex.getMessage(), ex);
            throw new TripServiceUnavailableException("Trip service communication failure: " + ex.getMessage(), ex);
        }
    }

    public record AssignDriverRequestDto(
            UUID driverId,
            UUID vehicleId,
            UUID offerId,
            String actorRole
    ) {}

    public record NoDriverRequestDto(
            String reason
    ) {}

    public record TripResponseDto(
            UUID tripId,
            UUID customerId,
            UUID driverId,
            UUID vehicleId,
            String status,
            Double pickupLatitude,
            Double pickupLongitude,
            String serviceType,
            String vehicleType
    ) {}
}
