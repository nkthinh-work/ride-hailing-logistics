package com.rhl.dispatch.services;

import com.rhl.dispatch.entities.DriverOffer;
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
import com.rhl.dispatch.repositories.DriverOfferHistoryRepository;
import com.rhl.dispatch.repositories.DriverOfferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@EnabledIf("isDockerAvailable")
@DisplayName("Candidate Selection with PostgreSQL Testcontainers Integration Tests")
class CandidateSelectionPostgresIntegrationTest {

    static PostgreSQLContainer postgres;

    static boolean isDockerAvailable() {
        try {
            DockerClientFactory.instance().client();
            if (postgres == null) {
                postgres = new PostgreSQLContainer("postgres:15-alpine")
                        .withDatabaseName("rhl_dispatch_test")
                        .withUsername("test_user")
                        .withPassword("test_pass");
                postgres.start();
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        if (postgres != null && postgres.isRunning()) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);
        }
    }

    @Autowired
    private DriverOfferRepository driverOfferRepository;

    @Autowired
    private DriverOfferHistoryRepository driverOfferHistoryRepository;

    @Autowired
    private DriverCandidateSelector candidateSelector;

    @MockitoBean
    private LocationNearbyProvider locationNearbyProvider;

    @MockitoBean
    private DriverEligibilityProvider driverEligibilityProvider;

    @MockitoBean
    private DriverAvailabilityProvider driverAvailabilityProvider;

    private final UUID driver1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID driver2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private final UUID driver3 = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private final UUID vehicle1 = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private final UUID vehicle2 = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private final UUID vehicle3 = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    @BeforeEach
    void cleanDatabase() {
        driverOfferHistoryRepository.deleteAll();
        driverOfferRepository.deleteAll();
    }

    @Test
    @DisplayName("Excludes candidates based on real PostgreSQL PENDING and ACCEPTED offers, selecting only available driver")
    void candidateSelection_withRealPostgreSqlOfferState() {
        Instant now = Instant.now();
        UUID otherTripA = UUID.randomUUID();
        UUID otherTripB = UUID.randomUUID();

        // Persist Driver 1 with PENDING offer
        DriverOffer pendingOffer = DriverOffer.builder()
                .id(UUID.randomUUID())
                .tripId(otherTripA)
                .driverId(driver1)
                .serviceType(ServiceType.RIDE)
                .offeredAt(now)
                .expiresAt(now.plusSeconds(15))
                .actor(driver1, "SYSTEM")
                .build();
        driverOfferRepository.saveAndFlush(pendingOffer);

        // Persist Driver 2 with ACCEPTED offer
        DriverOffer acceptedOffer = DriverOffer.builder()
                .id(UUID.randomUUID())
                .tripId(otherTripB)
                .driverId(driver2)
                .serviceType(ServiceType.RIDE)
                .offeredAt(now.minusSeconds(10))
                .expiresAt(now.plusSeconds(5))
                .actor(driver2, "SYSTEM")
                .build();
        acceptedOffer.accept(driver2, now.minusSeconds(5));
        driverOfferRepository.saveAndFlush(acceptedOffer);

        // Driver 3 has no active offers in PostgreSQL

        // Mock Location: returns all 3 drivers
        NearbyDriverLocation loc1 = new NearbyDriverLocation(driver1, 105.85, 21.02, 0.4, now.minusSeconds(5));
        NearbyDriverLocation loc2 = new NearbyDriverLocation(driver2, 105.85, 21.02, 0.6, now.minusSeconds(5));
        NearbyDriverLocation loc3 = new NearbyDriverLocation(driver3, 105.85, 21.02, 0.8, now.minusSeconds(5));
        when(locationNearbyProvider.findNearbyDrivers(anyDouble(), anyDouble(), anyDouble(), anyInt()))
                .thenReturn(List.of(loc1, loc2, loc3));

        // Mock Trip Availability: no drivers in active trip
        when(driverAvailabilityProvider.findBusyDrivers(anyList())).thenReturn(Set.of());

        // Mock Identity: all drivers eligible with MOTORBIKE
        when(driverEligibilityProvider.getBatchEligibility(anyList())).thenReturn(Map.of(
                driver1, new DriverEligibilityInfo(driver1, true, "ACTIVE", "APPROVED", true, List.of(),
                        List.of(new VehicleSummary(vehicle1, VehicleType.MOTORBIKE, "29A-11111", "ACTIVE"))),
                driver2, new DriverEligibilityInfo(driver2, true, "ACTIVE", "APPROVED", true, List.of(),
                        List.of(new VehicleSummary(vehicle2, VehicleType.MOTORBIKE, "29A-22222", "ACTIVE"))),
                driver3, new DriverEligibilityInfo(driver3, true, "ACTIVE", "APPROVED", true, List.of(),
                        List.of(new VehicleSummary(vehicle3, VehicleType.MOTORBIKE, "29A-33333", "ACTIVE")))
        ));

        // Execute Candidate Selection
        MatchingCriteria criteria = MatchingCriteria.builder()
                .tripId(UUID.randomUUID())
                .pickupLocation(21.0285, 105.8542)
                .serviceType(ServiceType.RIDE)
                .vehicleType(VehicleType.MOTORBIKE)
                .radiusKm(1.0)
                .build();

        CandidateSelectionResult result = candidateSelector.selectCandidates(criteria);

        // Assertions
        assertThat(result.hasCandidates()).isTrue();
        assertThat(result.candidateCount()).isEqualTo(1);
        assertThat(result.candidates().getFirst().driverId()).isEqualTo(driver3);

        // Verify exclusions
        assertThat(result.exclusions().get(driver1)).isEqualTo(CandidateExclusionReason.HAS_PENDING_OFFER);
        assertThat(result.exclusions().get(driver2)).isEqualTo(CandidateExclusionReason.ACCEPTED_PENDING_ASSIGNMENT);
    }

