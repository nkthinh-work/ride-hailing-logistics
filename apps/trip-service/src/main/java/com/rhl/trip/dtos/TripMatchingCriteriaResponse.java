package com.rhl.trip.dtos;

import com.rhl.trip.models.TripStatus;

import java.math.BigDecimal;
import java.util.UUID;

public record TripMatchingCriteriaResponse(
        UUID tripId,
        UUID customerId,
        TripStatus status,
        BigDecimal pickupLatitude,
        BigDecimal pickupLongitude,
        String serviceType,
        String vehicleType
) {}
