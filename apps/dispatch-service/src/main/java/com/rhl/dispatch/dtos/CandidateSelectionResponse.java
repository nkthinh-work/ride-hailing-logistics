package com.rhl.dispatch.dtos;

import com.rhl.dispatch.models.CandidateSelectionResult;
import com.rhl.dispatch.models.DriverCandidate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public record CandidateSelectionResponse(
        UUID tripId,
        int searchRound,
        double searchRadiusKm,
        int candidateCount,
        List<DriverCandidateDto> candidates,
        Map<String, String> exclusions,
        Instant evaluatedAt
) {
    public static CandidateSelectionResponse from(CandidateSelectionResult result) {
        List<DriverCandidateDto> candidateDtos = result.candidates().stream()
                .map(DriverCandidateDto::from)
                .toList();

        Map<String, String> exclusionMap = result.exclusions().entrySet().stream()
                .collect(Collectors.toMap(
                        e -> e.getKey().toString(),
                        e -> e.getValue().name(),
                        (existing, replacement) -> existing
                ));

        return new CandidateSelectionResponse(
                result.tripId(),
                result.searchRound(),
                result.searchRadiusKm(),
                result.candidateCount(),
                candidateDtos,
                exclusionMap,
                result.evaluatedAt()
        );
    }

    public record DriverCandidateDto(
            UUID driverId,
            UUID vehicleId,
            String vehicleType,
            double longitude,
            double latitude,
            double distanceKm,
            Instant lastSeenTimestamp,
            Long estimatedArrivalSeconds
    ) {
        public static DriverCandidateDto from(DriverCandidate candidate) {
            return new DriverCandidateDto(
                    candidate.driverId(),
                    candidate.vehicleId(),
                    candidate.vehicleType().name(),
                    candidate.longitude(),
                    candidate.latitude(),
                    candidate.distanceKm(),
                    candidate.lastSeenTimestamp(),
                    candidate.estimatedArrivalSeconds()
            );
        }
    }
}
