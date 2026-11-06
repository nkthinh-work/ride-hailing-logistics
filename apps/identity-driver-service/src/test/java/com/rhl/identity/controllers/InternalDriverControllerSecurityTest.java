package com.rhl.identity.controllers;

import com.rhl.identity.configs.SecurityConfig;
import com.rhl.identity.dtos.DriverEligibilityResponse;
import com.rhl.identity.entities.enums.DriverApplicationStatus;
import com.rhl.identity.exceptions.GlobalExceptionHandler;
import com.rhl.identity.security.SecurityErrorHandler;
import com.rhl.identity.services.interfaces.IDriverService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = InternalDriverController.class)
@Import({SecurityConfig.class, SecurityErrorHandler.class, GlobalExceptionHandler.class})
class InternalDriverControllerSecurityTest {

    private static final String INTERNAL_SECRET = "rhl-internal-secret-2026";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IDriverService driverService;

    @MockitoBean
    private com.rhl.identity.repositories.VehicleRepository vehicleRepository;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private final UUID driverId = UUID.randomUUID();

    @Nested
    @DisplayName("Single Driver Eligibility Endpoint Tests")
    class SingleDriverEligibilityTests {

        @Test
        @DisplayName("Valid X-Internal-Secret header allows access and returns 200 OK")
        void getEligibility_withValidHeader_returns200() throws Exception {
            DriverEligibilityResponse response = new DriverEligibilityResponse(
                    driverId,
                    true,
                    "ACTIVE",
                    DriverApplicationStatus.APPROVED,
                    true,
                    List.of()
            );

            when(driverService.getEligibilityByUserId(driverId)).thenReturn(response);

            mockMvc.perform(get("/internal/v1/drivers/{userId}/eligibility", driverId)
                            .header("X-Internal-Secret", INTERNAL_SECRET))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.userId").value(driverId.toString()))
                    .andExpect(jsonPath("$.eligible").value(true))
                    .andExpect(jsonPath("$.accountStatus").value("ACTIVE"))
                    .andExpect(jsonPath("$.applicationStatus").value("APPROVED"))
                    .andExpect(jsonPath("$.hasDriverRole").value(true));

            verify(driverService, times(1)).getEligibilityByUserId(driverId);
        }

        @Test
        @DisplayName("Valid Bearer internal secret allows access and returns 200 OK")
        void getEligibility_withValidBearerSecret_returns200() throws Exception {
            DriverEligibilityResponse response = new DriverEligibilityResponse(
                    driverId,
                    false,
                    "ACTIVE",
                    DriverApplicationStatus.SUSPENDED,
                    true,
                    List.of("DRIVER_SUSPENDED")
            );

            when(driverService.getEligibilityByUserId(driverId)).thenReturn(response);

            mockMvc.perform(get("/internal/v1/drivers/{userId}/eligibility", driverId)
                            .header("Authorization", "Bearer " + INTERNAL_SECRET))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.eligible").value(false))
                    .andExpect(jsonPath("$.applicationStatus").value("SUSPENDED"))
                    .andExpect(jsonPath("$.reasons[0]").value("DRIVER_SUSPENDED"));

            verify(driverService, times(1)).getEligibilityByUserId(driverId);
        }

        @Test
        @DisplayName("Missing secret header is rejected with 401 UNAUTHORIZED_INTERNAL_SERVICE")
        void getEligibility_withoutSecret_returns401() throws Exception {
            mockMvc.perform(get("/internal/v1/drivers/{userId}/eligibility", driverId))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED_INTERNAL_SERVICE"));

            verifyNoInteractions(driverService);
        }

