package com.rhl.realtime.services;

import com.rhl.realtime.models.TripMembershipInfo;

import java.util.Optional;
import java.util.UUID;

/**
 * Provider interface for authoritative trip room membership resolution (FR-RT-001, FR-RT-008).
 */
public interface TripMembershipProvider {

    /**
     * Retrieves authoritative trip membership and lifecycle status for a given trip.
     */
    Optional<TripMembershipInfo> getTripMembership(UUID tripId);

    /**
     * Resolves the active trip ID currently assigned to a driver, if any.
     */
    Optional<UUID> findActiveTripByDriverId(UUID driverId);

    /**
     * Invalidates any cached membership information when a trip completes or cancels.
     */
    void invalidateTrip(UUID tripId);
}
