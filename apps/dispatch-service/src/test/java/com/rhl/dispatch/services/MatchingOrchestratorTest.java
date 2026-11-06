package com.rhl.dispatch.services;

import com.rhl.dispatch.configs.MatchingProperties;
import com.rhl.dispatch.dtos.CreateOfferCommand;
import com.rhl.dispatch.dtos.DriverOfferResponse;
import com.rhl.dispatch.entities.MatchingSession;
import com.rhl.dispatch.models.CandidateSelectionResult;
import com.rhl.dispatch.models.DriverCandidate;
import com.rhl.dispatch.models.MatchingCriteria;
import com.rhl.dispatch.models.MatchingOrchestrationResult;
import com.rhl.dispatch.models.MatchingSessionStatus;
import com.rhl.dispatch.models.OfferStatus;
import com.rhl.dispatch.models.ServiceType;
import com.rhl.dispatch.models.TripAssignmentResult;
import com.rhl.dispatch.models.TripMatchingCriteriaSnapshot;
import com.rhl.dispatch.models.VehicleType;
import com.rhl.dispatch.providers.TripAssignmentProvider;
import com.rhl.dispatch.providers.TripCriteriaProvider;
import com.rhl.dispatch.repositories.DriverOfferRepository;
import com.rhl.dispatch.repositories.MatchingSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MatchingOrchestrator Unit Tests")
class MatchingOrchestratorTest {

    @Mock
    private TripCriteriaProvider tripCriteriaProvider;

    @Mock
    private TripAssignmentProvider tripAssignmentProvider;

    @Mock
    private MatchingSessionRepository matchingSessionRepository;

    @Mock
    private DriverOfferRepository driverOfferRepository;

    @Mock
    private DriverOfferService driverOfferService;

    @Mock
    private DriverCandidateSelector driverCandidateSelector;

    @Spy
    private MatchingProperties matchingProperties = new MatchingProperties(List.of(1.0, 3.0, 5.0), 60, 10, 15);

    private Clock clock;

    private MatchingOrchestrator orchestrator;

    private UUID tripId;
    private UUID driverId;
    private UUID vehicleId;
    private UUID offerId;
    private Instant now;

    @BeforeEach
    void setUp() {
        now = Instant.parse("2026-10-25T10:00:00Z");
        clock = Clock.fixed(now, ZoneOffset.UTC);

        orchestrator = new MatchingOrchestrator(
                tripCriteriaProvider,
                tripAssignmentProvider,
                matchingSessionRepository,
                driverOfferRepository,
                driverOfferService,
                driverCandidateSelector,
                matchingProperties,
                clock
        );

        tripId = UUID.randomUUID();
        driverId = UUID.randomUUID();
        vehicleId = UUID.randomUUID();
        offerId = UUID.randomUUID();
    }

    private TripMatchingCriteriaSnapshot snapshot(String status) {
        return new TripMatchingCriteriaSnapshot(
                tripId, UUID.randomUUID(), status, 21.0285, 105.8542, "RIDE", "MOTORBIKE"
        );
    }

    private DriverCandidate candidate(UUID dId, UUID vId, double distKm) {
        return DriverCandidate.builder()
                .driverId(dId)
                .vehicleId(vId)
                .vehicleType(VehicleType.MOTORBIKE)
                .location(105.85, 21.02)
                .distanceKm(distKm)
                .lastSeenTimestamp(now)
                .build();
    }

    @Nested
    @DisplayName("startOrContinueMatching tests")
    class StartOrContinueTests {

        @Test
        @DisplayName("Trip in MATCHING finds candidate in Round 1 and dispatches offer")
        void matchingTrip_Round1_CandidateFound_DispatchesOffer() {
            when(tripCriteriaProvider.getTripMatchingCriteria(tripId)).thenReturn(snapshot("MATCHING"));
            when(driverOfferRepository.findByTripIdAndStatusAndAssignmentStatusIn(eq(tripId), any(), any()))
                    .thenReturn(Optional.empty());
            when(driverOfferRepository.findByTripId(tripId)).thenReturn(List.of());

            MatchingSession session = new MatchingSession(UUID.randomUUID(), tripId, 1, 1000, MatchingSessionStatus.SEARCHING, now);
            when(matchingSessionRepository.findByTripIdAndStatus(tripId, MatchingSessionStatus.SEARCHING))
                    .thenReturn(Optional.of(session));

            CandidateSelectionResult selection = new CandidateSelectionResult(
                    tripId, 1, 1.0, List.of(candidate(driverId, vehicleId, 0.8)), Map.of(), now);
            when(driverCandidateSelector.selectCandidates(any(MatchingCriteria.class))).thenReturn(selection);

            DriverOfferResponse offerResponse = new DriverOfferResponse(
                    offerId, tripId, driverId, vehicleId, ServiceType.RIDE, OfferStatus.PENDING,
                    now, now.plusSeconds(15), null, null, null, null, null, 0L
            );
            when(driverOfferService.createOffer(any(CreateOfferCommand.class))).thenReturn(offerResponse);

            MatchingOrchestrationResult result = orchestrator.startOrContinueMatching(tripId);

            assertThat(result.outcome()).isEqualTo("OFFER_DISPATCHED");
            assertThat(result.offerId()).isEqualTo(offerId);
            assertThat(result.driverId()).isEqualTo(driverId);
            assertThat(result.roundNumber()).isEqualTo(1);
            assertThat(result.radiusMeters()).isEqualTo(1000);
            verify(driverOfferService).createOffer(any(CreateOfferCommand.class));
        }

