package com.rhl.dispatch.exceptions;

import java.util.UUID;

public class OfferAccessDeniedException extends RuntimeException {

    private final UUID offerId;
    private final UUID driverId;

    public OfferAccessDeniedException(UUID offerId, UUID driverId) {
        super(String.format("Driver %s is not authorized to access or resolve offer %s", driverId, offerId));
        this.offerId = offerId;
        this.driverId = driverId;
    }

    public UUID getOfferId() {
        return offerId;
    }

    public UUID getDriverId() {
        return driverId;
    }
}
