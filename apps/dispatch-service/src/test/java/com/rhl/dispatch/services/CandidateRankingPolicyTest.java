package com.rhl.dispatch.services;

import com.rhl.dispatch.models.DriverCandidate;
import com.rhl.dispatch.models.VehicleType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CandidateRankingPolicy Unit Tests")
class CandidateRankingPolicyTest {

    private final CandidateRankingPolicy policy = new CandidateRankingPolicy.DefaultDistanceAndEtaRankingPolicy();
    private final Instant now = Instant.parse("2026-10-24T10:00:00Z");

    @Test
    @DisplayName("Ranks candidates by distance ascending")
    void rank_ordersByDistanceAscending() {
        DriverCandidate far = createCandidate(UUID.fromString("11111111-1111-1111-1111-111111111111"), 2.5, null);
        DriverCandidate close = createCandidate(UUID.fromString("22222222-2222-2222-2222-222222222222"), 0.5, null);
        DriverCandidate mid = createCandidate(UUID.fromString("33333333-3333-3333-3333-333333333333"), 1.2, null);

        List<DriverCandidate> ranked = policy.rank(List.of(far, close, mid));

        assertEquals(3, ranked.size());
        assertEquals(close.driverId(), ranked.get(0).driverId());
        assertEquals(mid.driverId(), ranked.get(1).driverId());
        assertEquals(far.driverId(), ranked.get(2).driverId());
    }

    @Test
    @DisplayName("Ranks candidates by ETA when distances are identical")
    void rank_ordersByEtaWhenDistanceEqual() {
        DriverCandidate slower = createCandidate(UUID.fromString("11111111-1111-1111-1111-111111111111"), 1.0, 300L);
        DriverCandidate faster = createCandidate(UUID.fromString("22222222-2222-2222-2222-222222222222"), 1.0, 120L);

        List<DriverCandidate> ranked = policy.rank(List.of(slower, faster));

        assertEquals(2, ranked.size());
        assertEquals(faster.driverId(), ranked.get(0).driverId());
        assertEquals(slower.driverId(), ranked.get(1).driverId());
    }

    @Test
    @DisplayName("Deterministic tie-breaking on driverId when distance and ETA are identical")
    void rank_deterministicTieBreaking() {
        UUID idA = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID idB = UUID.fromString("00000000-0000-0000-0000-000000000002");

        DriverCandidate candB = createCandidate(idB, 1.0, 180L);
        DriverCandidate candA = createCandidate(idA, 1.0, 180L);

        List<DriverCandidate> ranked1 = policy.rank(List.of(candB, candA));
        List<DriverCandidate> ranked2 = policy.rank(List.of(candA, candB));

        assertEquals(idA, ranked1.get(0).driverId());
        assertEquals(idB, ranked1.get(1).driverId());
        assertEquals(ranked1, ranked2, "Ranking must be deterministic regardless of input permutation");
    }

    @Test
    @DisplayName("Handles empty and null candidate lists safely")
    void rank_handlesEmpty() {
        assertTrue(policy.rank(List.of()).isEmpty());
        assertTrue(policy.rank(null).isEmpty());
    }

    private DriverCandidate createCandidate(UUID driverId, double distanceKm, Long etaSeconds) {
        return DriverCandidate.builder()
                .driverId(driverId)
                .vehicleId(UUID.randomUUID())
                .vehicleType(VehicleType.MOTORBIKE)
                .location(105.834, 21.028)
                .distanceKm(distanceKm)
                .lastSeenTimestamp(now)
                .estimatedArrivalSeconds(etaSeconds)
                .build();
    }
}
