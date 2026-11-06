package com.rhl.pricing.routing;

import java.math.BigDecimal;

/**
 * Abstraction interface for routing providers (FR-PRI-005, FR-PRI-006).
 * Separates fare calculation from external map services (Google Maps, OSRM, Mapbox).
 */
public interface RouteEstimateProvider {

    /**
     * Estimates route distance and duration between pickup and dropoff points.
     *
     * @param pickupLat  pickup latitude [-90, 90]
     * @param pickupLon  pickup longitude [-180, 180]
     * @param dropoffLat dropoff latitude [-90, 90]
     * @param dropoffLon dropoff longitude [-180, 180]
     * @return estimated route result
     * @throws IllegalArgumentException if coordinates are invalid
     */
    RouteEstimate estimateRoute(BigDecimal pickupLat, BigDecimal pickupLon,
                                BigDecimal dropoffLat, BigDecimal dropoffLon);
}
