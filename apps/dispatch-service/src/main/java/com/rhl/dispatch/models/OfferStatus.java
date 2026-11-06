package com.rhl.dispatch.models;

/**
 * Driver Offer Lifecycle States (SRS-RHL-002, Section 4.5.3, FR-MAT-007..014).
 */
public enum OfferStatus {
    /**
     * Offer has been created and dispatched to the driver; awaiting response.
     */
    PENDING,

    /**
     * Driver has accepted the offer. Winning offer for the trip.
     */
    ACCEPTED,

    /**
     * Driver actively declined the offer.
     */
    REJECTED,

    /**
     * Offer response window elapsed without driver response.
     */
    EXPIRED,

    /**
     * Offer was retracted (e.g. Trip cancelled or competing offer accepted).
     */
    REVOKED;

    public boolean isTerminal() {
        return this == ACCEPTED || this == REJECTED || this == EXPIRED || this == REVOKED;
    }
}
