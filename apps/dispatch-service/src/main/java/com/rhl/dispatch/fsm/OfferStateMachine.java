package com.rhl.dispatch.fsm;

import com.rhl.dispatch.exceptions.InvalidOfferStateTransitionException;
import com.rhl.dispatch.models.OfferStatus;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Finite State Machine for Driver Offer Lifecycle (SRS Section 4.5.3, FR-MAT-007..014).
 * Enforces strictly valid transitions and actor guards.
 */
public final class OfferStateMachine {

    private static final Map<OfferStatus, Set<OfferStatus>> ALLOWED_TRANSITIONS = new EnumMap<>(OfferStatus.class);

    static {
        // PENDING can transition to any terminal state depending on trigger
        ALLOWED_TRANSITIONS.put(OfferStatus.PENDING, EnumSet.of(
                OfferStatus.ACCEPTED,
                OfferStatus.REJECTED,
                OfferStatus.EXPIRED,
                OfferStatus.REVOKED
        ));

        // Terminal states cannot transition to anything
        ALLOWED_TRANSITIONS.put(OfferStatus.ACCEPTED, Collections.emptySet());
        ALLOWED_TRANSITIONS.put(OfferStatus.REJECTED, Collections.emptySet());
        ALLOWED_TRANSITIONS.put(OfferStatus.EXPIRED, Collections.emptySet());
        ALLOWED_TRANSITIONS.put(OfferStatus.REVOKED, Collections.emptySet());
    }

    private OfferStateMachine() {
        // Utility class
    }

    /**
     * Validates whether a state transition from fromStatus to toStatus by the given actor role is permissible.
     *
     * @param fromStatus current offer status
     * @param toStatus   target offer status
     * @param actorRole  actor attempting the transition (e.g. DRIVER, SYSTEM, ADMINISTRATOR)
     * @throws InvalidOfferStateTransitionException if transition or actor is invalid
     */
    public static void validateTransition(OfferStatus fromStatus, OfferStatus toStatus, String actorRole) {
        if (fromStatus == null || toStatus == null) {
            throw new IllegalArgumentException("FromStatus and ToStatus cannot be null");
        }
        if (actorRole == null || actorRole.isBlank()) {
            throw new IllegalArgumentException("Actor role is required");
        }

        // 1. Check if fromStatus is a terminal state
        if (fromStatus.isTerminal()) {
            throw new InvalidOfferStateTransitionException(
                    fromStatus, toStatus, actorRole,
                    "Cannot transition from terminal state " + fromStatus
            );
        }

        // 2. Check if transition is in allowed transition graph
        Set<OfferStatus> targets = ALLOWED_TRANSITIONS.getOrDefault(fromStatus, Collections.emptySet());
        if (!targets.contains(toStatus)) {
            throw new InvalidOfferStateTransitionException(
                    fromStatus, toStatus, actorRole,
                    "Transition from " + fromStatus + " to " + toStatus + " is not permitted in state machine"
            );
        }

        // 3. Actor guard validation
        String role = actorRole.toUpperCase().trim();
        switch (toStatus) {
            case ACCEPTED:
                if (!"DRIVER".equals(role) && !"SYSTEM".equals(role)) {
                    throw new InvalidOfferStateTransitionException(
                            fromStatus, toStatus, actorRole,
                            "Only DRIVER or SYSTEM actor can accept an offer"
                    );
                }
                break;
            case REJECTED:
                if (!"DRIVER".equals(role)) {
                    throw new InvalidOfferStateTransitionException(
                            fromStatus, toStatus, actorRole,
                            "Only DRIVER actor can reject an offer"
                    );
                }
                break;
            case EXPIRED:
                if (!"SYSTEM".equals(role)) {
                    throw new InvalidOfferStateTransitionException(
                            fromStatus, toStatus, actorRole,
                            "Only SYSTEM actor can expire an offer upon timeout"
                    );
                }
                break;
            case REVOKED:
                if (!"SYSTEM".equals(role) && !"ADMINISTRATOR".equals(role) && !"SUPPORT_STAFF".equals(role)) {
                    throw new InvalidOfferStateTransitionException(
                            fromStatus, toStatus, actorRole,
                            "Only SYSTEM, ADMINISTRATOR, or SUPPORT_STAFF can revoke an offer"
                    );
                }
                break;
            default:
                throw new InvalidOfferStateTransitionException(
                        fromStatus, toStatus, actorRole,
                        "Unknown target status: " + toStatus
                );
        }
    }
}
