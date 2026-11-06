package com.rhl.dispatch.exceptions;

import java.util.UUID;

public class DriverOfferNotFoundException extends RuntimeException {

    private final UUID offerId;

    public DriverOfferNotFoundException(UUID offerId) {
        super("Driver offer not found: " + offerId);
        this.offerId = offerId;
    }

    public UUID getOfferId() {
        return offerId;
    }
}
