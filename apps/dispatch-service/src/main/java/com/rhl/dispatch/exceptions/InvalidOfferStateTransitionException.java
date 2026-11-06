package com.rhl.dispatch.exceptions;

import com.rhl.dispatch.models.OfferStatus;

public class InvalidOfferStateTransitionException extends RuntimeException {

    private final OfferStatus fromStatus;
    private final OfferStatus toStatus;
    private final String actorRole;

    public InvalidOfferStateTransitionException(OfferStatus fromStatus, OfferStatus toStatus, String actorRole, String message) {
        super(String.format("Invalid offer state transition from %s to %s by [%s]: %s", fromStatus, toStatus, actorRole, message));
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.actorRole = actorRole;
    }

    public OfferStatus getFromStatus() {
        return fromStatus;
    }

    public OfferStatus getToStatus() {
        return toStatus;
    }

    public String getActorRole() {
        return actorRole;
    }
}
