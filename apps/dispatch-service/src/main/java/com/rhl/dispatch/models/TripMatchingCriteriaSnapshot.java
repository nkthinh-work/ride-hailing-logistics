package com.rhl.dispatch.models;

import java.util.UUID;

public record TripMatchingCriteriaSnapshot(
        UUID tripId,
        UUID customerId,
        String status,
        Double pickupLatitude,
        Double pickupLongitude,
        String serviceType,
        String vehicleType
) {}