        @Test
        @DisplayName("Trip in CREATED transitions to MATCHING before candidate search")
        void createdTrip_TransitionsToMatching_ThenSearches() {
            when(tripCriteriaProvider.getTripMatchingCriteria(tripId)).thenReturn(snapshot("CREATED"));
            when(tripAssignmentProvider.startMatching(tripId)).thenReturn(snapshot("MATCHING"));
            when(driverOfferRepository.findByTripIdAndStatusAndAssignmentStatusIn(eq(tripId), any(), any()))
                    .thenReturn(Optional.empty());
            when(driverOfferRepository.findByTripId(tripId)).thenReturn(List.of());

            MatchingSession session = new MatchingSession(UUID.randomUUID(), tripId, 1, 1000, MatchingSessionStatus.SEARCHING, now);
            when(matchingSessionRepository.findByTripIdAndStatus(tripId, MatchingSessionStatus.SEARCHING))
                    .thenReturn(Optional.of(session));

            CandidateSelectionResult selection = new CandidateSelectionResult(
                    tripId, 1, 1.0, List.of(candidate(driverId, vehicleId, 0.5)), Map.of(), now);
            when(driverCandidateSelector.selectCandidates(any(MatchingCriteria.class))).thenReturn(selection);

            DriverOfferResponse offerResponse = new DriverOfferResponse(
                    offerId, tripId, driverId, vehicleId, ServiceType.RIDE, OfferStatus.PENDING,
                    now, now.plusSeconds(15), null, null, null, null, null, 0L
            );
            when(driverOfferService.createOffer(any(CreateOfferCommand.class))).thenReturn(offerResponse);

            MatchingOrchestrationResult result = orchestrator.startOrContinueMatching(tripId);

            assertThat(result.outcome()).isEqualTo("OFFER_DISPATCHED");
            verify(tripAssignmentProvider).startMatching(tripId);
        }

        @Test
        @DisplayName("No candidates in Round 1 expands to Round 2 (3km), then dispatches offer")
        void noCandidateRound1_ExpandsToRound2() {
            when(tripCriteriaProvider.getTripMatchingCriteria(tripId)).thenReturn(snapshot("MATCHING"));
            when(driverOfferRepository.findByTripIdAndStatusAndAssignmentStatusIn(eq(tripId), any(), any()))
                    .thenReturn(Optional.empty());
            when(driverOfferRepository.findByTripId(tripId)).thenReturn(List.of());

            MatchingSession session = new MatchingSession(UUID.randomUUID(), tripId, 1, 1000, MatchingSessionStatus.SEARCHING, now);
            when(matchingSessionRepository.findByTripIdAndStatus(tripId, MatchingSessionStatus.SEARCHING))
                    .thenReturn(Optional.of(session));

            CandidateSelectionResult emptySelection = CandidateSelectionResult.empty(tripId, 1, 1.0, Map.of(), now);
            CandidateSelectionResult round2Selection = new CandidateSelectionResult(
                    tripId, 2, 3.0, List.of(candidate(driverId, vehicleId, 2.2)), Map.of(), now);

            when(driverCandidateSelector.selectCandidates(any(MatchingCriteria.class)))
                    .thenReturn(emptySelection)
                    .thenReturn(round2Selection);

            DriverOfferResponse offerResponse = new DriverOfferResponse(
                    offerId, tripId, driverId, vehicleId, ServiceType.RIDE, OfferStatus.PENDING,
                    now, now.plusSeconds(15), null, null, null, null, null, 0L
            );
            when(driverOfferService.createOffer(any(CreateOfferCommand.class))).thenReturn(offerResponse);

            MatchingOrchestrationResult result = orchestrator.startOrContinueMatching(tripId);

            assertThat(result.outcome()).isEqualTo("OFFER_DISPATCHED");
            assertThat(result.roundNumber()).isEqualTo(2);
            assertThat(result.radiusMeters()).isEqualTo(3000);
            verify(matchingSessionRepository).save(session);
        }

