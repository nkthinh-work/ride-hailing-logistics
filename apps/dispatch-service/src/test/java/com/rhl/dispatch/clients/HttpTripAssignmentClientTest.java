package com.rhl.dispatch.clients;

import com.rhl.dispatch.exceptions.TripServiceUnavailableException;
import com.rhl.dispatch.models.TripAssignmentResult;
import com.rhl.dispatch.models.TripMatchingCriteriaSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@DisplayName("HttpTripAssignmentClient Unit Tests")
class HttpTripAssignmentClientTest {

    private MockRestServiceServer mockServer;
    private HttpTripAssignmentClient client;

    private final String internalSecret = "test-internal-secret-2026";
    private final String baseUrl = "http://localhost:8084";

    private UUID tripId;
    private UUID driverId;
    private UUID vehicleId;
    private UUID offerId;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl);
        mockServer = MockRestServiceServer.bindTo(builder).build();
        client = new HttpTripAssignmentClient(builder.build(), internalSecret);

        tripId = UUID.randomUUID();
        driverId = UUID.randomUUID();
        vehicleId = UUID.randomUUID();
        offerId = UUID.randomUUID();
    }

    @Nested
    @DisplayName("assignDriver tests")
    class AssignDriverTests {

        @Test
        @DisplayName("Successfully assigns driver via M2M POST /internal/v1/trips/{tripId}/assign-driver")
        void assignDriver_success() {
            String jsonResponse = """
                    {
                        "tripId": "%s",
                        "customerId": "%s",
                        "driverId": "%s",
                        "vehicleId": "%s",
                        "status": "ACCEPTED"
                    }
                    """.formatted(tripId, UUID.randomUUID(), driverId, vehicleId);

            mockServer.expect(requestTo(baseUrl + "/internal/v1/trips/" + tripId + "/assign-driver"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(header("X-Internal-Secret", internalSecret))
                    .andRespond(withSuccess(jsonResponse, MediaType.APPLICATION_JSON));

            TripAssignmentResult result = client.assignDriver(tripId, driverId, vehicleId, offerId);

            assertThat(result.success()).isTrue();
            assertThat(result.tripId()).isEqualTo(tripId);
            assertThat(result.driverId()).isEqualTo(driverId);
            assertThat(result.vehicleId()).isEqualTo(vehicleId);
            assertThat(result.status()).isEqualTo("ACCEPTED");
            mockServer.verify();
        }

        @Test
        @DisplayName("Returns failure result on 409 Conflict from Trip Service")
        void assignDriver_conflict() {
            mockServer.expect(requestTo(baseUrl + "/internal/v1/trips/" + tripId + "/assign-driver"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(header("X-Internal-Secret", internalSecret))
                    .andRespond(withStatus(HttpStatus.CONFLICT).body("{\"code\": \"TRIP_ALREADY_ASSIGNED\"}"));

            TripAssignmentResult result = client.assignDriver(tripId, driverId, vehicleId, offerId);

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo("CONFLICT");
            mockServer.verify();
        }

        @Test
        @DisplayName("Returns failure result on 404 Not Found from Trip Service")
        void assignDriver_notFound() {
            mockServer.expect(requestTo(baseUrl + "/internal/v1/trips/" + tripId + "/assign-driver"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(header("X-Internal-Secret", internalSecret))
                    .andRespond(withStatus(HttpStatus.NOT_FOUND));

            TripAssignmentResult result = client.assignDriver(tripId, driverId, vehicleId, offerId);

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo("TRIP_NOT_FOUND");
            mockServer.verify();
        }
    }

    @Nested
    @DisplayName("startMatching tests")
    class StartMatchingTests {

        @Test
        @DisplayName("Successfully starts matching via POST /internal/v1/trips/{tripId}/start-matching")
        void startMatching_success() {
            String jsonResponse = """
                    {
                        "tripId": "%s",
                        "customerId": "%s",
                        "status": "MATCHING",
                        "pickupLatitude": 21.0285,
                        "pickupLongitude": 105.8542,
                        "serviceType": "RIDE",
                        "vehicleType": "MOTORBIKE"
                    }
                    """.formatted(tripId, UUID.randomUUID());

            mockServer.expect(requestTo(baseUrl + "/internal/v1/trips/" + tripId + "/start-matching"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(header("X-Internal-Secret", internalSecret))
                    .andRespond(withSuccess(jsonResponse, MediaType.APPLICATION_JSON));

            TripMatchingCriteriaSnapshot result = client.startMatching(tripId);

            assertThat(result).isNotNull();
            assertThat(result.tripId()).isEqualTo(tripId);
            assertThat(result.status()).isEqualTo("MATCHING");
            assertThat(result.pickupLatitude()).isEqualTo(21.0285);
            mockServer.verify();
        }

        @Test
        @DisplayName("Throws TripServiceUnavailableException on 500 server error")
        void startMatching_serverError() {
            mockServer.expect(requestTo(baseUrl + "/internal/v1/trips/" + tripId + "/start-matching"))
                    .andExpect(method(HttpMethod.POST))
                    .andRespond(withServerError());

            assertThatThrownBy(() -> client.startMatching(tripId))
                    .isInstanceOf(TripServiceUnavailableException.class);
            mockServer.verify();
        }
    }

    @Nested
    @DisplayName("markNoDriver tests")
    class MarkNoDriverTests {

        @Test
        @DisplayName("Successfully marks no-driver via POST /internal/v1/trips/{tripId}/no-driver")
        void markNoDriver_success() {
            mockServer.expect(requestTo(baseUrl + "/internal/v1/trips/" + tripId + "/no-driver"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(header("X-Internal-Secret", internalSecret))
                    .andRespond(withSuccess());

            client.markNoDriver(tripId, "Candidates exhausted");
            mockServer.verify();
        }
    }
}
