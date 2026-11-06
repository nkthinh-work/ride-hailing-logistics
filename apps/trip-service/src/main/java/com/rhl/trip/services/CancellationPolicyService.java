package com.rhl.trip.services;

import com.rhl.trip.entities.Trip;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Strategy interface for calculating trip cancellation fees (FR-CAN-003, FR-CAN-004).
 * Decouples the Trip Domain from evolving financial rules and payment settlement policies.
 */
public interface CancellationPolicyService {

    /**
     * Determines the cancellation fee for a cancellation request.
     *
     * @param trip       the target trip being cancelled
     * @param actorId    the user ID of the actor requesting cancellation
     * @param actorRole  the role of the actor (CUSTOMER, DRIVER, SUPPORT_STAFF, ADMINISTRATOR)
     * @param reasonCode cancellation reason code (FR-CAN-002)
     * @param now        cancellation timestamp (server clock)
     * @return cancellation fee amount (or null / ZERO if free cancellation applies)
     */
    BigDecimal calculateCancellationFee(Trip trip, UUID actorId, String actorRole, String reasonCode, Instant now);
}