        @Test
        @DisplayName("Exhausted all 3 rounds without candidate triggers NO_DRIVER transition in Trip Service")
        void allRoundsExhausted_MarksNoDriver() {
            when(tripCriteriaProvider.getTripMatchingCriteria(tripId)).thenReturn(snapshot("MATCHING"));
            when(driverOfferRepository.findByTripIdAndStatusAndAssignmentStatusIn(eq(tripId), any(), any()))
                    .thenReturn(Optional.empty());
            when(driverOfferRepository.findByTripId(tripId)).thenReturn(List.of());

            MatchingSession session = new MatchingSession(UUID.randomUUID(), tripId, 1, 1000, MatchingSessionStatus.SEARCHING, now);
            when(matchingSessionRepository.findByTripIdAndStatus(tripId, MatchingSessionStatus.SEARCHING))
                    .thenReturn(Optional.of(session));

            CandidateSelectionResult emptySelection = CandidateSelectionResult.empty(tripId, 1, 1.0, Map.of(), now);
            when(driverCandidateSelector.selectCandidates(any(MatchingCriteria.class))).thenReturn(emptySelection);

            MatchingOrchestrationResult result = orchestrator.startOrContinueMatching(tripId);

            assertThat(result.outcome()).isEqualTo("NO_DRIVER");
            assertThat(session.getStatus()).isEqualTo(MatchingSessionStatus.EXPIRED);
            verify(tripAssignmentProvider).markNoDriver(eq(tripId), any());
        }

        @Test
        @DisplayName("Trip already ACCEPTED terminates immediately without search")
        void tripAlreadyAccepted_TerminatesMatching() {
            when(tripCriteriaProvider.getTripMatchingCriteria(tripId)).thenReturn(snapshot("ACCEPTED"));

            MatchingOrchestrationResult result = orchestrator.startOrContinueMatching(tripId);

            assertThat(result.outcome()).isEqualTo("ALREADY_MATCHED");
            verifyNoInteractions(driverCandidateSelector);
        }

        @Test
        @DisplayName("Trip in CANCELLED terminates immediately without search")
        void tripCancelled_AbortsMatching() {
            when(tripCriteriaProvider.getTripMatchingCriteria(tripId)).thenReturn(snapshot("CANCELLED"));

            MatchingOrchestrationResult result = orchestrator.startOrContinueMatching(tripId);

            assertThat(result.outcome()).isEqualTo("CANCELLED");
            verifyNoInteractions(driverCandidateSelector);
        }
    }

    @Nested
    @DisplayName("acceptOffer tests")
    class AcceptOfferTests {

        @Test
        @DisplayName("Driver accepts offer successfully -> marks ASSIGNED and completes session MATCHED")
        void acceptOffer_Success() {
            DriverOfferResponse acceptedOffer = new DriverOfferResponse(
                    offerId, tripId, driverId, vehicleId, ServiceType.RIDE, OfferStatus.ACCEPTED,
                    now, now.plusSeconds(15), now, null, null, null, null, 1L
            );
            when(driverOfferService.acceptOffer(offerId, driverId)).thenReturn(acceptedOffer);

            when(tripAssignmentProvider.assignDriver(tripId, driverId, vehicleId, offerId))
                    .thenReturn(TripAssignmentResult.success(tripId, driverId, vehicleId, "ACCEPTED"));

            MatchingSession session = new MatchingSession(UUID.randomUUID(), tripId, 1, 1000, MatchingSessionStatus.SEARCHING, now);
            when(matchingSessionRepository.findByTripIdAndStatus(tripId, MatchingSessionStatus.SEARCHING))
                    .thenReturn(Optional.of(session));

            MatchingOrchestrationResult result = orchestrator.acceptOffer(offerId, driverId);

            assertThat(result.outcome()).isEqualTo("ALREADY_MATCHED");
            verify(driverOfferService).markAssignmentAssigned(offerId);
            verify(driverOfferService).revokeAllPendingOffersForTrip(eq(tripId), any(), eq("SYSTEM"));
            assertThat(session.getStatus()).isEqualTo(MatchingSessionStatus.MATCHED);
        }

