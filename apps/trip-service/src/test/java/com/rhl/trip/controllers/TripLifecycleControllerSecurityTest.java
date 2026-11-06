package com.rhl.trip.controllers;

import com.rhl.trip.configs.SecurityConfig;
import com.rhl.trip.dtos.TripResponse;
import com.rhl.trip.exceptions.GlobalExceptionHandler;
import com.rhl.trip.exceptions.InvalidTripStateTransitionException;
import com.rhl.trip.exceptions.TripAccessDeniedException;
import com.rhl.trip.exceptions.TripNotFoundException;
import com.rhl.trip.models.ServiceType;
import com.rhl.trip.models.TripStatus;
import com.rhl.trip.services.TripCancellationService;
import com.rhl.trip.services.TripProgressionService;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = TripLifecycleController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@DisplayName("TripLifecycleController Security & HTTP Tests")
class TripLifecycleControllerSecurityTest {

    private static final String BASE_URL = "/api/v1/trips";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TripCancellationService tripCancellationService;

    @MockitoBean
    private TripProgressionService tripProgressionService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private final UUID customerId = UUID.randomUUID();
    private final UUID driverId = UUID.randomUUID();
    private final UUID staffId = UUID.randomUUID();
    private final UUID tripId = UUID.randomUUID();

    private RequestPostProcessor asCustomer(UUID userId) {
        return jwt().jwt(j -> j.subject(userId.toString()).claim("roles", java.util.List.of("CUSTOMER")))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    private RequestPostProcessor asDriver(UUID userId) {
        return jwt().jwt(j -> j.subject(userId.toString()).claim("roles", java.util.List.of("DRIVER")))
                .authorities(new SimpleGrantedAuthority("ROLE_DRIVER"));
    }

    private RequestPostProcessor asSupportStaff(UUID userId) {
        return jwt().jwt(j -> j.subject(userId.toString()).claim("roles", java.util.List.of("SUPPORT_STAFF")))
                .authorities(new SimpleGrantedAuthority("ROLE_SUPPORT_STAFF"));
    }

    private RequestPostProcessor asAdministrator(UUID userId) {
        return jwt().jwt(j -> j.subject(userId.toString()).claim("roles", java.util.List.of("ADMINISTRATOR")))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMINISTRATOR"));
    }

    private TripResponse createMockResponse(TripStatus status) {
        return new TripResponse(
                tripId, customerId, driverId, status, UUID.randomUUID(),
                "idemp-key", ServiceType.RIDE, "MOTORBIKE",
                new BigDecimal("10.7769"), new BigDecimal("106.7009"), "Pickup",
                new BigDecimal("10.7725"), new BigDecimal("106.6980"), "Dropoff",
                new BigDecimal("5.00"), 10, new BigDecimal("40000.00"),
                new BigDecimal("1.00"), "VND", "DEV-v1.0",
                Instant.now(), Instant.now(), null, null, null,
                status == TripStatus.CANCELLED ? Instant.now() : null,
                status == TripStatus.CANCELLED ? "CUSTOMER_CHANGE_OF_MIND" : null,
                status == TripStatus.CANCELLED ? customerId : null,
                status == TripStatus.CANCELLED ? "CUSTOMER" : null,
                BigDecimal.ZERO, 0L
        );
    }

    @Nested
    @DisplayName("Cancellation Endpoint Security (/cancel)")
    class CancellationSecurityTests {

        @Test
        @DisplayName("Unauthenticated request to cancel trip returns 401 Unauthorized")
        void cancelTrip_Unauthenticated_Returns401() throws Exception {
            mockMvc.perform(post(BASE_URL + "/{tripId}/cancel", tripId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "reasonCode": "CUSTOMER_CHANGE_OF_MIND"
                                    }
                                    """))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Customer cancels trip successfully returns 200 OK")
        void cancelTrip_Customer_Returns200() throws Exception {
            when(tripCancellationService.cancelTrip(eq(tripId), eq(customerId), eq("CUSTOMER"), any()))
                    .thenReturn(createMockResponse(TripStatus.CANCELLED));

            mockMvc.perform(post(BASE_URL + "/{tripId}/cancel", tripId)
                            .with(asCustomer(customerId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "reasonCode": "CUSTOMER_CHANGE_OF_MIND",
                                      "description": "Found alternative transport"
                                    }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CANCELLED"))
                    .andExpect(jsonPath("$.tripId").value(tripId.toString()));
        }

        @Test
        @DisplayName("Driver cancels trip successfully returns 200 OK")
        void cancelTrip_Driver_Returns200() throws Exception {
            when(tripCancellationService.cancelTrip(eq(tripId), eq(driverId), eq("DRIVER"), any()))
                    .thenReturn(createMockResponse(TripStatus.CANCELLED));

            mockMvc.perform(post(BASE_URL + "/{tripId}/cancel", tripId)
                            .with(asDriver(driverId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "reasonCode": "VEHICLE_BREAKDOWN"
                                    }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CANCELLED"));
        }

        @Test
        @DisplayName("Support staff cancels trip successfully returns 200 OK")
        void cancelTrip_SupportStaff_Returns200() throws Exception {
            when(tripCancellationService.cancelTrip(eq(tripId), eq(staffId), eq("SUPPORT_STAFF"), any()))
                    .thenReturn(createMockResponse(TripStatus.CANCELLED));

            mockMvc.perform(post(BASE_URL + "/{tripId}/cancel", tripId)
                            .with(asSupportStaff(staffId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "reasonCode": "SUPPORT_DISPUTE_RESOLUTION"
                                    }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CANCELLED"));
        }

        @Test
        @DisplayName("Administrator cancels trip successfully returns 200 OK")
        void cancelTrip_Administrator_Returns200() throws Exception {
            UUID adminId = UUID.randomUUID();
            when(tripCancellationService.cancelTrip(eq(tripId), eq(adminId), eq("ADMINISTRATOR"), any()))
                    .thenReturn(createMockResponse(TripStatus.CANCELLED));

            mockMvc.perform(post(BASE_URL + "/{tripId}/cancel", tripId)
                            .with(asAdministrator(adminId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "reasonCode": "ADMINISTRATIVE_OVERRIDE"
                                    }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CANCELLED"));
        }

        @Test
        @DisplayName("Missing reasonCode in cancel request returns 400 Bad Request")
        void cancelTrip_MissingReason_Returns400() throws Exception {
            mockMvc.perform(post(BASE_URL + "/{tripId}/cancel", tripId)
                            .with(asCustomer(customerId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }

        @Test
        @DisplayName("Invalid state transition error returns 409 Conflict")
        void cancelTrip_InvalidTransition_Returns409() throws Exception {
            when(tripCancellationService.cancelTrip(any(), any(), any(), any()))
                    .thenThrow(new InvalidTripStateTransitionException(TripStatus.COMPLETED, TripStatus.CANCELLED, "Cannot cancel completed trip"));

            mockMvc.perform(post(BASE_URL + "/{tripId}/cancel", tripId)
                            .with(asCustomer(customerId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "reasonCode": "CANCEL"
                                    }
                                    """))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        }

        @Test
        @DisplayName("Trip access denied returns 403 Forbidden")
        void cancelTrip_AccessDenied_Returns403() throws Exception {
            when(tripCancellationService.cancelTrip(any(), any(), any(), any()))
                    .thenThrow(new TripAccessDeniedException(tripId, customerId));

            mockMvc.perform(post(BASE_URL + "/{tripId}/cancel", tripId)
                            .with(asCustomer(customerId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "reasonCode": "CANCEL"
                                    }
                                    """))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("TRIP_ACCESS_DENIED"));
        }
    }

    @Nested
    @DisplayName("Driver Progression Endpoints Security")
    class ProgressionSecurityTests {

        @Test
        @DisplayName("Unauthenticated request to progression endpoint returns 401 Unauthorized")
        void startPickup_Unauthenticated_Returns401() throws Exception {
            mockMvc.perform(post(BASE_URL + "/{tripId}/start-pickup", tripId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Customer attempting driver progression returns 403 Forbidden")
        void startPickup_CustomerRole_Returns403() throws Exception {
            mockMvc.perform(post(BASE_URL + "/{tripId}/start-pickup", tripId)
                            .with(asCustomer(customerId)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Driver calling /start-pickup returns 200 OK")
        void startPickup_Driver_Returns200() throws Exception {
            when(tripProgressionService.startPickingUp(tripId, driverId))
                    .thenReturn(createMockResponse(TripStatus.PICKING_UP));

            mockMvc.perform(post(BASE_URL + "/{tripId}/start-pickup", tripId)
                            .with(asDriver(driverId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PICKING_UP"));
        }

        @Test
        @DisplayName("Driver calling /arrive returns 200 OK")
        void arrive_Driver_Returns200() throws Exception {
            when(tripProgressionService.confirmArrived(tripId, driverId))
                    .thenReturn(createMockResponse(TripStatus.ARRIVED));

            mockMvc.perform(post(BASE_URL + "/{tripId}/arrive", tripId)
                            .with(asDriver(driverId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ARRIVED"));
        }

        @Test
        @DisplayName("Driver calling /start returns 200 OK")
        void startTrip_Driver_Returns200() throws Exception {
            when(tripProgressionService.startTrip(tripId, driverId))
                    .thenReturn(createMockResponse(TripStatus.IN_TRIP));

            mockMvc.perform(post(BASE_URL + "/{tripId}/start", tripId)
                            .with(asDriver(driverId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("IN_TRIP"));
        }

        @Test
        @DisplayName("Driver calling /complete returns 200 OK")
        void completeTrip_Driver_Returns200() throws Exception {
            when(tripProgressionService.completeTrip(tripId, driverId))
                    .thenReturn(createMockResponse(TripStatus.COMPLETED));

            mockMvc.perform(post(BASE_URL + "/{tripId}/complete", tripId)
                            .with(asDriver(driverId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("COMPLETED"));
        }

        @Test
        @DisplayName("Progression for non-existent trip returns 404 Not Found")
        void startPickup_TripNotFound_Returns404() throws Exception {
            when(tripProgressionService.startPickingUp(tripId, driverId))
                    .thenThrow(new TripNotFoundException(tripId));

            mockMvc.perform(post(BASE_URL + "/{tripId}/start-pickup", tripId)
                            .with(asDriver(driverId)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));
        }
    }
}