    @Test
    @DisplayName("Historical ACCEPTED offer with completed trip assignment does not exclude driver")
    void candidateSelection_withHistoricalCompletedOffer_eligible() {
        Instant now = Instant.now();
        UUID completedTrip = UUID.randomUUID();

        // Persist Driver 1 with historical completed offer
        DriverOffer historicalOffer = DriverOffer.builder()
                .id(UUID.randomUUID())
                .tripId(completedTrip)
                .driverId(driver1)
                .serviceType(ServiceType.RIDE)
                .offeredAt(now.minusSeconds(300))
                .expiresAt(now.minusSeconds(285))
                .actor(driver1, "SYSTEM")
                .build();
        historicalOffer.accept(driver1, now.minusSeconds(290));
        historicalOffer.markAssigned(now.minusSeconds(280));
        driverOfferRepository.saveAndFlush(historicalOffer);

        // Location: returns driver 1
        NearbyDriverLocation loc1 = new NearbyDriverLocation(driver1, 105.85, 21.02, 0.4, now.minusSeconds(5));
        when(locationNearbyProvider.findNearbyDrivers(anyDouble(), anyDouble(), anyDouble(), anyInt()))
                .thenReturn(List.of(loc1));

        // Trip Service: driver1 is not busy
        when(driverAvailabilityProvider.findBusyDrivers(anyList())).thenReturn(Set.of());

        // Identity: driver1 is eligible
        when(driverEligibilityProvider.getBatchEligibility(anyList())).thenReturn(Map.of(
                driver1, new DriverEligibilityInfo(driver1, true, "ACTIVE", "APPROVED", true, List.of(),
                        List.of(new VehicleSummary(vehicle1, VehicleType.MOTORBIKE, "29A-11111", "ACTIVE")))
        ));

        MatchingCriteria criteria = MatchingCriteria.builder()
                .tripId(UUID.randomUUID())
                .pickupLocation(21.0285, 105.8542)
                .serviceType(ServiceType.RIDE)
                .vehicleType(VehicleType.MOTORBIKE)
                .radiusKm(1.0)
                .build();

        CandidateSelectionResult result = candidateSelector.selectCandidates(criteria);

        assertThat(result.hasCandidates()).isTrue();
        assertThat(result.candidates().getFirst().driverId()).isEqualTo(driver1);
    }
}
