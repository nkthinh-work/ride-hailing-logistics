package com.rhl.location.clients;

import com.rhl.location.dtos.DriverEligibilityClientResponse;
import com.rhl.location.exceptions.IdentityServiceUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class IdentityDriverClientTest {

    private MockRestServiceServer mockServer;
    private IdentityDriverClient client;

    private final UUID driverId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("http://localhost:8081")
                .defaultHeader("X-Internal-Secret", "test-secret");

        mockServer = MockRestServiceServer.bindTo(builder).build();
        client = new IdentityDriverClient(builder.build());
    }

    @Test
    @DisplayName("getEligibility: successful response deserializes accurately")
    void getEligibility_success_returnsResponse() {
        String json = """
                {
                    "userId": "%s",
                    "eligible": true,
                    "accountStatus": "ACTIVE",
                    "applicationStatus": "APPROVED",
                    "hasDriverRole": true,
                    "reasons": []
                }
                """.formatted(driverId);

        mockServer.expect(requestTo("http://localhost:8081/internal/v1/drivers/" + driverId + "/eligibility"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Secret", "test-secret"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        DriverEligibilityClientResponse response = client.getEligibility(driverId);

        assertThat(response.userId()).isEqualTo(driverId);
        assertThat(response.eligible()).isTrue();
        assertThat(response.accountStatus()).isEqualTo("ACTIVE");
        assertThat(response.applicationStatus()).isEqualTo("APPROVED");
        assertThat(response.hasDriverRole()).isTrue();
        assertThat(response.reasons()).isEmpty();

        mockServer.verify();
    }

    @Test
    @DisplayName("getEligibility: 404 Not Found returns ineligible response with USER_NOT_FOUND")
    void getEligibility_whenNotFound_returnsIneligible() {
        mockServer.expect(requestTo("http://localhost:8081/internal/v1/drivers/" + driverId + "/eligibility"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        DriverEligibilityClientResponse response = client.getEligibility(driverId);

        assertThat(response.userId()).isEqualTo(driverId);
        assertThat(response.eligible()).isFalse();
        assertThat(response.reasons()).contains("USER_NOT_FOUND");

        mockServer.verify();
    }

    @Test
    @DisplayName("getEligibility: 500 error throws IdentityServiceUnavailableException (fail-closed)")
    void getEligibility_whenServerError_throwsServiceUnavailable() {
        mockServer.expect(requestTo("http://localhost:8081/internal/v1/drivers/" + driverId + "/eligibility"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.getEligibility(driverId))
                .isInstanceOf(IdentityServiceUnavailableException.class);

        mockServer.verify();
    }

    @Test
    @DisplayName("getBatchEligibility: returns batch of driver eligibility statuses")
    void getBatchEligibility_success_returnsList() {
        UUID driver2 = UUID.randomUUID();
        String json = """
                [
                    {
                        "userId": "%s",
                        "eligible": true,
                        "accountStatus": "ACTIVE",
                        "applicationStatus": "APPROVED",
                        "hasDriverRole": true,
                        "reasons": []
                    },
                    {
                        "userId": "%s",
                        "eligible": false,
                        "accountStatus": "ACTIVE",
                        "applicationStatus": "SUSPENDED",
                        "hasDriverRole": true,
                        "reasons": ["DRIVER_SUSPENDED"]
                    }
                ]
                """.formatted(driverId, driver2);

        mockServer.expect(requestTo("http://localhost:8081/internal/v1/drivers/eligibility/batch"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Secret", "test-secret"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        List<DriverEligibilityClientResponse> responses = client.getBatchEligibility(List.of(driverId, driver2));

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).eligible()).isTrue();
        assertThat(responses.get(1).eligible()).isFalse();
        assertThat(responses.get(1).reasons()).contains("DRIVER_SUSPENDED");

        mockServer.verify();
    }

    @Test
    @DisplayName("getBatchEligibility: empty input list returns empty without HTTP call")
    void getBatchEligibility_emptyList_returnsEmpty() {
        List<DriverEligibilityClientResponse> responses = client.getBatchEligibility(List.of());
        assertThat(responses).isEmpty();
        mockServer.verify();
    }

    @Test
    @DisplayName("getDriverVehicles: deserializes id field into vehicleId accurately")
    void getDriverVehicles_withIdentityServiceIdField_deserializesVehicleId() {
        UUID expectedVehicleId = UUID.fromString("0d9deba7-2cb4-42fc-93d0-a6312c9facfa");
        String json = """
                [
                    {
                        "id": "%s",
                        "driverProfileId": "%s",
                        "type": "MOTORBIKE",
                        "plate": "29A-888.88",
                        "make": "Honda",
                        "model": "AirBlade",
                        "color": "Black",
                        "status": "ACTIVE"
                    }
                ]
                """.formatted(expectedVehicleId, UUID.randomUUID());

        mockServer.expect(requestTo("http://localhost:8081/internal/v1/drivers/" + driverId + "/vehicles"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Secret", "test-secret"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        List<com.rhl.location.dtos.VehicleClientResponse> vehicles = client.getDriverVehicles(driverId);

        assertThat(vehicles).hasSize(1);
        assertThat(vehicles.get(0).vehicleId()).isEqualTo(expectedVehicleId);
        assertThat(vehicles.get(0).plate()).isEqualTo("29A-888.88");
        assertThat(vehicles.get(0).status()).isEqualTo("ACTIVE");

        mockServer.verify();
    }
}