        @Test
        @DisplayName("Trip assignment fails due to driver busy -> marks FAILED and resumes matching")
        void acceptOffer_AssignmentFailsDriverBusy_ResumesMatching() {
            DriverOfferResponse acceptedOffer = new DriverOfferResponse(
                    offerId, tripId, driverId, vehicleId, ServiceType.RIDE, OfferStatus.ACCEPTED,
                    now, now.plusSeconds(15), now, null, null, null, null, 1L
            );
            when(driverOfferService.acceptOffer(offerId, driverId)).thenReturn(acceptedOffer);

            when(tripAssignmentProvider.assignDriver(tripId, driverId, vehicleId, offerId))
                    .thenReturn(TripAssignmentResult.failure(tripId, driverId, vehicleId, "DRIVER_BUSY", "Driver has active trip"));

            // When resuming matching
            when(tripCriteriaProvider.getTripMatchingCriteria(tripId)).thenReturn(snapshot("MATCHING"));
            when(driverOfferRepository.findByTripIdAndStatusAndAssignmentStatusIn(eq(tripId), any(), any()))
                    .thenReturn(Optional.empty());
            when(driverOfferRepository.findByTripId(tripId)).thenReturn(List.of());

            MatchingSession session = new MatchingSession(UUID.randomUUID(), tripId, 1, 1000, MatchingSessionStatus.SEARCHING, now);
            when(matchingSessionRepository.findByTripIdAndStatus(tripId, MatchingSessionStatus.SEARCHING))
                    .thenReturn(Optional.of(session));

            UUID nextDriver = UUID.randomUUID();
            CandidateSelectionResult selection = new CandidateSelectionResult(
                    tripId, 1, 1.0, List.of(candidate(nextDriver, vehicleId, 1.0)), Map.of(), now);
            when(driverCandidateSelector.selectCandidates(any(MatchingCriteria.class))).thenReturn(selection);

            DriverOfferResponse nextOffer = new DriverOfferResponse(
                    UUID.randomUUID(), tripId, nextDriver, vehicleId, ServiceType.RIDE, OfferStatus.PENDING,
                    now, now.plusSeconds(15), null, null, null, null, null, 0L
            );
            when(driverOfferService.createOffer(any())).thenReturn(nextOffer);

            MatchingOrchestrationResult result = orchestrator.acceptOffer(offerId, driverId);

            verify(driverOfferService).markAssignmentFailed(eq(offerId), any());
            assertThat(result.outcome()).isEqualTo("OFFER_DISPATCHED");
            assertThat(result.driverId()).isEqualTo(nextDriver);
        }

        @Test
        @DisplayName("Trip assignment fails due to TRIP_CANCELLED -> completes session CANCELLED")
        void acceptOffer_AssignmentFailsTripCancelled_CancelsSession() {
            DriverOfferResponse acceptedOffer = new DriverOfferResponse(
                    offerId, tripId, driverId, vehicleId, ServiceType.RIDE, OfferStatus.ACCEPTED,
                    now, now.plusSeconds(15), now, null, null, null, null, 1L
            );
            when(driverOfferService.acceptOffer(offerId, driverId)).thenReturn(acceptedOffer);

            when(tripAssignmentProvider.assignDriver(tripId, driverId, vehicleId, offerId))
                    .thenReturn(TripAssignmentResult.failure(tripId, driverId, vehicleId, "TRIP_CANCELLED", "Trip cancelled"));

            MatchingSession session = new MatchingSession(UUID.randomUUID(), tripId, 1, 1000, MatchingSessionStatus.SEARCHING, now);
            when(matchingSessionRepository.findByTripIdAndStatus(tripId, MatchingSessionStatus.SEARCHING))
                    .thenReturn(Optional.of(session));

            MatchingOrchestrationResult result = orchestrator.acceptOffer(offerId, driverId);

            assertThat(result.outcome()).isEqualTo("CANCELLED");
            verify(driverOfferService).markAssignmentFailed(eq(offerId), any());
            assertThat(session.getStatus()).isEqualTo(MatchingSessionStatus.CANCELLED);
        }
    }

    @Nested
    @DisplayName("rejectOffer and expireOffer tests")
    class RejectAndExpireTests {

