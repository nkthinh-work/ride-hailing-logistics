package com.rhl.pricing.routing;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Deterministic simulated route provider for DEV and TEST environments.
 *
 * <p><strong>WARNING:</strong> This is a DEV/TEST simulation fixture only.
 * It does NOT represent real road network distances and must NOT be used
 * for issuing production fare quotes.
 */
@Component
public class SimulatedRouteEstimateProvider implements RouteEstimateProvider {

    public static final String PROVIDER_NAME = "SIMULATED_DEV_FIXTURE";
    private static final double EARTH_RADIUS_METERS = 6371000.0;
    private static final double SIMULATED_SPEED_MPS = 8.333; // ~30 km/h in meters/second

    @Override
    public RouteEstimate estimateRoute(BigDecimal pickupLat, BigDecimal pickupLon,
                                        BigDecimal dropoffLat, BigDecimal dropoffLon) {
        validateCoordinates(pickupLat, pickupLon);
        validateCoordinates(dropoffLat, dropoffLon);

        // Compute deterministic great-circle metric as a baseline for the simulation fixture
        double lat1 = Math.toRadians(pickupLat.doubleValue());
        double lon1 = Math.toRadians(pickupLon.doubleValue());
        double lat2 = Math.toRadians(dropoffLat.doubleValue());
        double lon2 = Math.toRadians(dropoffLon.doubleValue());

        double dLat = lat2 - lat1;
        double dLon = lon2 - lon1;

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(lat1) * Math.cos(lat2) *
                        Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        long distanceMeters = Math.round(EARTH_RADIUS_METERS * c);

        // Simulated ETA assuming average urban speed 30 km/h (minimum 60s if points are identical)
        long durationSeconds = distanceMeters > 0
                ? Math.max(60, Math.round(distanceMeters / SIMULATED_SPEED_MPS))
                : 0;

        return new RouteEstimate(distanceMeters, durationSeconds, PROVIDER_NAME, true);
    }

    private void validateCoordinates(BigDecimal lat, BigDecimal lon) {
        Objects.requireNonNull(lat, "latitude must not be null");
        Objects.requireNonNull(lon, "longitude must not be null");

        if (lat.compareTo(BigDecimal.valueOf(-90)) < 0 || lat.compareTo(BigDecimal.valueOf(90)) > 0) {
            throw new IllegalArgumentException("Latitude out of range [-90, 90]: " + lat);
        }
        if (lon.compareTo(BigDecimal.valueOf(-180)) < 0 || lon.compareTo(BigDecimal.valueOf(180)) > 0) {
            throw new IllegalArgumentException("Longitude out of range [-180, 180]: " + lon);
        }
    }
}
