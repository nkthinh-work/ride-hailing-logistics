package com.rhl.trip.controllers;

import com.rhl.trip.configs.SecurityConfig;
import com.rhl.trip.entities.Trip;
import com.rhl.trip.exceptions.GlobalExceptionHandler;
import com.rhl.trip.models.ServiceType;
import com.rhl.trip.models.TripStatus;
import com.rhl.trip.repositories.TripRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = InternalTripController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@DisplayName("InternalTripController Security & M2M Contract Tests")
class InternalTripControllerSecurityTest {

    private static final String BASE_URL = "/internal/v1/trips";
    private static final String VALID_SECRET = "rhl-internal-secret-2026";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TripRepository tripRepository;

    @MockitoBean
    private com.rhl.trip.services.DispatchTripAssignmentService assignmentService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("Single driver busy check with valid secret returns 200 OK")
    void isDriverBusy_validSecret_returns200() throws Exception {
        UUID driverId = UUID.randomUUID();
        when(tripRepository.existsByDriverIdAndStatusIn(eq(driverId), any())).thenReturn(true);

        mockMvc.perform(get(BASE_URL + "/drivers/" + driverId + "/busy")
                        .header("X-Internal-Secret", VALID_SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.driverId").value(driverId.toString()))
                .andExpect(jsonPath("$.busy").value(true));
    }

    @Test
    @DisplayName("Batch driver busy check with valid secret returns 200 OK with busy driver list")
    void batchDriverBusy_validSecret_returns200() throws Exception {
        UUID driver1 = UUID.randomUUID();
        UUID driver2 = UUID.randomUUID();
        when(tripRepository.findBusyDriverIds(any(), any())).thenReturn(List.of(driver1));

        String body = """
                {
                    "driverIds": ["%s", "%s"]
                }
                """.formatted(driver1, driver2);

        mockMvc.perform(post(BASE_URL + "/drivers/busy")
                        .header("X-Internal-Secret", VALID_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value(driver1.toString()));
    }

    @Test
    @DisplayName("Authoritative matching criteria for MATCHING trip returns 200 OK")
    void matchingCriteria_matchingTrip_returns200() throws Exception {
        UUID tripId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();

        Trip trip = mock(Trip.class);
        when(trip.getId()).thenReturn(tripId);
        when(trip.getCustomerId()).thenReturn(customerId);
        when(trip.getStatus()).thenReturn(TripStatus.MATCHING);
        when(trip.getPickupLatitude()).thenReturn(new BigDecimal("21.0285000"));
        when(trip.getPickupLongitude()).thenReturn(new BigDecimal("105.8542000"));
        when(trip.getServiceType()).thenReturn(ServiceType.RIDE);
        when(trip.getVehicleType()).thenReturn("MOTORBIKE");

        when(tripRepository.findById(tripId)).thenReturn(Optional.of(trip));

        mockMvc.perform(get(BASE_URL + "/" + tripId + "/matching-criteria")
                        .header("X-Internal-Secret", VALID_SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tripId").value(tripId.toString()))
                .andExpect(jsonPath("$.status").value("MATCHING"))
                .andExpect(jsonPath("$.vehicleType").value("MOTORBIKE"))
                .andExpect(jsonPath("$.serviceType").value("RIDE"));
    }

    @Test
    @DisplayName("Authoritative matching criteria for non-MATCHING trip returns 409 CONFLICT")
    void matchingCriteria_nonMatchingTrip_returns409() throws Exception {
        UUID tripId = UUID.randomUUID();

        Trip trip = mock(Trip.class);
        when(trip.getId()).thenReturn(tripId);
        when(trip.getStatus()).thenReturn(TripStatus.CREATED);

        when(tripRepository.findById(tripId)).thenReturn(Optional.of(trip));

        mockMvc.perform(get(BASE_URL + "/" + tripId + "/matching-criteria")
                        .header("X-Internal-Secret", VALID_SECRET))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    @DisplayName("Authoritative matching criteria for non-existent trip returns 404 NOT_FOUND")
    void matchingCriteria_nonExistentTrip_returns404() throws Exception {
        UUID tripId = UUID.randomUUID();
        when(tripRepository.findById(tripId)).thenReturn(Optional.empty());

        mockMvc.perform(get(BASE_URL + "/" + tripId + "/matching-criteria")
                        .header("X-Internal-Secret", VALID_SECRET))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));
    }

    @Test
    @DisplayName("Assign driver with valid secret returns 200 OK")
    void assignDriver_validSecret_returns200() throws Exception {
        UUID tripId = UUID.randomUUID();
        UUID driverId = UUID.randomUUID();
        UUID vehicleId = UUID.randomUUID();

        com.rhl.trip.dtos.TripResponse mockResponse = new com.rhl.trip.dtos.TripResponse(
                tripId, UUID.randomUUID(), driverId, TripStatus.ACCEPTED,
                null, "key", ServiceType.RIDE, "MOTORBIKE",
                null, null, null, null, null, null,
                null, null, null, null, "VND", null,
                null, null, null, null, null, null, 1L
        );

        when(assignmentService.assignDriver(eq(tripId), eq(driverId), eq(vehicleId), any()))
                .thenReturn(mockResponse);

        String body = """
                {
                    "driverId": "%s",
                    "vehicleId": "%s",
                    "offerId": "%s",
                    "actorRole": "DRIVER"
                }
                """.formatted(driverId, vehicleId, UUID.randomUUID());

        mockMvc.perform(post(BASE_URL + "/" + tripId + "/assign-driver")
                        .header("X-Internal-Secret", VALID_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tripId").value(tripId.toString()))
                .andExpect(jsonPath("$.driverId").value(driverId.toString()))
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    @Test
    @DisplayName("Start matching with valid secret returns 200 OK")
    void startMatching_validSecret_returns200() throws Exception {
        UUID tripId = UUID.randomUUID();

        com.rhl.trip.dtos.TripResponse mockResponse = new com.rhl.trip.dtos.TripResponse(
                tripId, UUID.randomUUID(), null, TripStatus.MATCHING,
                null, "key", ServiceType.RIDE, "MOTORBIKE",
                null, null, null, null, null, null,
                null, null, null, null, "VND", null,
                null, null, null, null, null, null, 1L
        );

        when(assignmentService.startMatching(eq(tripId))).thenReturn(mockResponse);

        mockMvc.perform(post(BASE_URL + "/" + tripId + "/start-matching")
                        .header("X-Internal-Secret", VALID_SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tripId").value(tripId.toString()))
                .andExpect(jsonPath("$.status").value("MATCHING"));
    }

    @Test
    @DisplayName("Mark no driver with valid secret returns 200 OK")
    void markNoDriver_validSecret_returns200() throws Exception {
        UUID tripId = UUID.randomUUID();

        com.rhl.trip.dtos.TripResponse mockResponse = new com.rhl.trip.dtos.TripResponse(
                tripId, UUID.randomUUID(), null, TripStatus.NO_DRIVER,
                null, "key", ServiceType.RIDE, "MOTORBIKE",
                null, null, null, null, null, null,
                null, null, null, null, "VND", null,
                null, null, null, null, null, null, 1L
        );

        when(assignmentService.markNoDriver(eq(tripId), any())).thenReturn(mockResponse);

        String body = """
                {
                    "reason": "Exhausted all candidate rounds"
                }
                """;

        mockMvc.perform(post(BASE_URL + "/" + tripId + "/no-driver")
                        .header("X-Internal-Secret", VALID_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tripId").value(tripId.toString()))
                .andExpect(jsonPath("$.status").value("NO_DRIVER"));
    }

    @Test
    @DisplayName("Assign driver without secret returns 401 UNAUTHORIZED_INTERNAL_SERVICE")
    void assignDriver_missingSecret_returns401() throws Exception {
        mockMvc.perform(post(BASE_URL + "/" + UUID.randomUUID() + "/assign-driver")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED_INTERNAL_SERVICE"));
    }

    @Test
    @DisplayName("Internal endpoint without secret returns 401 UNAUTHORIZED_INTERNAL_SERVICE")
    void internalEndpoint_missingSecret_returns401() throws Exception {
        mockMvc.perform(get(BASE_URL + "/drivers/" + UUID.randomUUID() + "/busy"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED_INTERNAL_SERVICE"));
    }

    @Test
    @DisplayName("Internal endpoint with wrong secret returns 401 UNAUTHORIZED_INTERNAL_SERVICE")
    void internalEndpoint_wrongSecret_returns401() throws Exception {
        mockMvc.perform(get(BASE_URL + "/drivers/" + UUID.randomUUID() + "/busy")
                        .header("X-Internal-Secret", "wrong-secret-xyz"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED_INTERNAL_SERVICE"));
    }
}
