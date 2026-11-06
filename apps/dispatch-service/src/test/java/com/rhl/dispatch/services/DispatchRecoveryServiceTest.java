package com.rhl.dispatch.services;

import com.rhl.dispatch.entities.DriverOffer;
import com.rhl.dispatch.entities.MatchingSession;
import com.rhl.dispatch.models.MatchingSessionStatus;
import com.rhl.dispatch.models.OfferStatus;
import com.rhl.dispatch.models.ServiceType;
import com.rhl.dispatch.models.TripAssignmentResult;
import com.rhl.dispatch.providers.TripAssignmentProvider;
import com.rhl.dispatch.repositories.DriverOfferRepository;
import com.rhl.dispatch.repositories.MatchingSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DispatchRecoveryService Unit Tests")
class DispatchRecoveryServiceTest {

    @Mock
    private DriverOfferRepository driverOfferRepository;

    @Mock
    private MatchingSessionRepository matchingSessionRepository;

    @Mock
    private DriverOfferService driverOfferService;

    @Mock
    private MatchingOrchestrator matchingOrchestrator;

    @Mock
    private TripAssignmentProvider tripAssignmentProvider;

    private Clock clock;
    private DispatchRecoveryService recoveryService;

    private final Instant fixedNow = Instant.parse("2026-10-25T12:00:00Z");

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(fixedNow, ZoneOffset.UTC);
        recoveryService = new DispatchRecoveryService(
                driverOfferRepository,
                matchingSessionRepository,
                driverOfferService,
                matchingOrchestrator,
                tripAssignmentProvider,
                clock
        );
    }

    private DriverOffer createAcceptedPendingOffer(UUID tripId, UUID driverId, UUID vehicleId, Instant offeredAt) {
        return DriverOffer.builder()
                .id(UUID.randomUUID())
                .tripId(tripId)
                .driverId(driverId)
                .vehicleId(vehicleId)
                .serviceType(ServiceType.RIDE)
                .offeredAt(offeredAt)
                .expiresAt(offeredAt.plusSeconds(30))
                .actor(driverId, "SYSTEM")
                .build();
    }

    @Nested
    @DisplayName("1. Stale PENDING Assignment Recovery (Section 5, Cases 1-7)")
    class StalePendingAssignmentRecoveryTests {

        @Test
        @DisplayName("Case 1: Trip already committed as ACCEPTED with same driver -> marks ASSIGNED")
        void recoverStalePending_case1_tripAlreadyCommitted_marksAssigned() {
            UUID tripId = UUID.randomUUID();
            UUID driverId = UUID.randomUUID();
            UUID vehicleId = UUID.randomUUID();
            DriverOffer offer = createAcceptedPendingOffer(tripId, driverId, vehicleId, fixedNow.minusSeconds(60));
            offer.accept(driverId, fixedNow.minusSeconds(40));

            when(driverOfferRepository.findPendingAssignmentsForRecovery(any(Instant.class)))
                    .thenReturn(List.of(offer));
            when(driverOfferRepository.findById(offer.getId())).thenReturn(Optional.of(offer));

            when(tripAssignmentProvider.assignDriver(tripId, driverId, vehicleId, offer.getId()))
                    .thenReturn(TripAssignmentResult.success(tripId, driverId, vehicleId, "ACCEPTED"));

            MatchingSession activeSession = new MatchingSession(UUID.randomUUID(), tripId, 1, 1000, MatchingSessionStatus.SEARCHING, fixedNow.minusSeconds(60));
            when(matchingSessionRepository.findByTripIdAndStatus(tripId, MatchingSessionStatus.SEARCHING))
                    .thenReturn(Optional.of(activeSession));

            int recovered = recoveryService.recoverStalePendingAssignments(Duration.ofSeconds(15));

            assertThat(recovered).isEqualTo(1);
            verify(driverOfferService, times(1)).markAssignmentAssigned(offer.getId());
            verify(driverOfferService, times(1)).revokeAllPendingOffersForTrip(eq(tripId), anyString(), eq("SYSTEM"));
            assertThat(activeSession.getStatus()).isEqualTo(MatchingSessionStatus.MATCHED);
            verify(matchingSessionRepository, times(1)).save(activeSession);
        }

        @Test
        @DisplayName("Case 4: Trip is still MATCHING -> retries assignment idempotently, succeeds -> marks ASSIGNED")
        void recoverStalePending_case4_tripStillMatching_retrySucceeds_marksAssigned() {
            UUID tripId = UUID.randomUUID();
            UUID driverId = UUID.randomUUID();
            UUID vehicleId = UUID.randomUUID();
            DriverOffer offer = createAcceptedPendingOffer(tripId, driverId, vehicleId, fixedNow.minusSeconds(60));
            offer.accept(driverId, fixedNow.minusSeconds(40));

            when(driverOfferRepository.findPendingAssignmentsForRecovery(any(Instant.class)))
                    .thenReturn(List.of(offer));
            when(driverOfferRepository.findById(offer.getId())).thenReturn(Optional.of(offer));

            when(tripAssignmentProvider.assignDriver(tripId, driverId, vehicleId, offer.getId()))
                    .thenReturn(TripAssignmentResult.success(tripId, driverId, vehicleId, "ACCEPTED"));

            int recovered = recoveryService.recoverStalePendingAssignments(Duration.ofSeconds(15));

            assertThat(recovered).isEqualTo(1);
            verify(tripAssignmentProvider, times(1)).assignDriver(tripId, driverId, vehicleId, offer.getId());
            verify(driverOfferService, times(1)).markAssignmentAssigned(offer.getId());
        }

        @Test
        @DisplayName("Case 4b: Trip is CANCELLED -> marks offer FAILED and session CANCELLED")
        void recoverStalePending_case4b_tripCancelled_marksFailed() {
            UUID tripId = UUID.randomUUID();
            UUID driverId = UUID.randomUUID();
            UUID vehicleId = UUID.randomUUID();
            DriverOffer offer = createAcceptedPendingOffer(tripId, driverId, vehicleId, fixedNow.minusSeconds(60));
            offer.accept(driverId, fixedNow.minusSeconds(40));

            when(driverOfferRepository.findPendingAssignmentsForRecovery(any(Instant.class)))
                    .thenReturn(List.of(offer));
            when(driverOfferRepository.findById(offer.getId())).thenReturn(Optional.of(offer));

            when(tripAssignmentProvider.assignDriver(tripId, driverId, vehicleId, offer.getId()))
                    .thenReturn(TripAssignmentResult.failure(tripId, driverId, vehicleId, "TRIP_CANCELLED", "Trip was cancelled"));

            MatchingSession activeSession = new MatchingSession(UUID.randomUUID(), tripId, 1, 1000, MatchingSessionStatus.SEARCHING, fixedNow.minusSeconds(60));
            when(matchingSessionRepository.findByTripIdAndStatus(tripId, MatchingSessionStatus.SEARCHING))
                    .thenReturn(Optional.of(activeSession));

            int recovered = recoveryService.recoverStalePendingAssignments(Duration.ofSeconds(15));

            assertThat(recovered).isEqualTo(1);
            verify(driverOfferService, times(1)).markAssignmentFailed(eq(offer.getId()), contains("cancelled"));
            assertThat(activeSession.getStatus()).isEqualTo(MatchingSessionStatus.CANCELLED);
        }

        @Test
        @DisplayName("Case 5: Trip ACCEPTED by another driver -> marks offer FAILED")
        void recoverStalePending_case5_tripAcceptedByAnotherDriver_marksFailed() {
            UUID tripId = UUID.randomUUID();
            UUID driverId = UUID.randomUUID();
            UUID vehicleId = UUID.randomUUID();
            DriverOffer offer = createAcceptedPendingOffer(tripId, driverId, vehicleId, fixedNow.minusSeconds(60));
            offer.accept(driverId, fixedNow.minusSeconds(40));

            when(driverOfferRepository.findPendingAssignmentsForRecovery(any(Instant.class)))
                    .thenReturn(List.of(offer));
            when(driverOfferRepository.findById(offer.getId())).thenReturn(Optional.of(offer));

            when(tripAssignmentProvider.assignDriver(tripId, driverId, vehicleId, offer.getId()))
                    .thenReturn(TripAssignmentResult.failure(tripId, driverId, vehicleId, "CONFLICT_ANOTHER_DRIVER", "Trip assigned to another driver"));

            int recovered = recoveryService.recoverStalePendingAssignments(Duration.ofSeconds(15));

            assertThat(recovered).isEqualTo(1);
            verify(driverOfferService, times(1)).markAssignmentFailed(eq(offer.getId()), contains("another driver"));
        }

        @Test
        @DisplayName("Case 6: Trip Service unreachable -> keeps offer in PENDING (no premature release)")
        void recoverStalePending_case6_tripServiceUnreachable_preservesPending() {
            UUID tripId = UUID.randomUUID();
            UUID driverId = UUID.randomUUID();
            UUID vehicleId = UUID.randomUUID();
            DriverOffer offer = createAcceptedPendingOffer(tripId, driverId, vehicleId, fixedNow.minusSeconds(60));
            offer.accept(driverId, fixedNow.minusSeconds(40));

            when(driverOfferRepository.findPendingAssignmentsForRecovery(any(Instant.class)))
                    .thenReturn(List.of(offer));
            when(driverOfferRepository.findById(offer.getId())).thenReturn(Optional.of(offer));

            when(tripAssignmentProvider.assignDriver(tripId, driverId, vehicleId, offer.getId()))
                    .thenThrow(new RuntimeException("Connection timed out"));

            int recovered = recoveryService.recoverStalePendingAssignments(Duration.ofSeconds(15));

            assertThat(recovered).isEqualTo(1);
            // Neither assigned nor failed was called: remains in PENDING safely
            verify(driverOfferService, never()).markAssignmentAssigned(any());
            verify(driverOfferService, never()).markAssignmentFailed(any(), any());
        }
    }

    @Nested
    @DisplayName("2. Offer Expiration & Session Recovery (Section 6)")
    class OfferExpirationAndSessionRecoveryTests {

        @Test
        @DisplayName("Expired PENDING offers are scanned and expired via matchingOrchestrator")
        void recoverExpiredOffers_triggersOrchestratorExpiration() {
            DriverOffer expiredOffer1 = createAcceptedPendingOffer(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), fixedNow.minusSeconds(40));
            DriverOffer expiredOffer2 = createAcceptedPendingOffer(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), fixedNow.minusSeconds(35));

            when(driverOfferRepository.findExpiredOffers(eq(OfferStatus.PENDING), any(Instant.class)))
                    .thenReturn(List.of(expiredOffer1, expiredOffer2));

            int count = recoveryService.recoverExpiredOffers();

            assertThat(count).isEqualTo(2);
            verify(matchingOrchestrator, times(1)).expireOffer(expiredOffer1.getId());
            verify(matchingOrchestrator, times(1)).expireOffer(expiredOffer2.getId());
        }

        @Test
        @DisplayName("Orphaned SEARCHING matching sessions with no active offers are resumed")
        void recoverOrphanedMatchingSessions_resumesMatching() {
            UUID tripId = UUID.randomUUID();
            MatchingSession session = new MatchingSession(UUID.randomUUID(), tripId, 1, 1000, MatchingSessionStatus.SEARCHING, fixedNow.minusSeconds(50));

            when(matchingSessionRepository.findByStatus(MatchingSessionStatus.SEARCHING))
                    .thenReturn(List.of(session));
            when(driverOfferRepository.existsByTripIdAndStatus(tripId, OfferStatus.PENDING)).thenReturn(false);
            when(driverOfferRepository.existsByTripIdAndStatusAndAssignmentStatusIn(eq(tripId), eq(OfferStatus.ACCEPTED), any())).thenReturn(false);

            int count = recoveryService.recoverOrphanedMatchingSessions(Duration.ofSeconds(30));

            assertThat(count).isEqualTo(1);
            verify(matchingOrchestrator, times(1)).startOrContinueMatching(tripId);
        }
    }
}
