package com.rhl.location.controllers;

import com.rhl.location.configs.SecurityConfig;
import com.rhl.location.dtos.DriverLocationResponse;
import com.rhl.location.dtos.NearbyDriverResponse;
import com.rhl.location.exceptions.DriverIneligibleException;
import com.rhl.location.exceptions.DriverOfflineException;
import com.rhl.location.exceptions.GlobalExceptionHandler;
import com.rhl.location.exceptions.IdentityServiceUnavailableException;
import com.rhl.location.exceptions.OutOfOrderTelemetryException;
import com.rhl.location.exceptions.TelemetryValidationException;
import com.rhl.location.services.DriverLocationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = DriverLocationController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class DriverLocationControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DriverLocationService locationService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private final UUID driverId = UUID.randomUUID();
    private final String validLocationJson = """
            {
                "longitude": 105.8342,
                "latitude": 21.0278
            }
            """;

    @Nested
    @DisplayName("POST /api/v1/locations/me/online Tests")
    class OnlineEndpointTests {

        @Test
        @DisplayName("Authenticated DRIVER with LOCATION_UPDATE authority turns ONLINE -> 200 OK")
        void online_withDriverRoleAndAuthority_returns200() throws Exception {
            DriverLocationResponse response = new DriverLocationResponse(
                    driverId, "ONLINE", 105.8342, 21.0278, Instant.now()
            );
            when(locationService.online(eq(driverId), any())).thenReturn(response);

            mockMvc.perform(post("/api/v1/locations/me/online")
                            .with(jwt().jwt(j -> j.subject(driverId.toString()))
                                    .authorities(
                                            new SimpleGrantedAuthority("ROLE_DRIVER"),
                                            new SimpleGrantedAuthority("LOCATION_UPDATE")
                                    ))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validLocationJson))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ONLINE"))
                    .andExpect(jsonPath("$.driverId").value(driverId.toString()));

            verify(locationService, times(1)).online(eq(driverId), any());
        }

        @Test
        @DisplayName("Suspended/ineligible driver going online returns 403 DRIVER_NOT_ELIGIBLE")
        void online_whenIneligible_returns403() throws Exception {
            when(locationService.online(eq(driverId), any()))
                    .thenThrow(new DriverIneligibleException("Driver is not eligible to go online", List.of("DRIVER_SUSPENDED")));

            mockMvc.perform(post("/api/v1/locations/me/online")
                            .with(jwt().jwt(j -> j.subject(driverId.toString()))
                                    .authorities(
                                            new SimpleGrantedAuthority("ROLE_DRIVER"),
                                            new SimpleGrantedAuthority("LOCATION_UPDATE")
                                    ))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validLocationJson))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("DRIVER_NOT_ELIGIBLE"))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("DRIVER_SUSPENDED")));
        }

        @Test
        @DisplayName("Identity service unavailable fails-closed with 503 IDENTITY_SERVICE_UNAVAILABLE")
        void online_whenIdentityUnavailable_returns503() throws Exception {
            when(locationService.online(eq(driverId), any()))
                    .thenThrow(new IdentityServiceUnavailableException("Identity service unavailable"));

            mockMvc.perform(post("/api/v1/locations/me/online")
                            .with(jwt().jwt(j -> j.subject(driverId.toString()))
                                    .authorities(
                                            new SimpleGrantedAuthority("ROLE_DRIVER"),
                                            new SimpleGrantedAuthority("LOCATION_UPDATE")
                                    ))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validLocationJson))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("IDENTITY_SERVICE_UNAVAILABLE"));
        }

        @Test
        @DisplayName("Unauthenticated request returns 401 Unauthorized")
        void online_unauthenticated_returns401() throws Exception {
            mockMvc.perform(post("/api/v1/locations/me/online")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validLocationJson))
                    .andExpect(status().isUnauthorized());

            verifyNoInteractions(locationService);
        }

        @Test
        @DisplayName("Customer role without DRIVER role returns 403 ACCESS_DENIED")
        void online_withCustomerRole_returns403() throws Exception {
            mockMvc.perform(post("/api/v1/locations/me/online")
                            .with(jwt().jwt(j -> j.subject(driverId.toString()))
                                    .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validLocationJson))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

            verifyNoInteractions(locationService);
        }
    }

    @Nested
    @DisplayName("PUT /api/v1/locations/me Tests")
    class UpdateEndpointTests {

        @Test
        @DisplayName("Suspended driver updating location returns 403 DRIVER_NOT_ELIGIBLE")
        void update_whenSuspended_returns403() throws Exception {
            when(locationService.update(eq(driverId), any()))
                    .thenThrow(new DriverIneligibleException("Driver is no longer eligible", List.of("DRIVER_SUSPENDED")));

            mockMvc.perform(put("/api/v1/locations/me")
                            .with(jwt().jwt(j -> j.subject(driverId.toString()))
                                    .authorities(
                                            new SimpleGrantedAuthority("ROLE_DRIVER"),
                                            new SimpleGrantedAuthority("LOCATION_UPDATE")
                                    ))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validLocationJson))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("DRIVER_NOT_ELIGIBLE"));
        }

        @Test
        @DisplayName("Driver offline in Redis returns 409 DRIVER_OFFLINE")
        void update_whenOffline_returns409() throws Exception {
            when(locationService.update(eq(driverId), any()))
                    .thenThrow(new DriverOfflineException());

            mockMvc.perform(put("/api/v1/locations/me")
                            .with(jwt().jwt(j -> j.subject(driverId.toString()))
                                    .authorities(
                                            new SimpleGrantedAuthority("ROLE_DRIVER"),
                                            new SimpleGrantedAuthority("LOCATION_UPDATE")
                                    ))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validLocationJson))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DRIVER_OFFLINE"));
        }

        @Test
        @DisplayName("Telemetry validation failure returns 400 with specific error code")
        void update_whenTelemetryInvalid_returns400() throws Exception {
            when(locationService.update(eq(driverId), any()))
                    .thenThrow(new TelemetryValidationException("Accuracy exceeds maximum allowed threshold", "TELEMETRY_ACCURACY_UNACCEPTABLE"));

            mockMvc.perform(put("/api/v1/locations/me")
                            .with(jwt().jwt(j -> j.subject(driverId.toString()))
                                    .authorities(
                                            new SimpleGrantedAuthority("ROLE_DRIVER"),
                                            new SimpleGrantedAuthority("LOCATION_UPDATE")
                                    ))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validLocationJson))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("TELEMETRY_ACCURACY_UNACCEPTABLE"));
        }

        @Test
        @DisplayName("Out-of-order telemetry returns 409 with OUT_OF_ORDER_TELEMETRY code")
        void update_whenOutOfOrder_returns409() throws Exception {
            when(locationService.update(eq(driverId), any()))
                    .thenThrow(new OutOfOrderTelemetryException("Telemetry out-of-order", "OUT_OF_ORDER_TELEMETRY"));

            mockMvc.perform(put("/api/v1/locations/me")
                            .with(jwt().jwt(j -> j.subject(driverId.toString()))
                                    .authorities(
                                            new SimpleGrantedAuthority("ROLE_DRIVER"),
                                            new SimpleGrantedAuthority("LOCATION_UPDATE")
                                    ))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validLocationJson))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("OUT_OF_ORDER_TELEMETRY"));
        }
    }

    @Nested
    @DisplayName("POST /api/v1/locations/me/offline Tests")
    class OfflineEndpointTests {

        @Test
        @DisplayName("Driver can turn OFFLINE successfully -> 200 OK")
        void offline_withDriverRole_returns200() throws Exception {
            DriverLocationResponse response = new DriverLocationResponse(
                    driverId, "OFFLINE", null, null, null
            );
            when(locationService.offline(driverId)).thenReturn(response);

            mockMvc.perform(post("/api/v1/locations/me/offline")
                            .with(jwt().jwt(j -> j.subject(driverId.toString()))
                                    .authorities(
                                            new SimpleGrantedAuthority("ROLE_DRIVER"),
                                            new SimpleGrantedAuthority("LOCATION_UPDATE")
                                    )))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("OFFLINE"));

            verify(locationService, times(1)).offline(driverId);
        }
    }

    @Nested
    @DisplayName("GET /api/v1/locations/nearby Tests")
    class NearbyEndpointTests {

        @Test
        @DisplayName("ADMINISTRATOR role can access nearby drivers -> 200 OK")
        void nearby_withAdminRole_returns200() throws Exception {
            NearbyDriverResponse driver = new NearbyDriverResponse(driverId, 105.834, 21.028, 1.2);
            when(locationService.nearby(105.8342, 21.0278, 3.0, 10))
                    .thenReturn(List.of(driver));

            mockMvc.perform(get("/api/v1/locations/nearby")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMINISTRATOR")))
                            .param("longitude", "105.8342")
                            .param("latitude", "21.0278")
                            .param("radiusKm", "3.0")
                            .param("limit", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].driverId").value(driverId.toString()));
        }

        @Test
        @DisplayName("DRIVER role is denied access to nearby endpoint -> 403 ACCESS_DENIED")
        void nearby_withDriverRole_returns403() throws Exception {
            mockMvc.perform(get("/api/v1/locations/nearby")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DRIVER")))
                            .param("longitude", "105.8342")
                            .param("latitude", "21.0278"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

            verifyNoInteractions(locationService);
        }
    }
}
