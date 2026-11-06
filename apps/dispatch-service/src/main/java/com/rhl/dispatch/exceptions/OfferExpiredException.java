package com.rhl.dispatch.exceptions;

import java.time.Instant;
import java.util.UUID;

public class OfferExpiredException extends RuntimeException {

    private final UUID offerId;
    private final Instant expiresAt;
    private final Instant attemptedAt;

    public OfferExpiredException(UUID offerId, Instant expiresAt, Instant attemptedAt) {
        super(String.format("Offer %s has expired at %s (attempted at %s)", offerId, expiresAt, attemptedAt));
        this.offerId = offerId;
        this.expiresAt = expiresAt;
        this.attemptedAt = attemptedAt;
    }

    public UUID getOfferId() {
        return offerId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getAttemptedAt() {
        return attemptedAt;
    }
}
