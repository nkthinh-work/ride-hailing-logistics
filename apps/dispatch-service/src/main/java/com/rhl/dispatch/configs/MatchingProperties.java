package com.rhl.dispatch.configs;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Configuration properties for candidate matching and radius expansion (FR-MAT-001..004, NFR-MNT-005).
 */
@ConfigurationProperties(prefix = "dispatch.matching")
public record MatchingProperties(
        List<Double> radiiKm,
        int gpsMaxAgeSeconds,
        int maxCandidates,
        int maxFutureSkewSeconds
) {
    public MatchingProperties {
        if (radiiKm == null || radiiKm.isEmpty()) {
            radiiKm = List.of(1.0, 3.0, 5.0);
        }
        if (gpsMaxAgeSeconds <= 0) {
            gpsMaxAgeSeconds = 60;
        }
        if (maxCandidates <= 0) {
            maxCandidates = 10;
        }
        if (maxFutureSkewSeconds <= 0) {
            maxFutureSkewSeconds = 15;
        }
    }

    public MatchingProperties() {
        this(List.of(1.0, 3.0, 5.0), 60, 10, 15);
    }
}
