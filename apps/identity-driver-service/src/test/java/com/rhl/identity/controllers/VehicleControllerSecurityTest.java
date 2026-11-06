package com.rhl.identity.controllers;

import com.rhl.identity.configs.SecurityConfig;
import com.rhl.identity.dtos.VehicleRequest;
import com.rhl.identity.dtos.VehicleResponse;
import com.rhl.identity.entities.enums.VehicleStatus;
import com.rhl.identity.entities.enums.VehicleType;
import com.rhl.identity.exceptions.GlobalExceptionHandler;
import com.rhl.identity.exceptions.VehicleNotFoundException;
import com.rhl.identity.exceptions.VehiclePlateAlreadyExistsException;
import com.rhl.identity.security.SecurityErrorHandler;
import com.rhl.identity.services.interfaces.IVehicleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = VehicleController.class)
@Import({SecurityConfig.class, SecurityErrorHandler.class, GlobalExceptionHandler.class})
class VehicleControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IVehicleService vehicleService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private final UUID vehicleId = UUID.randomUUID();
    private final UUID driverProfileId = UUID.randomUUID();
    private final UUID ownerUserId = UUID.randomUUID();
    private final UUID strangerUserId = UUID.randomUUID();
    private final UUID reviewerUserId = UUID.randomUUID();

    private VehicleResponse sampleVehicle(VehicleStatus status) {
        return new VehicleResponse(
                vehicleId,
                driverProfileId,
                VehicleType.CAR_4_SEAT,
                "29A-12345",
                "Toyota",
                "Vios",
                "White",
                status,
                Instant.now()
        );
    }

    @Nested
    @DisplayName("CASE 1: Unauthenticated Requests (No JWT)")
    class UnauthenticatedTests {

        @Test
        @DisplayName("POST /api/v1/drivers/vehicles without token returns 401 TOKEN_MISSING")
        void registerVehicle_unauthenticated_returns401() throws Exception {
            mockMvc.perform(post("/api/v1/drivers/vehicles")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "type": "CAR_4_SEAT",
                                      "plate": "29A-12345",
                                      "make": "Toyota",
                                      "model": "Vios",
                                      "color": "White"
                                    }
                                    """))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.code").value("TOKEN_MISSING"));

            verifyNoInteractions(vehicleService);
        }

        @Test
        @DisplayName("GET /api/v1/drivers/vehicles/me without token returns 401 TOKEN_MISSING")
        void getMyVehicles_unauthenticated_returns401() throws Exception {
            mockMvc.perform(get("/api/v1/drivers/vehicles/me"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.code").value("TOKEN_MISSING"));

            verifyNoInteractions(vehicleService);
        }

        @Test
        @DisplayName("GET /api/v1/drivers/vehicles/{id} without token returns 401 TOKEN_MISSING")
        void getVehicleById_unauthenticated_returns401() throws Exception {
            mockMvc.perform(get("/api/v1/drivers/vehicles/" + vehicleId))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.code").value("TOKEN_MISSING"));

            verifyNoInteractions(vehicleService);
        }
    }

    @Nested
    @DisplayName("CASE 2: Request Body Validation (DR-VAL-003)")
    class ValidationTests {

        @Test
        @DisplayName("POST /vehicles with blank plate returns 400 VALIDATION_ERROR")
        void registerVehicle_blankPlate_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/drivers/vehicles")
                            .with(jwt().jwt(j -> j.subject(ownerUserId.toString())))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "type": "CAR_4_SEAT",
                                      "plate": "",
                                      "make": "Toyota",
                                      "model": "Vios",
                                      "color": "White"
                                    }
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

            verifyNoInteractions(vehicleService);
        }

        @Test
        @DisplayName("POST /vehicles with blank make or model returns 400 VALIDATION_ERROR")
        void registerVehicle_blankMakeModel_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/drivers/vehicles")
                            .with(jwt().jwt(j -> j.subject(ownerUserId.toString())))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "type": "CAR_4_SEAT",
                                      "plate": "29A-12345",
                                      "make": "",
                                      "model": "  ",
                                      "color": "White"
                                    }
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

            verifyNoInteractions(vehicleService);
        }
    }

    @Nested
    @DisplayName("CASE 3: Authorization & RBAC for Reviewer Endpoint")
    class ReviewerEndpointTests {

        @Test
        @DisplayName("GET /applications/{appId} called by Customer without DRIVER_REVIEW -> returns 403 ACCESS_DENIED")
        void getVehiclesByApplicationId_customerForbidden_returns403() throws Exception {
            mockMvc.perform(get("/api/v1/drivers/vehicles/applications/" + driverProfileId)
                            .with(jwt().jwt(j -> j.subject(ownerUserId.toString()))
                                    .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

            verifyNoInteractions(vehicleService);
        }

        @Test
        @DisplayName("GET /applications/{appId} called by Reviewer with DRIVER_REVIEW authority -> returns 200 OK")
        void getVehiclesByApplicationId_reviewerAllowed_returns200() throws Exception {
            when(vehicleService.getVehiclesByDriverProfileId(driverProfileId))
                    .thenReturn(List.of(sampleVehicle(VehicleStatus.ACTIVE)));

            mockMvc.perform(get("/api/v1/drivers/vehicles/applications/" + driverProfileId)
                            .with(jwt().jwt(j -> j.subject(reviewerUserId.toString()))
                                    .authorities(new SimpleGrantedAuthority("DRIVER_REVIEW"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].plate").value("29A-12345"))
                    .andExpect(jsonPath("$[0].type").value("CAR_4_SEAT"));

            verify(vehicleService).getVehiclesByDriverProfileId(driverProfileId);
        }
    }

    @Nested
    @DisplayName("CASE 4: Ownership & Business Exception Mappings")
    class OwnershipAndExceptionTests {

        @Test
        @DisplayName("POST /vehicles with duplicate plate returns 409 VEHICLE_PLATE_EXISTS")
        void registerVehicle_duplicatePlate_returns409() throws Exception {
            when(vehicleService.registerVehicle(eq(ownerUserId), any(VehicleRequest.class)))
                    .thenThrow(new VehiclePlateAlreadyExistsException("29A-12345"));

            mockMvc.perform(post("/api/v1/drivers/vehicles")
                            .with(jwt().jwt(j -> j.subject(ownerUserId.toString())))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "type": "CAR_4_SEAT",
                                      "plate": "29A-12345",
                                      "make": "Toyota",
                                      "model": "Vios",
                                      "color": "White"
                                    }
                                    """))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.code").value("VEHICLE_PLATE_EXISTS"))
                    .andExpect(jsonPath("$.message").value("Vehicle plate already registered: 29A-12345"));
        }

        @Test
        @DisplayName("GET /vehicles/{id} for non-existent vehicle returns 404 VEHICLE_NOT_FOUND")
        void getVehicleById_notFound_returns404() throws Exception {
            when(vehicleService.getVehicleById(eq(ownerUserId), eq(vehicleId)))
                    .thenThrow(new VehicleNotFoundException());

            mockMvc.perform(get("/api/v1/drivers/vehicles/" + vehicleId)
                            .with(jwt().jwt(j -> j.subject(ownerUserId.toString()))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.code").value("VEHICLE_NOT_FOUND"));
        }

        @Test
        @DisplayName("GET /vehicles/{id} for stranger returns 403 ACCESS_DENIED")
        void getVehicleById_stranger_returns403() throws Exception {
            when(vehicleService.getVehicleById(eq(strangerUserId), eq(vehicleId)))
                    .thenThrow(new AccessDeniedException("You do not have permission to view this vehicle"));

            mockMvc.perform(get("/api/v1/drivers/vehicles/" + vehicleId)
                            .with(jwt().jwt(j -> j.subject(strangerUserId.toString()))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }

        @Test
        @DisplayName("GET /vehicles/me returns current user's vehicle list")
        void getMyVehicles_owner_returns200() throws Exception {
            when(vehicleService.getMyVehicles(ownerUserId))
                    .thenReturn(List.of(sampleVehicle(VehicleStatus.ACTIVE)));

            mockMvc.perform(get("/api/v1/drivers/vehicles/me")
                            .with(jwt().jwt(j -> j.subject(ownerUserId.toString()))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].plate").value("29A-12345"))
                    .andExpect(jsonPath("$[0].status").value("ACTIVE"));
        }
    }
}
