package com.rhl.dispatch.controllers;

import com.rhl.dispatch.configs.SecurityConfig;
import com.rhl.dispatch.dtos.DriverOfferResponse;
import com.rhl.dispatch.exceptions.DriverOfferConflictException;
import com.rhl.dispatch.exceptions.DriverOfferNotFoundException;
import com.rhl.dispatch.exceptions.GlobalExceptionHandler;
import com.rhl.dispatch.exceptions.InvalidOfferStateTransitionException;
import com.rhl.dispatch.exceptions.OfferAccessDeniedException;
import com.rhl.dispatch.exceptions.OfferExpiredException;
import com.rhl.dispatch.models.OfferStatus;
import com.rhl.dispatch.models.ServiceType;
import com.rhl.dispatch.services.DriverOfferService;
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

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = DriverOfferController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@DisplayName("DriverOfferController Security & Authorization Tests")
class DriverOfferControllerSecurityTest {

    private static final String BASE_URL = "/api/v1/offers";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DriverOfferService driverOfferService;

    @MockitoBean
    private com.rhl.dispatch.services.MatchingOrchestrator matchingOrchestrator;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private final UUID driverId = UUID.randomUUID();
    private final UUID strangerDriverId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();
    private final UUID offerId = UUID.randomUUID();
    private final UUID tripId = UUID.randomUUID();

    private RequestPostProcessor asDriver(UUID id) {
        return jwt().jwt(builder -> builder
                .subject(id.toString())
                .claim("roles", java.util.List.of("DRIVER"))
        ).authorities(new SimpleGrantedAuthority("ROLE_DRIVER"));
    }

