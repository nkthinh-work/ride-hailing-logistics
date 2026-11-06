package com.rhl.trip.controllers;

import com.rhl.trip.dtos.CreateTripRequest;
import com.rhl.trip.dtos.TripResponse;
import com.rhl.trip.services.BookingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller for customer Trip Booking and queries (M05 Group 2, FR-TRIP-001..002).
 */
@RestController
@RequestMapping("/api/v1/trips")
public class TripBookingController {

    private final BookingService bookingService;

    public TripBookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    /**
     * Creates a new trip booking based on an authenticated customer's quote.
     */
    @PostMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<TripResponse> bookTrip(
            @Valid @RequestBody CreateTripRequest request,
            @AuthenticationPrincipal Jwt jwt) {

        UUID customerId = UUID.fromString(jwt.getSubject());
        TripResponse response = bookingService.bookTrip(customerId, request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    /**
     * Retrieves trip details for the authenticated customer.
     */
    @GetMapping("/{tripId}")
    @PreAuthorize("hasRole('CUSTOMER') or hasRole('ADMINISTRATOR')")
    public ResponseEntity<TripResponse> getTrip(
            @PathVariable UUID tripId,
            @AuthenticationPrincipal Jwt jwt) {

        UUID customerId = UUID.fromString(jwt.getSubject());
        TripResponse response = bookingService.getTripById(tripId, customerId);

        return ResponseEntity.ok(response);
    }
}
