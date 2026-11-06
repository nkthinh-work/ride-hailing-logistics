package com.rhl.dispatch.services;

import com.rhl.dispatch.configs.MatchingProperties;
import com.rhl.dispatch.exceptions.IdentityServiceUnavailableException;
import com.rhl.dispatch.exceptions.LocationServiceUnavailableException;
import com.rhl.dispatch.exceptions.TripServiceUnavailableException;
import com.rhl.dispatch.models.AssignmentStatus;
import com.rhl.dispatch.models.CandidateExclusionReason;
import com.rhl.dispatch.models.CandidateSelectionResult;
import com.rhl.dispatch.models.MatchingCriteria;
import com.rhl.dispatch.models.OfferStatus;
import com.rhl.dispatch.models.ServiceType;
import com.rhl.dispatch.models.VehicleType;
import com.rhl.dispatch.providers.DriverAvailabilityProvider;
import com.rhl.dispatch.providers.DriverEligibilityProvider;
import com.rhl.dispatch.providers.DriverEligibilityProvider.DriverEligibilityInfo;
import com.rhl.dispatch.providers.DriverEligibilityProvider.VehicleSummary;
import com.rhl.dispatch.providers.LocationNearbyProvider;
import com.rhl.dispatch.providers.LocationNearbyProvider.NearbyDriverLocation;
import com.rhl.dispatch.repositories.DriverOfferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DriverCandidateSelector Unit Tests")
class DriverCandidateSelectorTest {

    @Mock
    private LocationNearbyProvider locationNearbyProvider;

    @Mock
    private DriverEligibilityProvider driverEligibilityProvider;

    @Mock
    private DriverAvailabilityProvider driverAvailabilityProvider;

    @Mock
    private DriverOfferRepository driverOfferRepository;

