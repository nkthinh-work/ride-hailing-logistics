package com.rhl.dispatch.clients;

import com.rhl.dispatch.exceptions.LocationServiceUnavailableException;
import com.rhl.dispatch.providers.LocationNearbyProvider.NearbyDriverLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@DisplayName("HttpLocationNearbyClient Unit Tests")
class HttpLocationNearbyClientTest {

    private MockRestServiceServer mockServer;
    private HttpLocationNearbyClient client;

    private final String internalSecret = "test-internal-secret-2026";
    private final String baseUrl = "http://localhost:8082";

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl);
        mockServer = MockRestServiceServer.bindTo(builder).build();
        client = new HttpLocationNearbyClient(builder.build(), internalSecret);
    }

    @Test
    @DisplayName("Successfully queries nearby drivers and parses response")
    void findNearbyDrivers_success() {
        UUID driverId = UUID.randomUUID();
        Instant timestamp = Instant.parse("2026-10-24T10:00:00Z");

        String jsonResponse = """
                [
                    {
                        "driverId": "%s",
                        "longitude": 105.85,
                        "latitude": 21.02,
                        "distanceKm": 0.75,
                        "lastSeenTimestamp": "%s"
                    }
                ]
                """.formatted(driverId, timestamp);

        mockServer.expect(requestTo(org.hamcrest.Matchers.startsWith(baseUrl + "/internal/v1/locations/nearby")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Secret", internalSecret))
                .andRespond(withSuccess(jsonResponse, MediaType.APPLICATION_JSON));

        List<NearbyDriverLocation> locations = client.findNearbyDrivers(105.85, 21.02, 1.0, 10);

        assertEquals(1, locations.size());
        assertEquals(driverId, locations.getFirst().driverId());
        assertEquals(0.75, locations.getFirst().distanceKm());
        assertEquals(timestamp, locations.getFirst().lastSeenTimestamp());
        mockServer.verify();
    }

    @Test
    @DisplayName("Returns empty list when Location Service returns 404 or empty list")
    void findNearbyDrivers_emptyResponse() {
        mockServer.expect(requestTo(org.hamcrest.Matchers.startsWith(baseUrl + "/internal/v1/locations/nearby")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        List<NearbyDriverLocation> locations = client.findNearbyDrivers(105.85, 21.02, 1.0, 10);

        assertTrue(locations.isEmpty());
        mockServer.verify();
    }

    @Test
    @DisplayName("Throws LocationServiceUnavailableException on 500 server error (fail-closed)")
    void findNearbyDrivers_serverError_failClosed() {
        mockServer.expect(requestTo(org.hamcrest.Matchers.startsWith(baseUrl + "/internal/v1/locations/nearby")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withServerError());

        assertThrows(LocationServiceUnavailableException.class,
                () -> client.findNearbyDrivers(105.85, 21.02, 1.0, 10));
        mockServer.verify();
    }
}
