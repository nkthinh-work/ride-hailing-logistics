package com.rhl.dispatch.clients;

import com.rhl.dispatch.exceptions.IdentityServiceUnavailableException;
import com.rhl.dispatch.models.VehicleType;
import com.rhl.dispatch.providers.DriverEligibilityProvider.DriverEligibilityInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@DisplayName("HttpIdentityEligibilityClient Unit Tests")
class HttpIdentityEligibilityClientTest {

    private MockRestServiceServer mockServer;
    private HttpIdentityEligibilityClient client;

    private final String internalSecret = "test-internal-secret-2026";
    private final String baseUrl = "http://localhost:8081";

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl);
        mockServer = MockRestServiceServer.bindTo(builder).build();
        client = new HttpIdentityEligibilityClient(builder.build(), internalSecret);
    }

    @Test
    @DisplayName("Successfully batch checks driver eligibility and fetches active vehicles")
    void getBatchEligibility_success() {
        UUID driverId = UUID.randomUUID();
        UUID vehicleId = UUID.randomUUID();

        String batchEligibilityJson = """
                [
                    {
                        "userId": "%s",
                        "eligible": true,
                        "accountStatus": "ACTIVE",
                        "applicationStatus": "APPROVED",
                        "hasDriverRole": true,
                        "reasons": []
                    }
                ]
                """.formatted(driverId);

        String vehiclesJson = """
                [
                    {
                        "id": "%s",
                        "driverProfileId": "%s",
                        "type": "MOTORBIKE",
                        "plate": "29A-12345",
                        "make": "Honda",
                        "model": "Wave",
                        "color": "Black",
                        "status": "ACTIVE"
                    }
                ]
                """.formatted(vehicleId, UUID.randomUUID());

        mockServer.expect(requestTo(baseUrl + "/internal/v1/drivers/eligibility/batch"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Secret", internalSecret))
                .andRespond(withSuccess(batchEligibilityJson, MediaType.APPLICATION_JSON));

        mockServer.expect(requestTo(baseUrl + "/internal/v1/drivers/" + driverId + "/vehicles"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Secret", internalSecret))
                .andRespond(withSuccess(vehiclesJson, MediaType.APPLICATION_JSON));

        Map<UUID, DriverEligibilityInfo> result = client.getBatchEligibility(List.of(driverId));

        assertNotNull(result);
        assertEquals(1, result.size());
        DriverEligibilityInfo info = result.get(driverId);
        assertTrue(info.eligible());
        assertTrue(info.isApproved());
        assertTrue(info.isAccountActive());
        assertEquals(1, info.activeVehicles().size());
        assertEquals(VehicleType.MOTORBIKE, info.activeVehicles().getFirst().type());
        mockServer.verify();
    }

    @Test
    @DisplayName("Throws IdentityServiceUnavailableException on 503 from Identity Service (fail-closed)")
    void getBatchEligibility_serviceUnavailable_failClosed() {
        UUID driverId = UUID.randomUUID();

        mockServer.expect(requestTo(baseUrl + "/internal/v1/drivers/eligibility/batch"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        assertThrows(IdentityServiceUnavailableException.class,
                () -> client.getBatchEligibility(List.of(driverId)));
        mockServer.verify();
    }
}