        @Test
        @DisplayName("Invalid secret header is rejected with 401 UNAUTHORIZED_INTERNAL_SERVICE")
        void getEligibility_withInvalidSecret_returns401() throws Exception {
            mockMvc.perform(get("/internal/v1/drivers/{userId}/eligibility", driverId)
                            .header("X-Internal-Secret", "wrong-secret"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED_INTERNAL_SERVICE"));

            verifyNoInteractions(driverService);
        }

        @Test
        @DisplayName("Regular user JWT without internal secret is rejected with 401")
        void getEligibility_withUserJwt_returns401() throws Exception {
            mockMvc.perform(get("/internal/v1/drivers/{userId}/eligibility", driverId)
                            .with(jwt().jwt(j -> j.subject(driverId.toString()))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED_INTERNAL_SERVICE"));

            verifyNoInteractions(driverService);
        }
    }

    @Nested
    @DisplayName("Batch Driver Eligibility Endpoint Tests")
    class BatchDriverEligibilityTests {

        @Test
        @DisplayName("Valid X-Internal-Secret allows batch eligibility check")
        void getBatchEligibility_withValidHeader_returns200() throws Exception {
            UUID driver1 = UUID.randomUUID();
            UUID driver2 = UUID.randomUUID();

            List<DriverEligibilityResponse> responses = List.of(
                    new DriverEligibilityResponse(driver1, true, "ACTIVE", DriverApplicationStatus.APPROVED, true, List.of()),
                    new DriverEligibilityResponse(driver2, false, "ACTIVE", DriverApplicationStatus.SUSPENDED, true, List.of("DRIVER_SUSPENDED"))
            );

            when(driverService.getBatchEligibility(List.of(driver1, driver2))).thenReturn(responses);

            String requestBody = """
                    {
                        "userIds": ["%s", "%s"]
                    }
                    """.formatted(driver1, driver2);

            mockMvc.perform(post("/internal/v1/drivers/eligibility/batch")
                            .header("X-Internal-Secret", INTERNAL_SECRET)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].userId").value(driver1.toString()))
                    .andExpect(jsonPath("$[0].eligible").value(true))
                    .andExpect(jsonPath("$[1].userId").value(driver2.toString()))
                    .andExpect(jsonPath("$[1].eligible").value(false));

            verify(driverService, times(1)).getBatchEligibility(List.of(driver1, driver2));
        }

        @Test
        @DisplayName("Batch request without secret returns 401")
        void getBatchEligibility_withoutSecret_returns401() throws Exception {
            mockMvc.perform(post("/internal/v1/drivers/eligibility/batch")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userIds\": []}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED_INTERNAL_SERVICE"));

            verifyNoInteractions(driverService);
        }
    }

    @Nested
    @DisplayName("Driver Vehicles Endpoint Tests")
    class DriverVehicleEndpointTests {

        @Test
        @DisplayName("Valid X-Internal-Secret allows querying driver vehicles")
        void getDriverVehicles_withValidHeader_returns200() throws Exception {
            com.rhl.identity.entities.DriverProfile profile = mock(com.rhl.identity.entities.DriverProfile.class);
            when(profile.getId()).thenReturn(UUID.randomUUID());

            com.rhl.identity.entities.Vehicle vehicle = new com.rhl.identity.entities.Vehicle(
                    profile,
                    com.rhl.identity.entities.enums.VehicleType.MOTORBIKE,
                    "29A-12345",
                    "Honda",
                    "Wave",
                    "Black",
                    com.rhl.identity.entities.enums.VehicleStatus.ACTIVE
            );

            when(vehicleRepository.findByUserId(driverId)).thenReturn(List.of(vehicle));

            mockMvc.perform(get("/internal/v1/drivers/{userId}/vehicles", driverId)
                            .header("X-Internal-Secret", INTERNAL_SECRET))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].plate").value("29A-12345"))
                    .andExpect(jsonPath("$[0].type").value("MOTORBIKE"))
                    .andExpect(jsonPath("$[0].status").value("ACTIVE"));
        }

        @Test
        @DisplayName("Valid X-Internal-Secret allows batch querying driver vehicles")
        void getBatchDriverVehicles_withValidHeader_returns200() throws Exception {
            com.rhl.identity.entities.DriverProfile profile = mock(com.rhl.identity.entities.DriverProfile.class);
            when(profile.getId()).thenReturn(UUID.randomUUID());

            com.rhl.identity.entities.Vehicle vehicle = new com.rhl.identity.entities.Vehicle(
                    profile,
                    com.rhl.identity.entities.enums.VehicleType.MOTORBIKE,
                    "29A-99999",
                    "Yamaha",
                    "Exciter",
                    "Blue",
                    com.rhl.identity.entities.enums.VehicleStatus.ACTIVE
            );

            when(vehicleRepository.findByUserIds(List.of(driverId))).thenReturn(List.of(vehicle));

            String body = """
                    {
                        "userIds": ["%s"]
                    }
                    """.formatted(driverId);

            mockMvc.perform(post("/internal/v1/drivers/vehicles/batch")
                            .header("X-Internal-Secret", INTERNAL_SECRET)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].plate").value("29A-99999"));
        }

        @Test
        @DisplayName("Vehicle lookup without secret returns 401")
        void getDriverVehicles_withoutSecret_returns401() throws Exception {
            mockMvc.perform(get("/internal/v1/drivers/{userId}/vehicles", driverId))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED_INTERNAL_SERVICE"));
        }
    }
}
