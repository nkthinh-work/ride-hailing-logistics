package com.rhl.location.utils;

public final class GeoUtils {

    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    private GeoUtils() {
    }

    /**
     * Calculates the great-circle distance between two geographic coordinates using the Haversine formula.
     *
     * @param lat1 Latitude of point 1 in degrees
     * @param lon1 Longitude of point 1 in degrees
     * @param lat2 Latitude of point 2 in degrees
     * @param lon2 Longitude of point 2 in degrees
     * @return Distance in meters
     */
    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);

        double rLat1 = Math.toRadians(lat1);
        double rLat2 = Math.toRadians(lat2);

        double a = Math.sin(dLat / 2.0) * Math.sin(dLat / 2.0)
                + Math.sin(dLon / 2.0) * Math.sin(dLon / 2.0) * Math.cos(rLat1) * Math.cos(rLat2);

        double c = 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));

        return EARTH_RADIUS_METERS * c;
    }

    /**
     * Calculates speed in kilometers per hour based on distance and elapsed time.
     *
     * @param distanceMeters Distance traversed in meters
     * @param timeDeltaSeconds Elapsed time in seconds
     * @return Speed in km/h
     */
    public static double calculateSpeedKmh(double distanceMeters, double timeDeltaSeconds) {
        if (timeDeltaSeconds <= 0.0) {
            return 0.0;
        }
        return (distanceMeters / timeDeltaSeconds) * 3.6;
    }
}
