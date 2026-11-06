package com.rhl.identity.controllers;

import com.rhl.identity.configs.SecurityConfig;
import com.rhl.identity.dtos.DriverApplicationResponse;
import com.rhl.identity.dtos.DriverAuditLogResponse;
import com.rhl.identity.dtos.DriverEligibilityResponse;
import com.rhl.identity.dtos.RejectDriverRequest;
import com.rhl.identity.dtos.SuspendDriverRequest;
import com.rhl.identity.entities.enums.DriverApplicationStatus;
import com.rhl.identity.entities.enums.DriverAuditAction;
import com.rhl.identity.exceptions.DriverApplicationConflictException;
import com.rhl.identity.exceptions.DriverApplicationNotFoundException;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = DriverController.class)
@Import({SecurityConfig.class, SecurityErrorHandler.class, GlobalExceptionHandler.class})
class DriverControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IDriverService driverService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private final UUID applicationId = UUID.randomUUID();
    private final UUID reviewerId = UUID.randomUUID();
    private final UUID administratorId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();

    private DriverApplicationResponse sampleResponse(DriverApplicationStatus status) {
        return new DriverApplicationResponse(
                applicationId,
                customerId,
                "Nguyen Van A",
                "0901234567",
                "GPLX123456",
                "29A-12345",
                status,
                null,
                Instant.now(),
                null
        );
    }

    @Nested
    @DisplayName("CASE 1: Unauthenticated Requests (No JWT)")
    class UnauthenticatedTests {

        @Test
        @DisplayName("GET /applications without token returns 401 TOKEN_MISSING")
        void listApplications_unauthenticated_returns401() throws Exception {
            mockMvc.perform(get("/api/v1/drivers/applications"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.code").value("TOKEN_MISSING"));

            verifyNoInteractions(driverService);
        }

        @Test
        @DisplayName("PATCH /applications/{id}/approve without token returns 401 TOKEN_MISSING")
        void approveApplication_unauthenticated_returns401() throws Exception {
            mockMvc.perform(patch("/api/v1/drivers/applications/{id}/approve", applicationId))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.code").value("TOKEN_MISSING"));

            verifyNoInteractions(driverService);
        }

        @Test
        @DisplayName("PATCH /applications/{id}/reject without token returns 401 TOKEN_MISSING")
        void rejectApplication_unauthenticated_returns401() throws Exception {
            mockMvc.perform(patch("/api/v1/drivers/applications/{id}/reject", applicationId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"Giay to khong hop le\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.code").value("TOKEN_MISSING"));

            verifyNoInteractions(driverService);
        }
    }

    @Nested
    @DisplayName("CASE 2: Authenticated as CUSTOMER (Lacks DRIVER_REVIEW authority)")
    class CustomerRoleTests {

        @Test
        @DisplayName("GET /applications with ROLE_CUSTOMER returns 403 ACCESS_DENIED")
        void listApplications_customerRole_returns403() throws Exception {
            mockMvc.perform(get("/api/v1/drivers/applications")
                            .with(jwt().jwt(j -> j.subject(customerId.toString()))
                                    .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

            verifyNoInteractions(driverService);
        }

        @Test
        @DisplayName("PATCH /applications/{id}/approve with ROLE_CUSTOMER returns 403 ACCESS_DENIED")
        void approveApplication_customerRole_returns403() throws Exception {
            mockMvc.perform(patch("/api/v1/drivers/applications/{id}/approve", applicationId)
                            .with(jwt().jwt(j -> j.subject(customerId.toString()))
                                    .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

            verifyNoInteractions(driverService);
        }

        @Test
        @DisplayName("PATCH /applications/{id}/reject with ROLE_CUSTOMER returns 403 ACCESS_DENIED")
        void rejectApplication_customerRole_returns403() throws Exception {
            mockMvc.perform(patch("/api/v1/drivers/applications/{id}/reject", applicationId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"Giay to khong hop le\"}")
                            .with(jwt().jwt(j -> j.subject(customerId.toString()))
                                    .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

            verifyNoInteractions(driverService);
        }
    }

    @Nested
    @DisplayName("CASE 3: Authenticated as REVIEWER with DRIVER_REVIEW Permission")
    class AuthorizedReviewerTests {

        @Test
        @DisplayName("GET /applications with DRIVER_REVIEW authority returns 200 and invokes service")
        void listApplications_withPermission_returns200() throws Exception {
            when(driverService.listApplications(DriverApplicationStatus.PENDING))
                    .thenReturn(List.of(sampleResponse(DriverApplicationStatus.PENDING)));

            mockMvc.perform(get("/api/v1/drivers/applications")
                            .with(jwt().jwt(j -> j.subject(reviewerId.toString()))
                                    .authorities(new SimpleGrantedAuthority("ROLE_REVIEWER"),
                                            new SimpleGrantedAuthority("DRIVER_REVIEW"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].id").value(applicationId.toString()))
                    .andExpect(jsonPath("$[0].fullName").value("Nguyen Van A"));

            verify(driverService, times(1)).listApplications(DriverApplicationStatus.PENDING);
        }

        @Test
        @DisplayName("PATCH /applications/{id}/approve with DRIVER_REVIEW authority returns 200 and invokes service")
        void approveApplication_withPermission_returns200() throws Exception {
            when(driverService.approve(reviewerId, applicationId))
                    .thenReturn(sampleResponse(DriverApplicationStatus.APPROVED));

            mockMvc.perform(patch("/api/v1/drivers/applications/{id}/approve", applicationId)
                            .with(jwt().jwt(j -> j.subject(reviewerId.toString()))
                                    .authorities(new SimpleGrantedAuthority("ROLE_REVIEWER"),
                                            new SimpleGrantedAuthority("DRIVER_REVIEW"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(applicationId.toString()))
                    .andExpect(jsonPath("$.status").value("APPROVED"));

            verify(driverService, times(1)).approve(reviewerId, applicationId);
        }

        @Test
        @DisplayName("PATCH /applications/{id}/reject with DRIVER_REVIEW authority returns 200 and invokes service")
        void rejectApplication_withPermission_returns200() throws Exception {
            when(driverService.reject(eq(reviewerId), eq(applicationId), any(RejectDriverRequest.class)))
                    .thenReturn(sampleResponse(DriverApplicationStatus.REJECTED));

            mockMvc.perform(patch("/api/v1/drivers/applications/{id}/reject", applicationId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"Giay to khong hop le\"}")
                            .with(jwt().jwt(j -> j.subject(reviewerId.toString()))
                                    .authorities(new SimpleGrantedAuthority("ROLE_REVIEWER"),
                                            new SimpleGrantedAuthority("DRIVER_REVIEW"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(applicationId.toString()))
                    .andExpect(jsonPath("$.status").value("REJECTED"));

            verify(driverService, times(1)).reject(eq(reviewerId), eq(applicationId), any(RejectDriverRequest.class));
        }

        @Test
        @DisplayName("PATCH /applications/{id}/reject with blank reason returns 400 VALIDATION_ERROR without invoking service")
        void rejectApplication_withBlankReason_returns400() throws Exception {
            mockMvc.perform(patch("/api/v1/drivers/applications/{id}/reject", applicationId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"\"}")
                            .with(jwt().jwt(j -> j.subject(reviewerId.toString()))
                                    .authorities(new SimpleGrantedAuthority("ROLE_REVIEWER"),
                                            new SimpleGrantedAuthority("DRIVER_REVIEW"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

            verifyNoInteractions(driverService);
        }
    }

    @Nested
    @DisplayName("CASE 4: Authenticated as REVIEWER missing DRIVER_REVIEW Permission")
    class ReviewerWithoutPermissionTests {

        @Test
        @DisplayName("GET /applications with only ROLE_REVIEWER returns 403 ACCESS_DENIED")
        void listApplications_reviewerWithoutPermission_returns403() throws Exception {
            mockMvc.perform(get("/api/v1/drivers/applications")
                            .with(jwt().jwt(j -> j.subject(reviewerId.toString()))
                                    .authorities(new SimpleGrantedAuthority("ROLE_REVIEWER"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

            verifyNoInteractions(driverService);
        }

        @Test
        @DisplayName("PATCH /applications/{id}/approve with only ROLE_REVIEWER returns 403 ACCESS_DENIED")
        void approveApplication_reviewerWithoutPermission_returns403() throws Exception {
            mockMvc.perform(patch("/api/v1/drivers/applications/{id}/approve", applicationId)
                            .with(jwt().jwt(j -> j.subject(reviewerId.toString()))
                                    .authorities(new SimpleGrantedAuthority("ROLE_REVIEWER"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

            verifyNoInteractions(driverService);
        }

        @Test
        @DisplayName("PATCH /applications/{id}/reject with only ROLE_REVIEWER returns 403 ACCESS_DENIED")
        void rejectApplication_reviewerWithoutPermission_returns403() throws Exception {
            mockMvc.perform(patch("/api/v1/drivers/applications/{id}/reject", applicationId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"Giay to khong hop le\"}")
                            .with(jwt().jwt(j -> j.subject(reviewerId.toString()))
                                    .authorities(new SimpleGrantedAuthority("ROLE_REVIEWER"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

            verifyNoInteractions(driverService);
        }
    }

    @Nested
    @DisplayName("CASE 5: Non-reviewer Endpoints (/me)")
    class NonReviewerEndpointTests {

        @Test
        @DisplayName("GET /applications/me with ROLE_CUSTOMER succeeds and invokes getMyApplication")
        void getMine_customerRole_returns200() throws Exception {
            when(driverService.getMyApplication(customerId))
                    .thenReturn(sampleResponse(DriverApplicationStatus.PENDING));

            mockMvc.perform(get("/api/v1/drivers/applications/me")
                            .with(jwt().jwt(j -> j.subject(customerId.toString()))
                                    .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(applicationId.toString()))
                    .andExpect(jsonPath("$.fullName").value("Nguyen Van A"));

            verify(driverService, times(1)).getMyApplication(customerId);
        }

        @Test
        @DisplayName("GET /applications/me/eligibility with authenticated user succeeds and invokes getEligibilityByUserId")
        void getMyEligibility_authenticated_returns200() throws Exception {
            DriverEligibilityResponse eligibility = new DriverEligibilityResponse(
                    customerId,
                    true,
                    "ACTIVE",
                    DriverApplicationStatus.APPROVED,
                    true,
                    List.of()
            );

            when(driverService.getEligibilityByUserId(customerId)).thenReturn(eligibility);

            mockMvc.perform(get("/api/v1/drivers/applications/me/eligibility")
                            .with(jwt().jwt(j -> j.subject(customerId.toString()))
                                    .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.userId").value(customerId.toString()))
                    .andExpect(jsonPath("$.eligible").value(true))
                    .andExpect(jsonPath("$.accountStatus").value("ACTIVE"))
                    .andExpect(jsonPath("$.applicationStatus").value("APPROVED"));

            verify(driverService, times(1)).getEligibilityByUserId(customerId);
        }
    }

    @Nested
    @DisplayName("CASE 6: Application Eligibility Endpoint by ID")
    class ApplicationEligibilityByIdTests {

        @Test
        @DisplayName("GET /{id}/eligibility without DRIVER_REVIEW returns 403 ACCESS_DENIED")
        void getApplicationEligibility_missingReviewerAuthority_returns403() throws Exception {
            mockMvc.perform(get("/api/v1/drivers/applications/{id}/eligibility", applicationId)
                            .with(jwt().jwt(j -> j.subject(customerId.toString()))
                                    .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

            verifyNoInteractions(driverService);
        }

        @Test
        @DisplayName("GET /{id}/eligibility with DRIVER_REVIEW authority returns 200 OK")
        void getApplicationEligibility_reviewerAuthority_returns200() throws Exception {
            DriverEligibilityResponse eligibility = new DriverEligibilityResponse(
                    customerId,
                    true,
                    "ACTIVE",
                    DriverApplicationStatus.APPROVED,
                    true,
                    List.of()
            );

            when(driverService.getEligibilityByApplicationId(applicationId)).thenReturn(eligibility);

            mockMvc.perform(get("/api/v1/drivers/applications/{id}/eligibility", applicationId)
                            .with(jwt().jwt(j -> j.subject(reviewerId.toString()))
                                    .authorities(new SimpleGrantedAuthority("DRIVER_REVIEW"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.userId").value(customerId.toString()))
                    .andExpect(jsonPath("$.eligible").value(true));

            verify(driverService, times(1)).getEligibilityByApplicationId(applicationId);
        }
    }

    @Nested
    @DisplayName("CASE 7: Suspend Driver Application Endpoint")
    class SuspendDriverTests {

        @Test
        @DisplayName("PATCH /{id}/suspend without token returns 401 TOKEN_MISSING")
        void suspend_unauthenticated_returns401() throws Exception {
            mockMvc.perform(patch("/api/v1/drivers/applications/{id}/suspend", applicationId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"Violated guidelines\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.code").value("TOKEN_MISSING"));

            verifyNoInteractions(driverService);
        }

        @Test
        @DisplayName("PATCH /{id}/suspend with ROLE_CUSTOMER returns 403 ACCESS_DENIED")
        void suspend_customerAuthority_returns403() throws Exception {
            mockMvc.perform(patch("/api/v1/drivers/applications/{id}/suspend", applicationId)
                            .with(jwt().jwt(j -> j.subject(customerId.toString()))
                                    .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"Violated guidelines\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

            verifyNoInteractions(driverService);
        }

        @Test
        @DisplayName("PATCH /{id}/suspend with DRIVER_REVIEW (Reviewer) returns 403 ACCESS_DENIED (Admin only)")
        void suspend_reviewerAuthority_returns403() throws Exception {
            mockMvc.perform(patch("/api/v1/drivers/applications/{id}/suspend", applicationId)
                            .with(jwt().jwt(j -> j.subject(reviewerId.toString()))
                                    .authorities(new SimpleGrantedAuthority("DRIVER_REVIEW")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"Violated guidelines\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

            verifyNoInteractions(driverService);
        }

        @Test
        @DisplayName("PATCH /{id}/suspend with blank reason returns 400 VALIDATION_ERROR")
        void suspend_blankReason_returns400() throws Exception {
            mockMvc.perform(patch("/api/v1/drivers/applications/{id}/suspend", applicationId)
                            .with(jwt().jwt(j -> j.subject(administratorId.toString()))
                                    .authorities(new SimpleGrantedAuthority("ROLE_ADMINISTRATOR")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"   \"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

            verifyNoInteractions(driverService);
        }

        @Test
        @DisplayName("PATCH /{id}/suspend with ROLE_ADMINISTRATOR and valid reason returns 200 OK")
        void suspend_administratorAuthority_returns200() throws Exception {
            DriverApplicationResponse response = sampleResponse(DriverApplicationStatus.SUSPENDED);
            when(driverService.suspend(eq(administratorId), eq(applicationId), any(SuspendDriverRequest.class)))
                    .thenReturn(response);

            mockMvc.perform(patch("/api/v1/drivers/applications/{id}/suspend", applicationId)
                            .with(jwt().jwt(j -> j.subject(administratorId.toString()))
                                    .authorities(new SimpleGrantedAuthority("ROLE_ADMINISTRATOR")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"Dangerous driving report verified\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(applicationId.toString()))
                    .andExpect(jsonPath("$.status").value("SUSPENDED"));

            verify(driverService, times(1))
                    .suspend(eq(administratorId), eq(applicationId), any(SuspendDriverRequest.class));
        }

        @Test
        @DisplayName("PATCH /{id}/suspend when application not found returns 404 NOT_FOUND")
        void suspend_notFound_returns404() throws Exception {
            when(driverService.suspend(eq(administratorId), eq(applicationId), any(SuspendDriverRequest.class)))
                    .thenThrow(new DriverApplicationNotFoundException());

            mockMvc.perform(patch("/api/v1/drivers/applications/{id}/suspend", applicationId)
                            .with(jwt().jwt(j -> j.subject(administratorId.toString()))
                                    .authorities(new SimpleGrantedAuthority("ROLE_ADMINISTRATOR")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"Dangerous driving\"}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.code").value("DRIVER_APPLICATION_NOT_FOUND"));
        }

        @Test
        @DisplayName("PATCH /{id}/suspend when profile is not APPROVED returns 409 CONFLICT")
        void suspend_invalidTransition_returns409() throws Exception {
            when(driverService.suspend(eq(administratorId), eq(applicationId), any(SuspendDriverRequest.class)))
                    .thenThrow(new DriverApplicationConflictException("Only APPROVED driver profiles can be suspended"));

            mockMvc.perform(patch("/api/v1/drivers/applications/{id}/suspend", applicationId)
                            .with(jwt().jwt(j -> j.subject(administratorId.toString()))
                                    .authorities(new SimpleGrantedAuthority("ROLE_ADMINISTRATOR")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"Dangerous driving\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.code").value("DRIVER_APPLICATION_CONFLICT"));
        }
    }

    @Nested
    @DisplayName("CASE 8: Reactivate Driver Application Endpoint")
    class ReactivateDriverTests {

        @Test
        @DisplayName("PATCH /{id}/reactivate without token returns 401 TOKEN_MISSING")
        void reactivate_unauthenticated_returns401() throws Exception {
            mockMvc.perform(patch("/api/v1/drivers/applications/{id}/reactivate", applicationId))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.code").value("TOKEN_MISSING"));

            verifyNoInteractions(driverService);
        }

        @Test
        @DisplayName("PATCH /{id}/reactivate with ROLE_CUSTOMER returns 403 ACCESS_DENIED")
        void reactivate_customerAuthority_returns403() throws Exception {
            mockMvc.perform(patch("/api/v1/drivers/applications/{id}/reactivate", applicationId)
                            .with(jwt().jwt(j -> j.subject(customerId.toString()))
                                    .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

            verifyNoInteractions(driverService);
        }

        @Test
        @DisplayName("PATCH /{id}/reactivate with DRIVER_REVIEW (Reviewer) returns 403 ACCESS_DENIED")
        void reactivate_reviewerAuthority_returns403() throws Exception {
            mockMvc.perform(patch("/api/v1/drivers/applications/{id}/reactivate", applicationId)
                            .with(jwt().jwt(j -> j.subject(reviewerId.toString()))
                                    .authorities(new SimpleGrantedAuthority("DRIVER_REVIEW"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

            verifyNoInteractions(driverService);
        }

        @Test
        @DisplayName("PATCH /{id}/reactivate with ROLE_ADMINISTRATOR returns 200 OK")
        void reactivate_administratorAuthority_returns200() throws Exception {
            DriverApplicationResponse response = sampleResponse(DriverApplicationStatus.APPROVED);
            when(driverService.reactivate(administratorId, applicationId)).thenReturn(response);

            mockMvc.perform(patch("/api/v1/drivers/applications/{id}/reactivate", applicationId)
                            .with(jwt().jwt(j -> j.subject(administratorId.toString()))
                                    .authorities(new SimpleGrantedAuthority("ROLE_ADMINISTRATOR"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(applicationId.toString()))
                    .andExpect(jsonPath("$.status").value("APPROVED"));

            verify(driverService, times(1)).reactivate(administratorId, applicationId);
        }

        @Test
        @DisplayName("PATCH /{id}/reactivate when profile is not SUSPENDED returns 409 CONFLICT")
        void reactivate_invalidTransition_returns409() throws Exception {
            when(driverService.reactivate(administratorId, applicationId))
                    .thenThrow(new DriverApplicationConflictException("Only SUSPENDED driver profiles can be reactivated"));

            mockMvc.perform(patch("/api/v1/drivers/applications/{id}/reactivate", applicationId)
                            .with(jwt().jwt(j -> j.subject(administratorId.toString()))
                                    .authorities(new SimpleGrantedAuthority("ROLE_ADMINISTRATOR"))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.code").value("DRIVER_APPLICATION_CONFLICT"));
        }
    }

    @Nested
    @DisplayName("CASE 9: Driver Audit Logs Endpoint")
    class DriverAuditLogsTests {

        @Test
        @DisplayName("GET /{id}/audit-logs without token returns 401 TOKEN_MISSING")
        void getAuditLogs_unauthenticated_returns401() throws Exception {
            mockMvc.perform(get("/api/v1/drivers/applications/{id}/audit-logs", applicationId))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.code").value("TOKEN_MISSING"));

            verifyNoInteractions(driverService);
        }

        @Test
        @DisplayName("GET /{id}/audit-logs with ROLE_CUSTOMER returns 403 ACCESS_DENIED")
        void getAuditLogs_customerAuthority_returns403() throws Exception {
            mockMvc.perform(get("/api/v1/drivers/applications/{id}/audit-logs", applicationId)
                            .with(jwt().jwt(j -> j.subject(customerId.toString()))
                                    .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

            verifyNoInteractions(driverService);
        }

        @Test
        @DisplayName("GET /{id}/audit-logs with DRIVER_REVIEW authority returns 200 OK")
        void getAuditLogs_reviewerAuthority_returns200() throws Exception {
            DriverAuditLogResponse log1 = new DriverAuditLogResponse(
                    UUID.randomUUID(),
                    applicationId,
                    administratorId,
                    DriverAuditAction.SUSPEND,
                    DriverApplicationStatus.APPROVED,
                    DriverApplicationStatus.SUSPENDED,
                    "Violation report",
                    Instant.now()
            );

            when(driverService.getAuditLogs(applicationId)).thenReturn(List.of(log1));

            mockMvc.perform(get("/api/v1/drivers/applications/{id}/audit-logs", applicationId)
                            .with(jwt().jwt(j -> j.subject(reviewerId.toString()))
                                    .authorities(new SimpleGrantedAuthority("DRIVER_REVIEW"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].driverProfileId").value(applicationId.toString()))
                    .andExpect(jsonPath("$[0].action").value("SUSPEND"))
                    .andExpect(jsonPath("$[0].newStatus").value("SUSPENDED"));

            verify(driverService, times(1)).getAuditLogs(applicationId);
        }

        @Test
        @DisplayName("GET /{id}/audit-logs with ROLE_ADMINISTRATOR returns 200 OK")
        void getAuditLogs_administratorAuthority_returns200() throws Exception {
            when(driverService.getAuditLogs(applicationId)).thenReturn(List.of());

            mockMvc.perform(get("/api/v1/drivers/applications/{id}/audit-logs", applicationId)
                            .with(jwt().jwt(j -> j.subject(administratorId.toString()))
                                    .authorities(new SimpleGrantedAuthority("ROLE_ADMINISTRATOR"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray());

            verify(driverService, times(1)).getAuditLogs(applicationId);
        }

        @Test
        @DisplayName("GET /{id}/audit-logs when application not found returns 404 NOT_FOUND")
        void getAuditLogs_notFound_returns404() throws Exception {
            when(driverService.getAuditLogs(applicationId))
                    .thenThrow(new DriverApplicationNotFoundException());

            mockMvc.perform(get("/api/v1/drivers/applications/{id}/audit-logs", applicationId)
                            .with(jwt().jwt(j -> j.subject(reviewerId.toString()))
                                    .authorities(new SimpleGrantedAuthority("DRIVER_REVIEW"))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.code").value("DRIVER_APPLICATION_NOT_FOUND"));
        }
    }
}