    private RequestPostProcessor asCustomer(UUID id) {
        return jwt().jwt(builder -> builder
                .subject(id.toString())
                .claim("roles", java.util.List.of("CUSTOMER"))
        ).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    private DriverOfferResponse sampleResponse(OfferStatus status) {
        return new DriverOfferResponse(
                offerId,
                tripId,
                driverId,
                UUID.randomUUID(),
                ServiceType.RIDE,
                status,
                Instant.now(),
                Instant.now().plusSeconds(15),
                status == OfferStatus.ACCEPTED ? Instant.now() : null,
                status == OfferStatus.REJECTED ? Instant.now() : null,
                status == OfferStatus.REJECTED ? "TIRED" : null,
                null,
                null,
                0L
        );
    }

    @Nested
    @DisplayName("Accept Offer Security Tests")
    class AcceptOfferSecurity {

        @Test
        @DisplayName("Unauthenticated request returns 401 Unauthorized")
        void unauthenticated_Returns401() throws Exception {
            mockMvc.perform(post(BASE_URL + "/" + offerId + "/accept"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Customer role returns 403 Forbidden")
        void customerRole_Returns403() throws Exception {
            mockMvc.perform(post(BASE_URL + "/" + offerId + "/accept")
                            .with(asCustomer(customerId)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Driver role can accept offer and receives 200 OK")
        void driverRole_CanAccept_Returns200() throws Exception {
            when(matchingOrchestrator.acceptOffer(eq(offerId), eq(driverId)))
                    .thenReturn(com.rhl.dispatch.models.MatchingOrchestrationResult.alreadyMatched(tripId, driverId));
            when(driverOfferService.getOfferById(eq(offerId)))
                    .thenReturn(sampleResponse(OfferStatus.ACCEPTED));

            mockMvc.perform(post(BASE_URL + "/" + offerId + "/accept")
                            .with(asDriver(driverId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(offerId.toString()))
                    .andExpect(jsonPath("$.status").value("ACCEPTED"));
        }

        @Test
        @DisplayName("Stranger driver accepting someone else's offer returns 403 Forbidden")
        void strangerDriver_Returns403() throws Exception {
            when(matchingOrchestrator.acceptOffer(eq(offerId), eq(strangerDriverId)))
                    .thenThrow(new OfferAccessDeniedException(offerId, strangerDriverId));

            mockMvc.perform(post(BASE_URL + "/" + offerId + "/accept")
                            .with(asDriver(strangerDriverId)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("OFFER_ACCESS_DENIED"));
        }

        @Test
        @DisplayName("Accepting expired offer returns 409 Conflict with OFFER_EXPIRED code")
        void acceptExpired_Returns409() throws Exception {
            when(matchingOrchestrator.acceptOffer(eq(offerId), eq(driverId)))
                    .thenThrow(new OfferExpiredException(offerId, Instant.now().minusSeconds(5), Instant.now()));

            mockMvc.perform(post(BASE_URL + "/" + offerId + "/accept")
                            .with(asDriver(driverId)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("OFFER_EXPIRED"));
        }

        @Test
        @DisplayName("Accepting when another driver already won returns 409 Conflict")
        void anotherDriverWon_Returns409() throws Exception {
            when(matchingOrchestrator.acceptOffer(eq(offerId), eq(driverId)))
                    .thenThrow(new DriverOfferConflictException("ANOTHER_DRIVER_ALREADY_ACCEPTED", "Another driver won"));

            mockMvc.perform(post(BASE_URL + "/" + offerId + "/accept")
                            .with(asDriver(driverId)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ANOTHER_DRIVER_ALREADY_ACCEPTED"));
        }
    }

    @Nested
    @DisplayName("Reject Offer Security Tests")
    class RejectOfferSecurity {

        @Test
        @DisplayName("Unauthenticated request returns 401 Unauthorized")
        void unauthenticated_Returns401() throws Exception {
            mockMvc.perform(post(BASE_URL + "/" + offerId + "/reject"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Customer role returns 403 Forbidden")
        void customerRole_Returns403() throws Exception {
            mockMvc.perform(post(BASE_URL + "/" + offerId + "/reject")
                            .with(asCustomer(customerId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\": \"NOT_INTERESTED\"}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Driver role can reject offer and receives 200 OK")
        void driverRole_CanReject_Returns200() throws Exception {
            when(matchingOrchestrator.rejectOffer(eq(offerId), eq(driverId), eq("NOT_INTERESTED")))
                    .thenReturn(com.rhl.dispatch.models.MatchingOrchestrationResult.noDriver(tripId, 1, 1000, "Rejected"));
            when(driverOfferService.getOfferById(eq(offerId)))
                    .thenReturn(sampleResponse(OfferStatus.REJECTED));

            mockMvc.perform(post(BASE_URL + "/" + offerId + "/reject")
                            .with(asDriver(driverId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\": \"NOT_INTERESTED\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("REJECTED"));
        }
    }

    @Nested
    @DisplayName("Pending Offer Query Security Tests")
    class PendingOfferSecurity {

        @Test
        @DisplayName("Unauthenticated request returns 401 Unauthorized")
        void unauthenticated_Returns401() throws Exception {
            mockMvc.perform(get(BASE_URL + "/pending"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Customer role returns 403 Forbidden")
        void customerRole_Returns403() throws Exception {
            mockMvc.perform(get(BASE_URL + "/pending")
                            .with(asCustomer(customerId)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Driver receives 200 OK when pending offer exists")
        void driverWithPendingOffer_Returns200() throws Exception {
            when(driverOfferService.getPendingOfferForDriver(driverId))
                    .thenReturn(Optional.of(sampleResponse(OfferStatus.PENDING)));

            mockMvc.perform(get(BASE_URL + "/pending")
                            .with(asDriver(driverId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PENDING"));
        }

        @Test
        @DisplayName("Driver receives 404 Not Found when no pending offer exists")
        void driverWithoutPendingOffer_Returns404() throws Exception {
            when(driverOfferService.getPendingOfferForDriver(driverId))
                    .thenReturn(Optional.empty());

            mockMvc.perform(get(BASE_URL + "/pending")
                            .with(asDriver(driverId)))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("Get Offer Details Security Tests")
    class GetOfferSecurity {

        @Test
        @DisplayName("Driver can view their own offer")
        void driverViewsOwnOffer_Returns200() throws Exception {
            when(driverOfferService.getOfferById(offerId))
                    .thenReturn(sampleResponse(OfferStatus.PENDING));

            mockMvc.perform(get(BASE_URL + "/" + offerId)
                            .with(asDriver(driverId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(offerId.toString()));
        }

        @Test
        @DisplayName("Driver cannot view someone else's offer -> 403 Forbidden")
        void driverViewsStrangerOffer_Returns403() throws Exception {
            when(driverOfferService.getOfferById(offerId))
                    .thenReturn(sampleResponse(OfferStatus.PENDING));

            mockMvc.perform(get(BASE_URL + "/" + offerId)
                            .with(asDriver(strangerDriverId)))
                    .andExpect(status().isForbidden());
        }
    }
}
