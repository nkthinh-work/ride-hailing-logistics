package com.rhl.dispatch.dtos;

import com.rhl.dispatch.entities.DriverOffer;
import com.rhl.dispatch.models.OfferStatus;
import com.rhl.dispatch.models.ServiceType;

import java.time.Instant;
import java.util.UUID;

public record DriverOfferResponse(
        UUID id,
        UUID tripId,
        UUID driverId,
        UUID vehicleId,
        ServiceType serviceType,
        OfferStatus status,
        Instant offeredAt,
        Instant expiresAt,
        Instant acceptedAt,
        Instant rejectedAt,
        String rejectionReason,
        Instant revokedAt,
        String revocationReason,
        Long version
) {
    public static DriverOfferResponse from(DriverOffer offer) {
        return new DriverOfferResponse(
                offer.getId(),
                offer.getTripId(),
                offer.getDriverId(),
                offer.getVehicleId(),
                offer.getServiceType(),
                offer.getStatus(),
                offer.getOfferedAt(),
                offer.getExpiresAt(),
                offer.getAcceptedAt(),
                offer.getRejectedAt(),
                offer.getRejectionReason(),
                offer.getRevokedAt(),
                offer.getRevocationReason(),
                offer.getVersion()
        );
    }
}
