package com.rhl.dispatch.clients;

import com.rhl.dispatch.configs.DispatchServiceEndpoints;
import com.rhl.dispatch.exceptions.TripServiceUnavailableException;
import com.rhl.dispatch.exceptions.TripValidationException;
import com.rhl.dispatch.models.TripMatchingCriteriaSnapshot;
import com.rhl.dispatch.providers.TripCriteriaProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.UUID;

@Component
public class HttpTripCriteriaClient implements TripCriteriaProvider {

    private static final Logger log = LoggerFactory.getLogger(HttpTripCriteriaClient.class);

    private final RestClient restClient;
    private final String internalSecret;

    @Autowired
    public HttpTripCriteriaClient(DispatchServiceEndpoints endpoints) {
        this.internalSecret = endpoints.internalSecret();

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(endpoints.connectTimeoutMs()));
        requestFactory.setReadTimeout(Duration.ofMillis(endpoints.readTimeoutMs()));

        this.restClient = RestClient.builder()
                .baseUrl(endpoints.tripBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    public HttpTripCriteriaClient(RestClient restClient, String internalSecret) {
        this.restClient = restClient;
        this.internalSecret = internalSecret;
    }

    @Override
    public TripMatchingCriteriaSnapshot getTripMatchingCriteria(UUID tripId) {
        try {
            TripCriteriaDto dto = restClient.get()
                    .uri("/internal/v1/trips/{tripId}/matching-criteria", tripId)
                    .header("X-Internal-Secret", internalSecret)
                    .retrieve()
                    .body(TripCriteriaDto.class);

            if (dto == null) {
                throw new TripValidationException(404, "TRIP_NOT_FOUND", "Trip " + tripId + " not found");
            }

            return new TripMatchingCriteriaSnapshot(
                    dto.tripId(),
                    dto.customerId(),
                    dto.status(),
                    dto.pickupLatitude(),
                    dto.pickupLongitude(),
                    dto.serviceType(),
                    dto.vehicleType()
            );

        } catch (TripValidationException ex) {
            throw ex;
        } catch (ResourceAccessException ex) {
            log.error("Network or timeout contacting Trip Service for matching criteria {}: {}", tripId, ex.getMessage());
            throw new TripServiceUnavailableException("Trip service unreachable or timed out", ex);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) {
                throw new TripValidationException(404, "TRIP_NOT_FOUND", "Trip " + tripId + " not found");
            }
            if (ex.getStatusCode().value() == 409) {
                throw new TripValidationException(409, "TRIP_NOT_MATCHING", "Trip " + tripId + " is not in MATCHING status");
            }
            log.error("Trip service returned status {}: {}", ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new TripServiceUnavailableException("Trip service returned error: " + ex.getStatusCode(), ex);
        } catch (Exception ex) {
            log.error("Unexpected error contacting Trip Service for trip criteria {}: {}", tripId, ex.getMessage(), ex);
            throw new TripServiceUnavailableException("Trip service communication failure: " + ex.getMessage(), ex);
        }
    }

    public record TripCriteriaDto(
            UUID tripId,
            UUID customerId,
            String status,
            Double pickupLatitude,
            Double pickupLongitude,
            String serviceType,
            String vehicleType
    ) {}
}