    private final Instant now = Instant.parse("2026-10-24T10:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final MatchingProperties matchingProperties = new MatchingProperties(List.of(1.0, 3.0, 5.0), 60, 10, 15);
    private final CandidateRankingPolicy rankingPolicy = new CandidateRankingPolicy.DefaultDistanceAndEtaRankingPolicy();

    private DriverCandidateSelector selector;

    private final UUID tripId = UUID.randomUUID();
    private final UUID driver1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID driver2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private final UUID vehicle1 = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private final UUID vehicle2 = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @BeforeEach
    void setUp() {
        selector = new DriverCandidateSelector(
                locationNearbyProvider,
                driverEligibilityProvider,
                driverAvailabilityProvider,
                driverOfferRepository,
                rankingPolicy,
                clock,
                matchingProperties
        );
    }

    private MatchingCriteria defaultCriteria() {
        return MatchingCriteria.builder()
                .tripId(tripId)
                .pickupLocation(21.0285, 105.8542)
                .serviceType(ServiceType.RIDE)
                .vehicleType(VehicleType.MOTORBIKE)
                .radiusKm(1.0)
                .maxCandidates(10)
                .build();
    }

    @Nested
    @DisplayName("Spatial & Empty Results Tests")
    class SpatialQueryTests {

        @Test
        @DisplayName("Returns empty result when Location Service returns zero nearby drivers")
        void zeroNearbyDrivers_returnsEmptyResult() {
            when(locationNearbyProvider.findNearbyDrivers(anyDouble(), anyDouble(), anyDouble(), anyInt()))
                    .thenReturn(List.of());

            CandidateSelectionResult result = selector.selectCandidates(defaultCriteria());

            assertFalse(result.hasCandidates());
            assertEquals(0, result.candidateCount());
            assertTrue(result.exclusions().isEmpty());
            verifyNoInteractions(driverEligibilityProvider, driverAvailabilityProvider, driverOfferRepository);
        }

        @Test
        @DisplayName("Deduplicates candidate drivers returned multiple times by spatial query")
        void duplicateDriverIds_deduplicatesToSingleCandidate() {
            NearbyDriverLocation loc1 = new NearbyDriverLocation(driver1, 105.85, 21.02, 0.5, now.minusSeconds(10));
            NearbyDriverLocation locDuplicate = new NearbyDriverLocation(driver1, 105.85, 21.02, 0.5, now.minusSeconds(10));

            when(locationNearbyProvider.findNearbyDrivers(anyDouble(), anyDouble(), anyDouble(), anyInt()))
                    .thenReturn(List.of(loc1, locDuplicate));

            mockDriverAvailable(driver1);
            mockDriverEligible(driver1, vehicle1, VehicleType.MOTORBIKE);

            CandidateSelectionResult result = selector.selectCandidates(defaultCriteria());

            assertTrue(result.hasCandidates());
            assertEquals(1, result.candidateCount());
            assertEquals(driver1, result.candidates().getFirst().driverId());
        }
    }

    @Nested
    @DisplayName("GPS Freshness Boundary & Validation Tests")
    class GpsFreshnessTests {

        @Test
        @DisplayName("Accepts candidate when GPS location is fresh (10s old)")
        void freshGps_accepted() {
            NearbyDriverLocation loc = new NearbyDriverLocation(driver1, 105.85, 21.02, 0.4, now.minusSeconds(10));
            when(locationNearbyProvider.findNearbyDrivers(anyDouble(), anyDouble(), anyDouble(), anyInt()))
                    .thenReturn(List.of(loc));

            mockDriverAvailable(driver1);
            mockDriverEligible(driver1, vehicle1, VehicleType.MOTORBIKE);

            CandidateSelectionResult result = selector.selectCandidates(defaultCriteria());

            assertTrue(result.hasCandidates());
            assertEquals(1, result.candidateCount());
            assertEquals(driver1, result.candidates().getFirst().driverId());
        }

        @Test
        @DisplayName("Accepts candidate exactly at 59s and 60s freshness boundary")
        void boundaryGps_accepted() {
            NearbyDriverLocation loc59 = new NearbyDriverLocation(driver1, 105.85, 21.02, 0.4, now.minusSeconds(59));
            NearbyDriverLocation loc60 = new NearbyDriverLocation(driver2, 105.86, 21.03, 0.8, now.minusSeconds(60));

            when(locationNearbyProvider.findNearbyDrivers(anyDouble(), anyDouble(), anyDouble(), anyInt()))
                    .thenReturn(List.of(loc59, loc60));

            mockDriverAvailable(driver1);
            mockDriverAvailable(driver2);
            mockBatchEligible(Map.of(
                    driver1, createEligibleInfo(driver1, vehicle1, VehicleType.MOTORBIKE),
                    driver2, createEligibleInfo(driver2, vehicle2, VehicleType.MOTORBIKE)
            ));

            CandidateSelectionResult result = selector.selectCandidates(defaultCriteria());

            assertEquals(2, result.candidateCount());
        }

        @Test
        @DisplayName("Excludes candidate when GPS is stale (> 60s, e.g. 61s, 75s, 90s) even if Redis TTL is 90s")
        void staleGps_excluded() {
            NearbyDriverLocation locStale61 = new NearbyDriverLocation(driver1, 105.85, 21.02, 0.4, now.minusSeconds(61));
            NearbyDriverLocation locStale75 = new NearbyDriverLocation(driver2, 105.86, 21.03, 0.6, now.minusSeconds(75));

            when(locationNearbyProvider.findNearbyDrivers(anyDouble(), anyDouble(), anyDouble(), anyInt()))
                    .thenReturn(List.of(locStale61, locStale75));

            CandidateSelectionResult result = selector.selectCandidates(defaultCriteria());

            assertFalse(result.hasCandidates());
            assertEquals(CandidateExclusionReason.STALE_GPS, result.exclusions().get(driver1));
            assertEquals(CandidateExclusionReason.STALE_GPS, result.exclusions().get(driver2));
        }

        @Test
        @DisplayName("Excludes candidate when GPS timestamp is skewed into future beyond tolerance (> 15s)")
        void futureGps_excluded() {
            NearbyDriverLocation locFuture = new NearbyDriverLocation(driver1, 105.85, 21.02, 0.4, now.plusSeconds(25));
            when(locationNearbyProvider.findNearbyDrivers(anyDouble(), anyDouble(), anyDouble(), anyInt()))
                    .thenReturn(List.of(locFuture));

            CandidateSelectionResult result = selector.selectCandidates(defaultCriteria());

            assertFalse(result.hasCandidates());
            assertEquals(CandidateExclusionReason.GPS_IN_FUTURE, result.exclusions().get(driver1));
        }

        @Test
        @DisplayName("Excludes candidate when GPS timestamp is missing (null) - fail-closed")
        void missingGpsTimestamp_excluded() {
            NearbyDriverLocation locMissing = new NearbyDriverLocation(driver1, 105.85, 21.02, 0.4, null);
            when(locationNearbyProvider.findNearbyDrivers(anyDouble(), anyDouble(), anyDouble(), anyInt()))
                    .thenReturn(List.of(locMissing));

            CandidateSelectionResult result = selector.selectCandidates(defaultCriteria());

            assertFalse(result.hasCandidates());
            assertEquals(CandidateExclusionReason.MISSING_GPS_TIMESTAMP, result.exclusions().get(driver1));
        }
    }

    @Nested
    @DisplayName("Local Offer State & Conflict Tests")
    class LocalOfferConflictTests {

        @Test
        @DisplayName("Excludes candidate who currently has an active PENDING offer in Dispatch DB (FR-DRV-012)")
        void pendingOffer_excluded() {
            NearbyDriverLocation loc = new NearbyDriverLocation(driver1, 105.85, 21.02, 0.4, now.minusSeconds(10));
            when(locationNearbyProvider.findNearbyDrivers(anyDouble(), anyDouble(), anyDouble(), anyInt()))
                    .thenReturn(List.of(loc));

            when(driverOfferRepository.existsByDriverIdAndStatus(driver1, OfferStatus.PENDING)).thenReturn(true);

            CandidateSelectionResult result = selector.selectCandidates(defaultCriteria());

            assertFalse(result.hasCandidates());
            assertEquals(CandidateExclusionReason.HAS_PENDING_OFFER, result.exclusions().get(driver1));
        }

        @Test
        @DisplayName("Excludes candidate who has an ACCEPTED offer pending trip assignment (FR-MAT-011)")
        void acceptedOffer_excluded() {
            NearbyDriverLocation loc = new NearbyDriverLocation(driver1, 105.85, 21.02, 0.4, now.minusSeconds(10));
            when(locationNearbyProvider.findNearbyDrivers(anyDouble(), anyDouble(), anyDouble(), anyInt()))
                    .thenReturn(List.of(loc));

            when(driverOfferRepository.existsByDriverIdAndStatus(driver1, OfferStatus.PENDING)).thenReturn(false);
            when(driverOfferRepository.existsByDriverIdAndStatusAndAssignmentStatus(driver1, OfferStatus.ACCEPTED, AssignmentStatus.PENDING)).thenReturn(true);

            CandidateSelectionResult result = selector.selectCandidates(defaultCriteria());

            assertFalse(result.hasCandidates());
            assertEquals(CandidateExclusionReason.ACCEPTED_PENDING_ASSIGNMENT, result.exclusions().get(driver1));
        }

        @Test
        @DisplayName("Does NOT exclude candidate who has a historical ACCEPTED offer with ASSIGNED assignment status and completed trip")
        void historicalAcceptedOffer_eligible() {
            NearbyDriverLocation loc = new NearbyDriverLocation(driver1, 105.85, 21.02, 0.4, now.minusSeconds(10));
            when(locationNearbyProvider.findNearbyDrivers(anyDouble(), anyDouble(), anyDouble(), anyInt()))
                    .thenReturn(List.of(loc));

            when(driverOfferRepository.existsByDriverIdAndStatus(driver1, OfferStatus.PENDING)).thenReturn(false);
            when(driverOfferRepository.existsByDriverIdAndStatusAndAssignmentStatus(driver1, OfferStatus.ACCEPTED, AssignmentStatus.PENDING)).thenReturn(false);
            when(driverAvailabilityProvider.findBusyDrivers(anyList())).thenReturn(Set.of());
            mockDriverEligible(driver1, vehicle1, VehicleType.MOTORBIKE);

            CandidateSelectionResult result = selector.selectCandidates(defaultCriteria());

            assertTrue(result.hasCandidates());
            assertEquals(1, result.candidateCount());
            assertEquals(driver1, result.candidates().getFirst().driverId());
        }

        @Test
        @DisplayName("Excludes candidate who previously rejected an offer in earlier matching round of this trip (FR-MAT-013)")
        void previouslyRejected_excluded() {
            NearbyDriverLocation loc = new NearbyDriverLocation(driver1, 105.85, 21.02, 0.4, now.minusSeconds(10));
            when(locationNearbyProvider.findNearbyDrivers(anyDouble(), anyDouble(), anyDouble(), anyInt()))
                    .thenReturn(List.of(loc));

            MatchingCriteria criteria = MatchingCriteria.builder()
                    .tripId(tripId)
                    .pickupLocation(21.0285, 105.8542)
                    .serviceType(ServiceType.RIDE)
                    .vehicleType(VehicleType.MOTORBIKE)
                    .radiusKm(3.0)
                    .searchRound(2)
                    .excludedDriverIds(Set.of(driver1))
                    .build();

            CandidateSelectionResult result = selector.selectCandidates(criteria);

            assertFalse(result.hasCandidates());
            assertEquals(CandidateExclusionReason.PREVIOUSLY_REJECTED, result.exclusions().get(driver1));
        }
    }

    @Nested
    @DisplayName("External Trip Availability Tests")
    class TripAvailabilityTests {

        @Test
        @DisplayName("Excludes candidate who is currently busy with an active trip in Trip Service (FR-MAT-005)")
        void driverBusyInTrip_excluded() {
            NearbyDriverLocation loc = new NearbyDriverLocation(driver1, 105.85, 21.02, 0.4, now.minusSeconds(10));
            when(locationNearbyProvider.findNearbyDrivers(anyDouble(), anyDouble(), anyDouble(), anyInt()))
                    .thenReturn(List.of(loc));

            when(driverOfferRepository.existsByDriverIdAndStatus(any(), any())).thenReturn(false);
            when(driverAvailabilityProvider.findBusyDrivers(List.of(driver1))).thenReturn(Set.of(driver1));

            CandidateSelectionResult result = selector.selectCandidates(defaultCriteria());

            assertFalse(result.hasCandidates());
            assertEquals(CandidateExclusionReason.DRIVER_BUSY_IN_TRIP, result.exclusions().get(driver1));
        }
    }

    @Nested
    @DisplayName("Driver Eligibility & Vehicle Compatibility Tests")
    class EligibilityAndVehicleTests {

        @Test
        @DisplayName("Excludes candidate when driver is SUSPENDED in Identity Service")
        void driverSuspended_excluded() {
            NearbyDriverLocation loc = new NearbyDriverLocation(driver1, 105.85, 21.02, 0.4, now.minusSeconds(10));
            when(locationNearbyProvider.findNearbyDrivers(anyDouble(), anyDouble(), anyDouble(), anyInt()))
                    .thenReturn(List.of(loc));

            mockDriverAvailable(driver1);

            DriverEligibilityInfo suspendedInfo = new DriverEligibilityInfo(
                    driver1, false, "ACTIVE", "SUSPENDED", true, List.of("DRIVER_SUSPENDED"),
                    List.of(new VehicleSummary(vehicle1, VehicleType.MOTORBIKE, "29A-12345", "ACTIVE"))
            );
            when(driverEligibilityProvider.getBatchEligibility(List.of(driver1))).thenReturn(Map.of(driver1, suspendedInfo));

            CandidateSelectionResult result = selector.selectCandidates(defaultCriteria());

            assertFalse(result.hasCandidates());
            assertEquals(CandidateExclusionReason.DRIVER_SUSPENDED, result.exclusions().get(driver1));
        }

        @Test
        @DisplayName("Excludes candidate when user account is INACTIVE")
        void accountInactive_excluded() {
            NearbyDriverLocation loc = new NearbyDriverLocation(driver1, 105.85, 21.02, 0.4, now.minusSeconds(10));
            when(locationNearbyProvider.findNearbyDrivers(anyDouble(), anyDouble(), anyDouble(), anyInt()))
                    .thenReturn(List.of(loc));

            mockDriverAvailable(driver1);

            DriverEligibilityInfo inactiveInfo = new DriverEligibilityInfo(
                    driver1, false, "INACTIVE", "APPROVED", true, List.of("ACCOUNT_INACTIVE"),
                    List.of(new VehicleSummary(vehicle1, VehicleType.MOTORBIKE, "29A-12345", "ACTIVE"))
            );
            when(driverEligibilityProvider.getBatchEligibility(List.of(driver1))).thenReturn(Map.of(driver1, inactiveInfo));

            CandidateSelectionResult result = selector.selectCandidates(defaultCriteria());

            assertFalse(result.hasCandidates());
            assertEquals(CandidateExclusionReason.ACCOUNT_INACTIVE, result.exclusions().get(driver1));
        }

        @Test
        @DisplayName("Excludes candidate when driver profile is NOT APPROVED (e.g. PENDING_REVIEW)")
        void profileNotApproved_excluded() {
            NearbyDriverLocation loc = new NearbyDriverLocation(driver1, 105.85, 21.02, 0.4, now.minusSeconds(10));
            when(locationNearbyProvider.findNearbyDrivers(anyDouble(), anyDouble(), anyDouble(), anyInt()))
                    .thenReturn(List.of(loc));

            mockDriverAvailable(driver1);

            DriverEligibilityInfo pendingReviewInfo = new DriverEligibilityInfo(
                    driver1, false, "ACTIVE", "PENDING_REVIEW", true, List.of("APPLICATION_NOT_APPROVED"),
                    List.of(new VehicleSummary(vehicle1, VehicleType.MOTORBIKE, "29A-12345", "ACTIVE"))
            );
            when(driverEligibilityProvider.getBatchEligibility(List.of(driver1))).thenReturn(Map.of(driver1, pendingReviewInfo));

            CandidateSelectionResult result = selector.selectCandidates(defaultCriteria());

            assertFalse(result.hasCandidates());
            assertEquals(CandidateExclusionReason.PROFILE_NOT_APPROVED, result.exclusions().get(driver1));
        }

        @Test
        @DisplayName("Excludes candidate when driver has NO active vehicle")
        void noActiveVehicle_excluded() {
            NearbyDriverLocation loc = new NearbyDriverLocation(driver1, 105.85, 21.02, 0.4, now.minusSeconds(10));
            when(locationNearbyProvider.findNearbyDrivers(anyDouble(), anyDouble(), anyDouble(), anyInt()))
                    .thenReturn(List.of(loc));

            mockDriverAvailable(driver1);

            DriverEligibilityInfo noVehicleInfo = new DriverEligibilityInfo(
                    driver1, true, "ACTIVE", "APPROVED", true, List.of(), List.of()
            );
            when(driverEligibilityProvider.getBatchEligibility(List.of(driver1))).thenReturn(Map.of(driver1, noVehicleInfo));

            CandidateSelectionResult result = selector.selectCandidates(defaultCriteria());

            assertFalse(result.hasCandidates());
            assertEquals(CandidateExclusionReason.NO_ACTIVE_VEHICLE, result.exclusions().get(driver1));
        }

        @Test
        @DisplayName("Excludes candidate when driver's active vehicle type does not match trip vehicle type")
        void vehicleTypeMismatch_excluded() {
            NearbyDriverLocation loc = new NearbyDriverLocation(driver1, 105.85, 21.02, 0.4, now.minusSeconds(10));
            when(locationNearbyProvider.findNearbyDrivers(anyDouble(), anyDouble(), anyDouble(), anyInt()))
                    .thenReturn(List.of(loc));

            mockDriverAvailable(driver1);

            // Driver has CAR_4_SEAT, but trip requested MOTORBIKE
            DriverEligibilityInfo carInfo = new DriverEligibilityInfo(
                    driver1, true, "ACTIVE", "APPROVED", true, List.of(),
                    List.of(new VehicleSummary(vehicle1, VehicleType.CAR_4_SEAT, "30E-99999", "ACTIVE"))
            );
            when(driverEligibilityProvider.getBatchEligibility(List.of(driver1))).thenReturn(Map.of(driver1, carInfo));

            CandidateSelectionResult result = selector.selectCandidates(defaultCriteria());

            assertFalse(result.hasCandidates());
            assertEquals(CandidateExclusionReason.VEHICLE_TYPE_MISMATCH, result.exclusions().get(driver1));
        }

        @Test
        @DisplayName("Excludes candidate when driver has multiple active vehicles of different types without session selection (fail-closed per Section 7)")
        void ambiguousActiveVehicles_excluded() {
            NearbyDriverLocation loc = new NearbyDriverLocation(driver1, 105.85, 21.02, 0.4, now.minusSeconds(10));
            when(locationNearbyProvider.findNearbyDrivers(anyDouble(), anyDouble(), anyDouble(), anyInt()))
                    .thenReturn(List.of(loc));

            mockDriverAvailable(driver1);

            // Driver has both MOTORBIKE and CAR_4_SEAT active simultaneously
            DriverEligibilityInfo multiVehicleInfo = new DriverEligibilityInfo(
                    driver1, true, "ACTIVE", "APPROVED", true, List.of(),
                    List.of(
                            new VehicleSummary(vehicle1, VehicleType.MOTORBIKE, "29A-12345", "ACTIVE"),
                            new VehicleSummary(vehicle2, VehicleType.CAR_4_SEAT, "30E-99999", "ACTIVE")
                    )
            );
            when(driverEligibilityProvider.getBatchEligibility(List.of(driver1))).thenReturn(Map.of(driver1, multiVehicleInfo));

            CandidateSelectionResult result = selector.selectCandidates(defaultCriteria());

            assertFalse(result.hasCandidates());
            assertEquals(CandidateExclusionReason.AMBIGUOUS_ACTIVE_VEHICLES, result.exclusions().get(driver1));
        }

        @Test
        @DisplayName("Excludes candidate when driver has multiple active vehicles of same type without trusted session (fail-closed)")
        void ambiguousActiveVehicles_sameTypeWithoutSession_excluded() {
            NearbyDriverLocation loc = new NearbyDriverLocation(driver1, 105.85, 21.02, 0.4, now.minusSeconds(10));
            when(locationNearbyProvider.findNearbyDrivers(anyDouble(), anyDouble(), anyDouble(), anyInt()))
                    .thenReturn(List.of(loc));

            mockDriverAvailable(driver1);

            // Driver has 2 active MOTORBIKE vehicles, no trusted vehicle ID
            DriverEligibilityInfo multiBikeInfo = new DriverEligibilityInfo(
                    driver1, true, "ACTIVE", "APPROVED", true, List.of(),
                    List.of(
                            new VehicleSummary(vehicle1, VehicleType.MOTORBIKE, "29A-11111", "ACTIVE"),
                            new VehicleSummary(vehicle2, VehicleType.MOTORBIKE, "29A-22222", "ACTIVE")
                    )
            );
            when(driverEligibilityProvider.getBatchEligibility(List.of(driver1))).thenReturn(Map.of(driver1, multiBikeInfo));

            CandidateSelectionResult result = selector.selectCandidates(defaultCriteria());

            assertFalse(result.hasCandidates());
            assertEquals(CandidateExclusionReason.AMBIGUOUS_ACTIVE_VEHICLES, result.exclusions().get(driver1));
        }

        @Test
        @DisplayName("Selects candidate when driver has multiple active vehicles but trusted selectedVehicleId matches")
        void ambiguousActiveVehicles_withSelectedVehicleId_selected() {
            // Location contains selectedVehicleId = vehicle2
            NearbyDriverLocation loc = new NearbyDriverLocation(driver1, 105.85, 21.02, 0.4, now.minusSeconds(10), vehicle2);
            when(locationNearbyProvider.findNearbyDrivers(anyDouble(), anyDouble(), anyDouble(), anyInt()))
                    .thenReturn(List.of(loc));

            mockDriverAvailable(driver1);

            DriverEligibilityInfo multiBikeInfo = new DriverEligibilityInfo(
                    driver1, true, "ACTIVE", "APPROVED", true, List.of(),
                    List.of(
                            new VehicleSummary(vehicle1, VehicleType.MOTORBIKE, "29A-11111", "ACTIVE"),
                            new VehicleSummary(vehicle2, VehicleType.MOTORBIKE, "29A-22222", "ACTIVE")
                    )
            );
            when(driverEligibilityProvider.getBatchEligibility(List.of(driver1))).thenReturn(Map.of(driver1, multiBikeInfo));

            CandidateSelectionResult result = selector.selectCandidates(defaultCriteria());

            assertTrue(result.hasCandidates());
            assertEquals(1, result.candidateCount());
            assertEquals(vehicle2, result.candidates().getFirst().vehicleId());
        }
    }

    @Nested
    @DisplayName("Ranking, Truncation & Fail-Closed Tests")
    class RankingAndFailClosedTests {

        @Test
        @DisplayName("Ranks candidates by distance ascending and truncates to maxCandidates")
        void rankingAndTruncation_success() {
            NearbyDriverLocation locFar = new NearbyDriverLocation(driver1, 105.85, 21.02, 0.9, now.minusSeconds(10));
            NearbyDriverLocation locClose = new NearbyDriverLocation(driver2, 105.84, 21.01, 0.3, now.minusSeconds(10));

            when(locationNearbyProvider.findNearbyDrivers(anyDouble(), anyDouble(), anyDouble(), anyInt()))
                    .thenReturn(List.of(locFar, locClose));

            mockDriverAvailable(driver1);
            mockDriverAvailable(driver2);
            mockBatchEligible(Map.of(
                    driver1, createEligibleInfo(driver1, vehicle1, VehicleType.MOTORBIKE),
                    driver2, createEligibleInfo(driver2, vehicle2, VehicleType.MOTORBIKE)
            ));

            MatchingCriteria criteria = MatchingCriteria.builder()
                    .tripId(tripId)
                    .pickupLocation(21.0285, 105.8542)
                    .serviceType(ServiceType.RIDE)
                    .vehicleType(VehicleType.MOTORBIKE)
                    .radiusKm(1.0)
                    .maxCandidates(1) // Truncate to top 1
                    .build();

            CandidateSelectionResult result = selector.selectCandidates(criteria);

            assertEquals(1, result.candidateCount());
            assertEquals(driver2, result.candidates().getFirst().driverId(), "Closest driver must be ranked first");
        }

        @Test
        @DisplayName("Throws LocationServiceUnavailableException when Location Service fails (fail-closed)")
        void locationServiceFails_failClosed() {
            when(locationNearbyProvider.findNearbyDrivers(anyDouble(), anyDouble(), anyDouble(), anyInt()))
                    .thenThrow(new LocationServiceUnavailableException("Connection refused"));

            assertThrows(LocationServiceUnavailableException.class, () -> selector.selectCandidates(defaultCriteria()));
        }

        @Test
        @DisplayName("Throws IdentityServiceUnavailableException when Identity Service fails (fail-closed)")
        void identityServiceFails_failClosed() {
            NearbyDriverLocation loc = new NearbyDriverLocation(driver1, 105.85, 21.02, 0.4, now.minusSeconds(10));
            when(locationNearbyProvider.findNearbyDrivers(anyDouble(), anyDouble(), anyDouble(), anyInt()))
                    .thenReturn(List.of(loc));

            mockDriverAvailable(driver1);
            when(driverEligibilityProvider.getBatchEligibility(anyList()))
                    .thenThrow(new IdentityServiceUnavailableException("Identity timeout"));

            assertThrows(IdentityServiceUnavailableException.class, () -> selector.selectCandidates(defaultCriteria()));
        }

        @Test
        @DisplayName("Throws TripServiceUnavailableException when Trip Service fails (fail-closed)")
        void tripServiceFails_failClosed() {
            NearbyDriverLocation loc = new NearbyDriverLocation(driver1, 105.85, 21.02, 0.4, now.minusSeconds(10));
            when(locationNearbyProvider.findNearbyDrivers(anyDouble(), anyDouble(), anyDouble(), anyInt()))
                    .thenReturn(List.of(loc));

            when(driverOfferRepository.existsByDriverIdAndStatus(any(), any())).thenReturn(false);
            when(driverAvailabilityProvider.findBusyDrivers(anyList()))
                    .thenThrow(new TripServiceUnavailableException("Trip service unavailable"));

            assertThrows(TripServiceUnavailableException.class, () -> selector.selectCandidates(defaultCriteria()));
        }
    }

    private void mockDriverAvailable(UUID driverId) {
        when(driverOfferRepository.existsByDriverIdAndStatus(driverId, OfferStatus.PENDING)).thenReturn(false);
        when(driverOfferRepository.existsByDriverIdAndStatusAndAssignmentStatus(driverId, OfferStatus.ACCEPTED, AssignmentStatus.PENDING)).thenReturn(false);
        when(driverAvailabilityProvider.findBusyDrivers(anyList())).thenReturn(Set.of());
    }

    private void mockDriverEligible(UUID driverId, UUID vehicleId, VehicleType type) {
        DriverEligibilityInfo info = createEligibleInfo(driverId, vehicleId, type);
        when(driverEligibilityProvider.getBatchEligibility(anyList())).thenReturn(Map.of(driverId, info));
    }

    private void mockBatchEligible(Map<UUID, DriverEligibilityInfo> map) {
        when(driverEligibilityProvider.getBatchEligibility(anyList())).thenReturn(map);
    }

    private DriverEligibilityInfo createEligibleInfo(UUID driverId, UUID vehicleId, VehicleType type) {
        return new DriverEligibilityInfo(
                driverId,
                true,
                "ACTIVE",
                "APPROVED",
                true,
                List.of(),
                List.of(new VehicleSummary(vehicleId, type, "29A-" + driverId.toString().substring(0, 5), "ACTIVE"))
        );
    }
}