        @Test
        @DisplayName("Driver reject offer continues candidate matching")
        void rejectOffer_ContinuesMatching() {
            DriverOfferResponse rejectedOffer = new DriverOfferResponse(
                    offerId, tripId, driverId, vehicleId, ServiceType.RIDE, OfferStatus.REJECTED,
                    now, now.plusSeconds(15), null, now, "BUSY", null, null, 1L
            );
            when(driverOfferService.rejectOffer(offerId, driverId, "BUSY")).thenReturn(rejectedOffer);

            when(tripCriteriaProvider.getTripMatchingCriteria(tripId)).thenReturn(snapshot("MATCHING"));
            when(driverOfferRepository.findByTripIdAndStatusAndAssignmentStatusIn(eq(tripId), any(), any()))
                    .thenReturn(Optional.empty());
            when(driverOfferRepository.findByTripId(tripId)).thenReturn(List.of());

            MatchingSession session = new MatchingSession(UUID.randomUUID(), tripId, 1, 1000, MatchingSessionStatus.SEARCHING, now);
            when(matchingSessionRepository.findByTripIdAndStatus(tripId, MatchingSessionStatus.SEARCHING))
                    .thenReturn(Optional.of(session));

            UUID nextDriver = UUID.randomUUID();
            CandidateSelectionResult selection = new CandidateSelectionResult(
                    tripId, 1, 1.0, List.of(candidate(nextDriver, vehicleId, 1.2)), Map.of(), now);
            when(driverCandidateSelector.selectCandidates(any(MatchingCriteria.class))).thenReturn(selection);

            DriverOfferResponse nextOffer = new DriverOfferResponse(
                    UUID.randomUUID(), tripId, nextDriver, vehicleId, ServiceType.RIDE, OfferStatus.PENDING,
                    now, now.plusSeconds(15), null, null, null, null, null, 0L
            );
            when(driverOfferService.createOffer(any())).thenReturn(nextOffer);

            MatchingOrchestrationResult result = orchestrator.rejectOffer(offerId, driverId, "BUSY");

            verify(driverOfferService).rejectOffer(offerId, driverId, "BUSY");
            assertThat(result.outcome()).isEqualTo("OFFER_DISPATCHED");
            assertThat(result.driverId()).isEqualTo(nextDriver);
        }

        @Test
        @DisplayName("Offer expiration continues candidate matching")
        void expireOffer_ContinuesMatching() {
            DriverOfferResponse expiredOffer = new DriverOfferResponse(
                    offerId, tripId, driverId, vehicleId, ServiceType.RIDE, OfferStatus.EXPIRED,
                    now, now.plusSeconds(15), null, null, null, null, null, 1L
            );
            when(driverOfferService.expireOffer(offerId)).thenReturn(expiredOffer);

            when(tripCriteriaProvider.getTripMatchingCriteria(tripId)).thenReturn(snapshot("MATCHING"));
            when(driverOfferRepository.findByTripIdAndStatusAndAssignmentStatusIn(eq(tripId), any(), any()))
                    .thenReturn(Optional.empty());
            when(driverOfferRepository.findByTripId(tripId)).thenReturn(List.of());

            MatchingSession session = new MatchingSession(UUID.randomUUID(), tripId, 1, 1000, MatchingSessionStatus.SEARCHING, now);
            when(matchingSessionRepository.findByTripIdAndStatus(tripId, MatchingSessionStatus.SEARCHING))
                    .thenReturn(Optional.of(session));

            CandidateSelectionResult emptySelection = CandidateSelectionResult.empty(tripId, 1, 1.0, Map.of(), now);
            when(driverCandidateSelector.selectCandidates(any())).thenReturn(emptySelection);

            MatchingOrchestrationResult result = orchestrator.expireOffer(offerId);

            verify(driverOfferService).expireOffer(offerId);
            assertThat(result.outcome()).isEqualTo("NO_DRIVER");
        }
    }

    @Nested
    @DisplayName("cancelMatching tests")
    class CancelMatchingTests {

        @Test
        @DisplayName("cancelMatching cancels active session and revokes all pending offers")
        void cancelMatching_CancelsSessionAndRevokesOffers() {
            MatchingSession session = new MatchingSession(UUID.randomUUID(), tripId, 1, 1000, MatchingSessionStatus.SEARCHING, now);
            when(matchingSessionRepository.findByTripIdAndStatus(tripId, MatchingSessionStatus.SEARCHING))
                    .thenReturn(Optional.of(session));

            MatchingOrchestrationResult result = orchestrator.cancelMatching(tripId, "Customer cancelled trip");

            assertThat(result.outcome()).isEqualTo("CANCELLED");
            assertThat(session.getStatus()).isEqualTo(MatchingSessionStatus.CANCELLED);
            verify(driverOfferService).revokeAllPendingOffersForTrip(eq(tripId), eq("Customer cancelled trip"), eq("SYSTEM"));
        }
    }
}
