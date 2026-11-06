package com.rhl.trip.services;

import com.rhl.trip.dtos.CreateTripRequest;
import com.rhl.trip.dtos.QuoteValidationClientResponse;
import com.rhl.trip.entities.Trip;
import com.rhl.trip.repositories.TripRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Dedicated transactional boundary for local atomic Trip & Status History creation (DR-004, M05 Group 2).
 * Ensures DB transaction is NOT kept open during cross-service network calls.
 */
@Component
public class AtomicTripCreator {

    private final TripRepository tripRepository;

    public AtomicTripCreator(TripRepository tripRepository) {
        this.tripRepository = tripRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Trip createTripInTransaction(
            UUID customerId,
            CreateTripRequest request,
            QuoteValidationClientResponse quote) {

        Trip trip = Trip.builder()
                .id(UUID.randomUUID())
                .customerId(customerId)
                .idempotencyKey(request.idempotencyKey())
                .quoteId(quote.quoteId())
                .serviceType(quote.serviceType())
                .vehicleType(quote.vehicleType())
                .pickupLocation(quote.pickupLatitude(), quote.pickupLongitude(), quote.pickupAddress())
                .dropoffLocation(quote.dropoffLatitude(), quote.dropoffLongitude(), quote.dropoffAddress())
                .estimatedDistanceKm(quote.estimatedDistanceKm())
                .estimatedDurationMinutes(quote.estimatedDurationMinutes())
                .estimatedFare(quote.estimatedFare())
                .surgeMultiplier(quote.surgeMultiplier())
                .currency(quote.currency())
                .pricingRuleVersion(quote.pricingRuleVersion())
                .requestedAt(Instant.now())
                .build();

        return tripRepository.saveAndFlush(trip);
    }
}
