package com.rhl.dispatch.clients;

import com.rhl.dispatch.exceptions.TripServiceUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@DisplayName("HttpTripAvailabilityClient Unit Tests")
class HttpTripAvailabilityClientTest {

    private MockRestServiceServer mockServer;
    private HttpTripAvailabilityClient client;

    private final String internalSecret = "test-internal-secret-2026";
    private final String baseUrl = "http://localhost:8084";

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl);
        mockServer = MockRestServiceServer.bindTo(builder).build();
        client = new HttpTripAvailabilityClient(builder.build(), internalSecret);
    }

    @Test
    @DisplayName("Successfully queries busy drivers from Trip Service")
    void findBusyDrivers_success() {
        UUID busyDriverId = UUID.randomUUID();
        UUID freeDriverId = UUID.randomUUID();

        String jsonResponse = """
                ["%s"]
                """.formatted(busyDriverId);

        mockServer.expect(requestTo(baseUrl + "/internal/v1/trips/drivers/busy"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Secret", internalSecret))
                .andRespond(withSuccess(jsonResponse, MediaType.APPLICATION_JSON));

        Set<UUID> busyDrivers = client.findBusyDrivers(List.of(busyDriverId, freeDriverId));

        assertNotNull(busyDrivers);
        assertEquals(1, busyDrivers.size());
        assertTrue(busyDrivers.contains(busyDriverId));
        assertFalse(busyDrivers.contains(freeDriverId));
        mockServer.verify();
    }

    @Test
    @DisplayName("Throws TripServiceUnavailableException on 500 server error (fail-closed)")
    void findBusyDrivers_serverError_failClosed() {
        UUID driverId = UUID.randomUUID();

        mockServer.expect(requestTo(baseUrl + "/internal/v1/trips/drivers/busy"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        assertThrows(TripServiceUnavailableException.class,
                () -> client.findBusyDrivers(List.of(driverId)));
        mockServer.verify();
    }
}
