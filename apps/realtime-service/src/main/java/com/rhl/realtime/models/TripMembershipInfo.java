package com.rhl.realtime.models;

import java.util.UUID;

/**
 * Authoritative membership and state information for a trip room (FR-RT-001, FR-RT-008).
 */
public record TripMembershipInfo(
        UUID tripId,
        UUID customerId,
        UUID driverId,
        String status
) {
    public boolean isMember(UUID userId) {
        if (userId == null) {
            return false;
        }
        return (customerId != null && customerId.equals(userId))
                || (driverId != null && driverId.equals(userId));
    }

    public boolean isStreamable() {
        if (status == null) {
            return false;
        }
        return "ACCEPTED".equalsIgnoreCase(status)
                || "PICKING_UP".equalsIgnoreCase(status)
                || "ARRIVED".equalsIgnoreCase(status)
                || "IN_TRIP".equalsIgnoreCase(status);
    }
}
