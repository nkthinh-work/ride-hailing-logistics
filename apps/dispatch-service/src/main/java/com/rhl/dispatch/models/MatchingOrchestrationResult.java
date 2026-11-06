package com.rhl.dispatch.models;

import java.util.UUID;

/**
 * Result of matching orchestration attempt (M06 Group 3, FR-MAT-001..015).
 */
public record MatchingOrchestrationResult(
        UUID tripId,
        MatchingSessionStatus sessionStatus,
        UUID offerId,
        UUID driverId,
        int roundNumber,
        int radiusMeters,
        String outcome,
        String message
) {
    public static MatchingOrchestrationResult offerDispatched(
            UUID tripId, UUID offerId, UUID driverId, int roundNumber, int radiusMeters) {
        return new MatchingOrchestrationResult(
                tripId, MatchingSessionStatus.SEARCHING, offerId, driverId, roundNumber, radiusMeters,
                "OFFER_DISPATCHED", "Driver offer created and dispatched to candidate"
        );
    }

    public static MatchingOrchestrationResult alreadyMatched(UUID tripId, UUID driverId) {
        return new MatchingOrchestrationResult(
                tripId, MatchingSessionStatus.MATCHED, null, driverId, 0, 0,
                "ALREADY_MATCHED", "Trip already has a matched/assigned driver"
        );
    }

    public static MatchingOrchestrationResult noDriver(UUID tripId, int roundNumber, int radiusMeters, String reason) {
        return new MatchingOrchestrationResult(
                tripId, MatchingSessionStatus.EXPIRED, null, null, roundNumber, radiusMeters,
                "NO_DRIVER", reason != null ? reason : "Exhausted all matching rounds without finding an eligible driver"
        );
    }

    public static MatchingOrchestrationResult cancelled(UUID tripId, String reason) {
        return new MatchingOrchestrationResult(
                tripId, MatchingSessionStatus.CANCELLED, null, null, 0, 0,
                "CANCELLED", reason != null ? reason : "Matching cancelled"
        );
    }
}
