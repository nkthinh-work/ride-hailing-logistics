package com.rhl.location.controllers;

import com.rhl.location.configs.SecurityConfig;
import com.rhl.location.dtos.NearbyDriverResponse;
import com.rhl.location.exceptions.GlobalExceptionHandler;
import com.rhl.location.services.DriverLocationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = InternalLocationController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@DisplayName("InternalLocationController Security & M2M Contract Tests")
class InternalLocationControllerSecurityTest {

    private static final String BASE_URL = "/internal/v1/locations/nearby";
    private static final String VALID_SECRET = "rhl-internal-secret-2026";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DriverLocationService locationService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("Valid X-Internal-Secret allows nearby drivers query and returns 200 OK")
    void nearby_validSecret_returns200() throws Exception {
        UUID driverId = UUID.randomUUID();
        Instant now = Instant.now();

        when(locationService.nearby(anyDouble(), anyDouble(), anyDouble(), anyInt()))
                .thenReturn(List.of(new NearbyDriverResponse(driverId, 105.85, 21.02, 0.75, now)));

        mockMvc.perform(get(BASE_URL)
                        .header("X-Internal-Secret", VALID_SECRET)
                        .param("longitude", "105.85")
                        .param("latitude", "21.02")
                        .param("radiusKm", "1.0")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].driverId").value(driverId.toString()))
                .andExpect(jsonPath("$[0].longitude").value(105.85))
                .andExpect(jsonPath("$[0].distanceKm").value(0.75))
                .andExpect(jsonPath("$[0].lastSeenTimestamp").isNotEmpty());
    }

    @Test
    @DisplayName("Nearby query without internal secret is rejected with 401 UNAUTHORIZED_INTERNAL_SERVICE")
    void nearby_missingSecret_returns401() throws Exception {
        mockMvc.perform(get(BASE_URL)
                        .param("longitude", "105.85")
                        .param("latitude", "21.02"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED_INTERNAL_SERVICE"));
    }

    @Test
    @DisplayName("Nearby query with invalid secret is rejected with 401 UNAUTHORIZED_INTERNAL_SERVICE")
    void nearby_invalidSecret_returns401() throws Exception {
        mockMvc.perform(get(BASE_URL)
                        .header("X-Internal-Secret", "wrong-secret-999")
                        .param("longitude", "105.85")
                        .param("latitude", "21.02"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED_INTERNAL_SERVICE"));
    }

    @Test
    @DisplayName("Customer/Driver JWT without machine secret is rejected with 401")
    void nearby_userJwtWithoutSecret_returns401() throws Exception {
        mockMvc.perform(get(BASE_URL)
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString())))
                        .param("longitude", "105.85")
                        .param("latitude", "21.02"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED_INTERNAL_SERVICE"));
    }

    @Test
    @DisplayName("Invalid coordinates with valid secret returns 400 INVALID_ARGUMENT")
    void nearby_invalidCoordinates_returns400() throws Exception {
        when(locationService.nearby(anyDouble(), anyDouble(), anyDouble(), anyInt()))
                .thenThrow(new IllegalArgumentException("Invalid GPS coordinates"));

        mockMvc.perform(get(BASE_URL)
                        .header("X-Internal-Secret", VALID_SECRET)
                        .param("longitude", "999.0")
                        .param("latitude", "21.02"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    @DisplayName("GET /internal/v1/locations/drivers/{driverId} with valid secret returns 200 OK and driver coordinates")
    void getDriverLocation_validSecret_returns200() throws Exception {
        UUID driverId = UUID.randomUUID();
        when(locationService.getMine(driverId)).thenReturn(new com.rhl.location.dtos.DriverLocationResponse(
                driverId, "ONLINE", 105.85, 21.02, Instant.now(), UUID.randomUUID(), UUID.randomUUID()
        ));

        mockMvc.perform(get("/internal/v1/locations/drivers/{driverId}", driverId)
                        .header("X-Internal-Secret", VALID_SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.driverId").value(driverId.toString()))
                .andExpect(jsonPath("$.status").value("ONLINE"))
                .andExpect(jsonPath("$.longitude").value(105.85))
                .andExpect(jsonPath("$.latitude").value(21.02));
    }

    @Test
    @DisplayName("GET /internal/v1/locations/drivers/{driverId} when driver is offline returns 404 NOT_FOUND")
    void getDriverLocation_offlineDriver_returns404() throws Exception {
        UUID driverId = UUID.randomUUID();
        when(locationService.getMine(driverId)).thenReturn(new com.rhl.location.dtos.DriverLocationResponse(
                driverId, "OFFLINE", null, null, null, null, null
        ));

        mockMvc.perform(get("/internal/v1/locations/drivers/{driverId}", driverId)
                        .header("X-Internal-Secret", VALID_SECRET))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /internal/v1/locations/drivers/{driverId} without secret returns 401 UNAUTHORIZED")
    void getDriverLocation_missingSecret_returns401() throws Exception {
        UUID driverId = UUID.randomUUID();
        mockMvc.perform(get("/internal/v1/locations/drivers/{driverId}", driverId))
                .andExpect(status().isUnauthorized());
    }
}
