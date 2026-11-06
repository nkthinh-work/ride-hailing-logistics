package com.rhl.trip.repositories;

import com.rhl.trip.entities.Trip;
import com.rhl.trip.entities.TripStatusHistory;
import com.rhl.trip.models.ServiceType;
import com.rhl.trip.models.TripStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class TripPersistenceIntegrationTest {

    @Autowired
    private TripRepository tripRepository;

    @Autowired
    private TripStatusHistoryRepository historyRepository;

    private Trip buildTestTrip(UUID customerId, String idempotencyKey) {
        return Trip.builder()
                .idempotencyKey(idempotencyKey)
                .customerId(customerId)
                .quoteId(UUID.randomUUID())
                .serviceType(ServiceType.RIDE)
                .vehicleType("MOTORBIKE")
                .pickupLocation(BigDecimal.valueOf(21.028511), BigDecimal.valueOf(105.854444), "Hanoi Opera House")
                .dropoffLocation(BigDecimal.valueOf(21.036868), BigDecimal.valueOf(105.834608), "Ba Dinh Square")
                .estimatedDistanceKm(BigDecimal.valueOf(4.5))
                .estimatedDurationMinutes(15)
                .estimatedFare(BigDecimal.valueOf(35000))
                .surgeMultiplier(BigDecimal.valueOf(1.00))
                .currency("VND")
                .pricingRuleVersion("DEV-v1.0")
                .requestedAt(Instant.now())
                .build();
    }

    @Test
    @DisplayName("Trip persistence: Save and load trip with initial history")
    @Transactional
    void saveAndLoadTrip() {
        UUID customerId = UUID.randomUUID();
        String idempotencyKey = "key-" + UUID.randomUUID();

        Trip trip = buildTestTrip(customerId, idempotencyKey);
        Trip saved = tripRepository.saveAndFlush(trip);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(TripStatus.CREATED);
        assertThat(saved.getVersion()).isEqualTo(0L);

        Optional<Trip> fetched = tripRepository.findById(saved.getId());
        assertThat(fetched).isPresent();
        assertThat(fetched.get().getCustomerId()).isEqualTo(customerId);
        assertThat(fetched.get().getIdempotencyKey()).isEqualTo(idempotencyKey);
    }

    @Test
    @DisplayName("FR-TRIP-002: Find by customerId and idempotencyKey")
    @Transactional
    void findByCustomerIdAndIdempotencyKey() {
        UUID customerId = UUID.randomUUID();
        String idempotencyKey = "idem-" + UUID.randomUUID();

        Trip trip = buildTestTrip(customerId, idempotencyKey);
        tripRepository.saveAndFlush(trip);

        Optional<Trip> found = tripRepository.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey);
        assertThat(found).isPresent();
        assertThat(found.get().getIdempotencyKey()).isEqualTo(idempotencyKey);

        Optional<Trip> notFound = tripRepository.findByCustomerIdAndIdempotencyKey(customerId, "other-key");
        assertThat(notFound).isEmpty();
    }

    @Test
    @DisplayName("FR-TRIP-007: Active trip conflict detection via existsByCustomerIdAndStatusIn")
    @Transactional
    void existsByCustomerIdAndStatusIn() {
        UUID customerId = UUID.randomUUID();
        Trip trip = buildTestTrip(customerId, "key-" + UUID.randomUUID());
        tripRepository.saveAndFlush(trip);

        Set<TripStatus> activeStatuses = Set.of(
                TripStatus.CREATED, TripStatus.MATCHING, TripStatus.ACCEPTED,
                TripStatus.PICKING_UP, TripStatus.ARRIVED, TripStatus.IN_TRIP
        );

        boolean hasActive = tripRepository.existsByCustomerIdAndStatusIn(customerId, activeStatuses);
        assertThat(hasActive).isTrue();

        // After completing or cancelling
        trip.cancel(customerId, "CUSTOMER", "Cancel test", null, Instant.now());
        tripRepository.saveAndFlush(trip);

        boolean stillActive = tripRepository.existsByCustomerIdAndStatusIn(customerId, activeStatuses);
        assertThat(stillActive).isFalse();
    }

    @Test
    @DisplayName("Optimistic Locking: Version increments on update")
    @Transactional
    void optimisticLocking_incrementsVersionOnUpdate() {
        UUID customerId = UUID.randomUUID();
        Trip trip = buildTestTrip(customerId, "key-" + UUID.randomUUID());
        Trip saved = tripRepository.saveAndFlush(trip);
        assertThat(saved.getVersion()).isEqualTo(0L);

        saved.startMatching("SYSTEM");
        Trip updated = tripRepository.saveAndFlush(saved);

        assertThat(updated.getVersion()).isEqualTo(1L);
        assertThat(updated.getStatus()).isEqualTo(TripStatus.MATCHING);
    }

    @Test
    @DisplayName("Trip status history can be persisted and queried independently")
    @Transactional
    void statusHistoryPersistence() {
        UUID tripId = UUID.randomUUID();
        TripStatusHistory entry1 = TripStatusHistory.of(
                tripId, null, TripStatus.CREATED, UUID.randomUUID(), "CUSTOMER", "Created", Instant.now()
        );
        TripStatusHistory entry2 = TripStatusHistory.of(
                tripId, TripStatus.CREATED, TripStatus.MATCHING, UUID.randomUUID(), "SYSTEM", "Matching", Instant.now().plusSeconds(1)
        );

        historyRepository.save(entry1);
        historyRepository.save(entry2);
        historyRepository.flush();

        List<TripStatusHistory> historyList = historyRepository.findByTripIdOrderByCreatedAtAsc(tripId);
        assertThat(historyList).hasSize(2);
        assertThat(historyList.get(0).getToStatus()).isEqualTo(TripStatus.CREATED);
        assertThat(historyList.get(1).getToStatus()).isEqualTo(TripStatus.MATCHING);
    }
}
