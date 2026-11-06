package com.rhl.dispatch.services;

import com.rhl.dispatch.models.DriverCandidate;

import java.util.Comparator;
import java.util.List;

/**
 * Strategy for ranking driver candidates (FR-MAT-003).
 * Enforces primary distance sorting, optional ETA sorting, and deterministic tie-breaking.
 */
public interface CandidateRankingPolicy {

    /**
     * Ranks candidates deterministically.
     *
     * @param candidates list of eligible candidates
     * @return sorted list of candidates
     */
    List<DriverCandidate> rank(List<DriverCandidate> candidates);

    /**
     * Default ranking policy:
     * 1. Distance (ascending)
     * 2. ETA seconds (ascending, if available)
     * 3. Driver ID string (ascending, deterministic tie-breaking)
     */
    class DefaultDistanceAndEtaRankingPolicy implements CandidateRankingPolicy {

        private static final Comparator<DriverCandidate> COMPARATOR = Comparator
                .comparingDouble(DriverCandidate::distanceKm)
                .thenComparing((c1, c2) -> {
                    if (c1.estimatedArrivalSeconds() != null && c2.estimatedArrivalSeconds() != null) {
                        return Long.compare(c1.estimatedArrivalSeconds(), c2.estimatedArrivalSeconds());
                    }
                    return 0;
                })
                .thenComparing(c -> c.driverId().toString());

        @Override
        public List<DriverCandidate> rank(List<DriverCandidate> candidates) {
            if (candidates == null || candidates.isEmpty()) {
                return List.of();
            }
            return candidates.stream()
                    .sorted(COMPARATOR)
                    .toList();
        }
    }
}
