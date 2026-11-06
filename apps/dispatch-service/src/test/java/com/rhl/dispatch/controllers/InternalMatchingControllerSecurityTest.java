package com.rhl.dispatch.controllers;

import com.rhl.dispatch.configs.MatchingProperties;
import com.rhl.dispatch.configs.SecurityConfig;
import com.rhl.dispatch.exceptions.GlobalExceptionHandler;
import com.rhl.dispatch.models.CandidateSelectionResult;
import com.rhl.dispatch.models.DriverCandidate;
import com.rhl.dispatch.models.VehicleType;
import com.rhl.dispatch.providers.TripCriteriaProvider;
import com.rhl.dispatch.services.DriverCandidateSelector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = InternalMatchingController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@DisplayName("InternalMatchingController Security & RBAC Tests")
class InternalMatchingControllerSecurityTest {

    private static final String BASE_URL = "/internal/v1/dispatch/matching/candidates";
    private static final String VALID_SECRET = "rhl-internal-secret-2026";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DriverCandidateSelector candidateSelector;

    @MockitoBean
    private MatchingProperties matchingProperties;

    @MockitoBean
    private TripCriteriaProvider tripCriteriaProvider;

    @MockitoBean
    private com.rhl.dispatch.services.MatchingOrchestrator matchingOrchestrator;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("Request with valid X-Internal-Secret and authoritative MATCHING trip returns 200 OK")
    void validInternalSecret_returns200() throws Exception {
        UUID tripId = UUID.randomUUID();
        UUID driverId = UUID.randomUUID();
        UUID vehicleId = UUID.randomUUID();

        when(tripCriteriaProvider.getTripMatchingCriteria(tripId)).thenReturn(
                new com.rhl.dispatch.models.TripMatchingCriteriaSnapshot(
                        tripId, UUID.randomUUID(), "MATCHING", 21.0285, 105.8542, "RIDE", "MOTORBIKE"
                )
        );

        DriverCandidate candidate = DriverCandidate.builder()
                .driverId(driverId)
                .vehicleId(vehicleId)
                .vehicleType(VehicleType.MOTORBIKE)
                .location(105.85, 21.02)
                .distanceKm(0.8)
                .lastSeenTimestamp(Instant.now())
                .build();

        CandidateSelectionResult result = new CandidateSelectionResult(
                tripId, 1, 1.0, List.of(candidate), Map.of(), Instant.now()
        );

        when(matchingProperties.radiiKm()).thenReturn(List.of(1.0, 3.0, 5.0));
        when(matchingProperties.maxCandidates()).thenReturn(10);
        when(candidateSelector.selectCandidates(any())).thenReturn(result);

        String validBody = """
                {
                    "tripId": "%s",
                    "pickupLatitude": 21.0285,
                    "pickupLongitude": 105.8542,
                    "serviceType": "RIDE",
                    "vehicleType": "MOTORBIKE",
                    "radiusKm": 1.0,
                    "maxCandidates": 10
                }
                """.formatted(tripId);

        mockMvc.perform(post(BASE_URL)
                        .header("X-Internal-Secret", VALID_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tripId").value(tripId.toString()))
                .andExpect(jsonPath("$.candidateCount").value(1))
                .andExpect(jsonPath("$.candidates[0].driverId").value(driverId.toString()))
                .andExpect(jsonPath("$.candidates[0].vehicleType").value("MOTORBIKE"));
    }

    @Test
    @DisplayName("Trip not found in authoritative Trip Service returns 404")
    void tripNotFound_returns404() throws Exception {
        UUID tripId = UUID.randomUUID();
        when(tripCriteriaProvider.getTripMatchingCriteria(tripId))
                .thenThrow(new com.rhl.dispatch.exceptions.TripValidationException(404, "TRIP_NOT_FOUND", "Trip not found"));

        String validBody = """
                {
                    "tripId": "%s",
                    "pickupLatitude": 21.0285,
                    "pickupLongitude": 105.8542,
                    "serviceType": "RIDE",
                    "vehicleType": "MOTORBIKE"
                }
                """.formatted(tripId);

        mockMvc.perform(post(BASE_URL)
                        .header("X-Internal-Secret", VALID_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));
    }

    @Test
    @DisplayName("Trip not in MATCHING status (e.g. CREATED) returns 409 CONFLICT")
    void tripNotMatching_returns409() throws Exception {
        UUID tripId = UUID.randomUUID();
        when(tripCriteriaProvider.getTripMatchingCriteria(tripId)).thenReturn(
                new com.rhl.dispatch.models.TripMatchingCriteriaSnapshot(
                        tripId, UUID.randomUUID(), "CREATED", 21.0285, 105.8542, "RIDE", "MOTORBIKE"
                )
        );

        String validBody = """
                {
                    "tripId": "%s",
                    "pickupLatitude": 21.0285,
                    "pickupLongitude": 105.8542,
                    "serviceType": "RIDE",
                    "vehicleType": "MOTORBIKE"
                }
                """.formatted(tripId);

        mockMvc.perform(post(BASE_URL)
                        .header("X-Internal-Secret", VALID_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRIP_NOT_MATCHING"));
    }

    @Test
    @DisplayName("Criteria mismatch between request and authoritative trip returns 400 BAD_REQUEST")
    void criteriaMismatch_returns400() throws Exception {
        UUID tripId = UUID.randomUUID();
        when(tripCriteriaProvider.getTripMatchingCriteria(tripId)).thenReturn(
                new com.rhl.dispatch.models.TripMatchingCriteriaSnapshot(
                        tripId, UUID.randomUUID(), "MATCHING", 21.0285, 105.8542, "RIDE", "CAR_4_SEATS"
                )
        );

        String mismatchBody = """
                {
                    "tripId": "%s",
                    "pickupLatitude": 21.0285,
                    "pickupLongitude": 105.8542,
                    "serviceType": "RIDE",
                    "vehicleType": "MOTORBIKE"
                }
                """.formatted(tripId);

        mockMvc.perform(post(BASE_URL)
                        .header("X-Internal-Secret", VALID_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mismatchBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CRITERIA_MISMATCH"));
    }

    @Test
    @DisplayName("Request without internal secret returns 401 UNAUTHORIZED_INTERNAL_SERVICE")
    void missingSecret_returns401() throws Exception {
        String body = """
                {
                    "tripId": "%s",
                    "pickupLatitude": 21.0285,
                    "pickupLongitude": 105.8542,
                    "serviceType": "RIDE",
                    "vehicleType": "MOTORBIKE"
                }
                """.formatted(UUID.randomUUID());

        mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED_INTERNAL_SERVICE"));
    }

    @Test
    @DisplayName("Request with invalid internal secret returns 401 UNAUTHORIZED_INTERNAL_SERVICE")
    void invalidSecret_returns401() throws Exception {
        String body = """
                {
                    "tripId": "%s",
                    "pickupLatitude": 21.0285,
                    "pickupLongitude": 105.8542,
                    "serviceType": "RIDE",
                    "vehicleType": "MOTORBIKE"
                }
                """.formatted(UUID.randomUUID());

        mockMvc.perform(post(BASE_URL)
                        .header("X-Internal-Secret", "wrong-secret-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED_INTERNAL_SERVICE"));
    }

    @Test
    @DisplayName("Request with invalid coordinates returns 400 VALIDATION_ERROR")
    void invalidCoordinates_returns400() throws Exception {
        String invalidBody = """
                {
                    "tripId": "%s",
                    "pickupLatitude": 95.0,
                    "pickupLongitude": 105.8542,
                    "serviceType": "RIDE",
                    "vehicleType": "MOTORBIKE"
                }
                """.formatted(UUID.randomUUID());

        mockMvc.perform(post(BASE_URL)
                        .header("X-Internal-Secret", VALID_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("POST /start with valid secret returns 200 OK")
    void startMatching_validSecret_returns200() throws Exception {
        UUID tripId = UUID.randomUUID();
        UUID offerId = UUID.randomUUID();
        UUID driverId = UUID.randomUUID();

        when(matchingOrchestrator.startOrContinueMatching(eq(tripId)))
                .thenReturn(com.rhl.dispatch.models.MatchingOrchestrationResult.offerDispatched(
                        tripId, offerId, driverId, 1, 1000));

        String body = """
                {
                    "tripId": "%s"
                }
                """.formatted(tripId);

        mockMvc.perform(post("/internal/v1/dispatch/matching/start")
                        .header("X-Internal-Secret", VALID_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tripId").value(tripId.toString()))
                .andExpect(jsonPath("$.offerId").value(offerId.toString()))
                .andExpect(jsonPath("$.driverId").value(driverId.toString()))
                .andExpect(jsonPath("$.outcome").value("OFFER_DISPATCHED"));
    }

    @Test
    @DisplayName("POST /cancel with valid secret returns 200 OK")
    void cancelMatching_validSecret_returns200() throws Exception {
        UUID tripId = UUID.randomUUID();

        when(matchingOrchestrator.cancelMatching(eq(tripId), any()))
                .thenReturn(com.rhl.dispatch.models.MatchingOrchestrationResult.cancelled(
                        tripId, "Customer cancelled"));

        String body = """
                {
                    "tripId": "%s",
                    "reason": "Customer cancelled"
                }
                """.formatted(tripId);

        mockMvc.perform(post("/internal/v1/dispatch/matching/cancel")
                        .header("X-Internal-Secret", VALID_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tripId").value(tripId.toString()))
                .andExpect(jsonPath("$.outcome").value("CANCELLED"));
    }

    @Test
    @DisplayName("POST /start without secret returns 401 UNAUTHORIZED")
    void startMatching_missingSecret_returns401() throws Exception {
        mockMvc.perform(post("/internal/v1/dispatch/matching/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tripId\": \"%s\"}".formatted(UUID.randomUUID())))
                .andExpect(status().isUnauthorized());
    }
}
