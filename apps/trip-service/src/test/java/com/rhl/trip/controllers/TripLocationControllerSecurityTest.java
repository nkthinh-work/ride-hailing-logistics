package com.rhl.trip.controllers;

import com.rhl.trip.clients.LocationServiceClient;
import com.rhl.trip.configs.SecurityConfig;
import com.rhl.trip.entities.Trip;
import com.rhl.trip.exceptions.GlobalExceptionHandler;
import com.rhl.trip.models.TripStatus;
import com.rhl.trip.repositories.TripRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = TripLocationController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@DisplayName("TripLocationController Security & Snapshot Authorization Tests (FR-RT-008)")
class TripLocationControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TripRepository tripRepository;

    @MockitoBean
    private LocationServiceClient locationServiceClient;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private final UUID customerId = UUID.randomUUID();
    private final UUID driverId = UUID.randomUUID();
    private final UUID outsiderCustomerId = UUID.randomUUID();
    private final UUID outsiderDriverId = UUID.randomUUID();
    private final UUID tripId = UUID.randomUUID();

    private Trip mockTrip(TripStatus status) {
        Trip trip = org.mockito.Mockito.mock(Trip.class);
        when(trip.getId()).thenReturn(tripId);
        when(trip.getCustomerId()).thenReturn(customerId);
        when(trip.getDriverId()).thenReturn(driverId);
        when(trip.getStatus()).thenReturn(status);
        return trip;
    }

    @Test
    @DisplayName("GET /api/v1/trips/{tripId}/location by Customer Owner returns authoritative live GPS snapshot (200 OK)")
    void getTripLocation_customerOwner_success() throws Exception {
        Trip trip = mockTrip(TripStatus.IN_TRIP);
        when(tripRepository.findById(tripId)).thenReturn(Optional.of(trip));

        LocationServiceClient.DriverLocationSnapshot snapshot = new LocationServiceClient.DriverLocationSnapshot(
                driverId, "ONLINE", 105.8542, 21.0285, Instant.now(), UUID.randomUUID(), UUID.randomUUID()
        );
        when(locationServiceClient.getDriverLocation(driverId)).thenReturn(Optional.of(snapshot));

        mockMvc.perform(get("/api/v1/trips/{tripId}/location", tripId)
                        .with(jwt().jwt(builder -> builder.subject(customerId.toString()).claim("roles", java.util.List.of("CUSTOMER")))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tripId").value(tripId.toString()))
                .andExpect(jsonPath("$.driverId").value(driverId.toString()))
                .andExpect(jsonPath("$.tripStatus").value("IN_TRIP"))
                .andExpect(jsonPath("$.latitude").value(21.0285))
                .andExpect(jsonPath("$.longitude").value(105.8542))
                .andExpect(jsonPath("$.live").value(true));
    }

    @Test
    @DisplayName("GET /api/v1/trips/{tripId}/location by Assigned Driver returns authoritative live GPS snapshot (200 OK)")
    void getTripLocation_assignedDriver_success() throws Exception {
        Trip trip = mockTrip(TripStatus.IN_TRIP);
        when(tripRepository.findById(tripId)).thenReturn(Optional.of(trip));

        LocationServiceClient.DriverLocationSnapshot snapshot = new LocationServiceClient.DriverLocationSnapshot(
                driverId, "ONLINE", 105.8542, 21.0285, Instant.now(), UUID.randomUUID(), UUID.randomUUID()
        );
        when(locationServiceClient.getDriverLocation(driverId)).thenReturn(Optional.of(snapshot));

        mockMvc.perform(get("/api/v1/trips/{tripId}/location", tripId)
                        .with(jwt().jwt(builder -> builder.subject(driverId.toString()).claim("roles", java.util.List.of("DRIVER")))
                                .authorities(new SimpleGrantedAuthority("ROLE_DRIVER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tripId").value(tripId.toString()))
                .andExpect(jsonPath("$.latitude").value(21.0285))
                .andExpect(jsonPath("$.live").value(true));
    }

    @Test
    @DisplayName("GET /api/v1/trips/{tripId}/location by outsider Customer is FORBIDDEN (403)")
    void getTripLocation_outsiderCustomer_forbidden() throws Exception {
        Trip trip = mockTrip(TripStatus.IN_TRIP);
        when(tripRepository.findById(tripId)).thenReturn(Optional.of(trip));

        mockMvc.perform(get("/api/v1/trips/{tripId}/location", tripId)
                        .with(jwt().jwt(builder -> builder.subject(outsiderCustomerId.toString()).claim("roles", java.util.List.of("CUSTOMER")))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/v1/trips/{tripId}/location by outsider Driver is FORBIDDEN (403)")
    void getTripLocation_outsiderDriver_forbidden() throws Exception {
        Trip trip = mockTrip(TripStatus.IN_TRIP);
        when(tripRepository.findById(tripId)).thenReturn(Optional.of(trip));

        mockMvc.perform(get("/api/v1/trips/{tripId}/location", tripId)
                        .with(jwt().jwt(builder -> builder.subject(outsiderDriverId.toString()).claim("roles", java.util.List.of("DRIVER")))
                                .authorities(new SimpleGrantedAuthority("ROLE_DRIVER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/v1/trips/{tripId}/location when Trip is COMPLETED returns live=false")
    void getTripLocation_completedTrip_returnsNotLive() throws Exception {
        Trip trip = mockTrip(TripStatus.COMPLETED);
        when(tripRepository.findById(tripId)).thenReturn(Optional.of(trip));

        mockMvc.perform(get("/api/v1/trips/{tripId}/location", tripId)
                        .with(jwt().jwt(builder -> builder.subject(customerId.toString()).claim("roles", java.util.List.of("CUSTOMER")))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tripStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.live").value(false))
                .andExpect(jsonPath("$.latitude").doesNotExist());
    }
}
