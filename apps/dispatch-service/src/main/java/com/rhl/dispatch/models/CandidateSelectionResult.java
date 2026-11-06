package com.rhl.dispatch.models;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Result of candidate selection pipeline execution (FR-MAT-001..005).
 * Contains ranked qualified candidates and diagnostic audit breakdown of excluded candidates.
 */
public record CandidateSelectionResult(
        UUID tripId,
        int searchRound,
        double searchRadiusKm,
        List<DriverCandidate> candidates,
        Map<UUID, CandidateExclusionReason> exclusions,
        Instant evaluatedAt
) {
    public CandidateSelectionResult {
        Objects.requireNonNull(tripId, "tripId must not be null");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt must not be null");

        candidates = candidates != null ? Collections.unmodifiableList(candidates) : List.of();
        exclusions = exclusions != null ? Collections.unmodifiableMap(exclusions) : Map.of();
    }

    public boolean hasCandidates() {
        return !candidates.isEmpty();
    }

    public int candidateCount() {
        return candidates.size();
    }

    public Optional<DriverCandidate> firstCandidate() {
        return candidates.isEmpty() ? Optional.empty() : Optional.of(candidates.getFirst());
    }

    public static CandidateSelectionResult empty(UUID tripId, int searchRound, double searchRadiusKm,
                                                 Map<UUID, CandidateExclusionReason> exclusions, Instant now) {
        return new CandidateSelectionResult(tripId, searchRound, searchRadiusKm, List.of(), exclusions, now);
    }
}
