package com.rhl.trip.dtos;

import com.rhl.trip.models.ServiceType;

import java.util.UUID;

public record QuoteValidationClientRequest(
        UUID quoteId,
        UUID customerId,
        ServiceType serviceType,
        String vehicleType,
        boolean customerConfirmedSurge
) {
}
