package com.rhl.trip.dtos;

import com.rhl.trip.entities.Trip;
import com.rhl.trip.models.ServiceType;
import com.rhl.trip.models.TripStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Enriched Trip response DTO representing current trip state and snapshot (DR-004, FR-TRIP-001..015, FR-CAN-001..007).
 */
public record TripResponse(
        UUID tripId,
        UUID customerId,
        UUID driverId,
        TripStatus status,
        UUID quoteId,
        String idempotencyKey,
        ServiceType serviceType,
        String vehicleType,
        BigDecimal pickupLatitude,
        BigDecimal pickupLongitude,
        String pickupAddress,
        BigDecimal dropoffLatitude,
        BigDecimal dropoffLongitude,
        String dropoffAddress,
        BigDecimal estimatedDistanceKm,
        Integer estimatedDurationMinutes,
        BigDecimal estimatedFare,
        BigDecimal surgeMultiplier,
        String currency,
        String pricingRuleVersion,
        Instant requestedAt,
        Instant acceptedAt,
        Instant arrivedAt,
        Instant startedAt,
        Instant completedAt,
        Instant cancelledAt,
        String cancellationReason,
        UUID cancelledBy,
        String cancellationActorRole,
        BigDecimal cancellationFee,
        Long version
) {

    /**
     * Backwards-compatible 27-parameter constructor for existing tests and consumers.
     */
    public TripResponse(
            UUID tripId,
            UUID customerId,
            UUID driverId,
            TripStatus status,
            UUID quoteId,
            String idempotencyKey,
            ServiceType serviceType,
            String vehicleType,
            BigDecimal pickupLatitude,
            BigDecimal pickupLongitude,
            String pickupAddress,
            BigDecimal dropoffLatitude,
            BigDecimal dropoffLongitude,
            String dropoffAddress,
            BigDecimal estimatedDistanceKm,
            Integer estimatedDurationMinutes,
            BigDecimal estimatedFare,
            BigDecimal surgeMultiplier,
            String currency,
            String pricingRuleVersion,
            Instant requestedAt,
            Instant acceptedAt,
            Instant arrivedAt,
            Instant startedAt,
            Instant completedAt,
            Instant cancelledAt,
            Long version
    ) {
        this(
                tripId, customerId, driverId, status, quoteId, idempotencyKey,
                serviceType, vehicleType, pickupLatitude, pickupLongitude, pickupAddress,
                dropoffLatitude, dropoffLongitude, dropoffAddress,
                estimatedDistanceKm, estimatedDurationMinutes, estimatedFare,
                surgeMultiplier, currency, pricingRuleVersion, requestedAt,
                acceptedAt, arrivedAt, startedAt, completedAt, cancelledAt,
                null, null, null, null, version
        );
    }

    public static TripResponse from(Trip trip) {
        return new TripResponse(
                trip.getId(),
                trip.getCustomerId(),
                trip.getDriverId(),
                trip.getStatus(),
                trip.getQuoteId(),
                trip.getIdempotencyKey(),
                trip.getServiceType(),
                trip.getVehicleType(),
                trip.getPickupLatitude(),
                trip.getPickupLongitude(),
                trip.getPickupAddress(),
                trip.getDropoffLatitude(),
                trip.getDropoffLongitude(),
                trip.getDropoffAddress(),
                trip.getEstimatedDistanceKm(),
                trip.getEstimatedDurationMinutes(),
                trip.getEstimatedFare(),
                trip.getSurgeMultiplier(),
                trip.getCurrency(),
                trip.getPricingRuleVersion(),
                trip.getRequestedAt(),
                trip.getAcceptedAt(),
                trip.getArrivedAt(),
                trip.getStartedAt(),
                trip.getCompletedAt(),
                trip.getCancelledAt(),
                trip.getCancellationReason(),
                trip.getCancelledBy(),
                trip.getCancellationActorRole(),
                trip.getCancellationFee(),
                trip.getVersion()
        );
    }
}
