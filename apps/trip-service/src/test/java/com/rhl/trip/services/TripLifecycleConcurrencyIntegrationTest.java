package com.rhl.trip.services;

import com.rhl.trip.clients.PricingQuoteClient;
import com.rhl.trip.dtos.CancelTripRequest;
import com.rhl.trip.dtos.TripResponse;
import com.rhl.trip.entities.Trip;
import com.rhl.trip.entities.TripStatusHistory;
import com.rhl.trip.models.ServiceType;
import com.rhl.trip.models.TripStatus;
import com.rhl.trip.repositories.TripRepository;
import com.rhl.trip.repositories.TripStatusHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@EnabledIf("isDockerAvailable")
@DisplayName("PostgreSQL Trip Lifecycle & Concurrency Integration Tests")
class TripLifecycleConcurrencyIntegrationTest {

    static PostgreSQLContainer postgres;

    static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        if (isDockerAvailable()) {
            if (postgres == null) {
                postgres = new PostgreSQLContainer("postgres:15-alpine");
                postgres.start();
            }
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        }
    }

    @Autowired
    private TripCancellationService tripCancellationService;

    @Autowired
    private TripProgressionService tripProgressionService;

    @Autowired
    private TripRepository tripRepository;

    @Autowired
    private TripStatusHistoryRepository historyRepository;

    @MockitoBean
    private PricingQuoteClient pricingQuoteClient;

    @BeforeEach
    void cleanDatabase() {
        historyRepository.deleteAll();
        tripRepository.deleteAll();
    }

    private Trip createAndPersistAcceptedTrip(UUID customerId, UUID driverId) {
        Trip trip = Trip.builder()
                .id(UUID.randomUUID())
                .customerId(customerId)
                .idempotencyKey("idemp-" + UUID.randomUUID())
                .quoteId(UUID.randomUUID())
                .serviceType(ServiceType.RIDE)
                .vehicleType("MOTORBIKE")
                .pickupLocation(new BigDecimal("10.7769"), new BigDecimal("106.7009"), "Pickup Address")
                .dropoffLocation(new BigDecimal("10.7725"), new BigDecimal("106.6980"), "Dropoff Address")
                .estimatedDistanceKm(new BigDecimal("5.00"))
                .estimatedDurationMinutes(10)
                .estimatedFare(new BigDecimal("40000.00"))
                .surgeMultiplier(new BigDecimal("1.00"))
                .currency("VND")
                .pricingRuleVersion("DEV-v1.0")
                .requestedAt(Instant.now())
                .build();

        // Advance to ACCEPTED in domain
        trip.startMatching("SYSTEM");
        trip.accept(driverId, UUID.randomUUID(), "DRIVER", Instant.now());

        return tripRepository.saveAndFlush(trip);
    }

    @Test
    @DisplayName("Race condition: Customer cancel vs Driver startPickup - Optimistic locking resolves conflict")
    void testConcurrentCustomerCancelVsDriverStartPickup_OptimisticLockingResolvesConflict() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID driverId = UUID.randomUUID();
        Trip acceptedTrip = createAndPersistAcceptedTrip(customerId, driverId);
        UUID tripId = acceptedTrip.getId();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        // Task 1: Customer cancel
        Future<?> futureCancel = executor.submit(() -> {
            try {
                readyLatch.countDown();
                startLatch.await();
                tripCancellationService.cancelTrip(tripId, customerId, "CUSTOMER", new CancelTripRequest("CUSTOMER_CHANGE_OF_MIND"));
                successCount.incrementAndGet();
            } catch (OptimisticLockingFailureException ex) {
                conflictCount.incrementAndGet();
            } catch (Exception ex) {
                if (ex.getCause() instanceof OptimisticLockingFailureException
                        || ex.getMessage().contains("Row was updated or deleted by another transaction")) {
                    conflictCount.incrementAndGet();
                } else {
                    throw new RuntimeException(ex);
                }
            }
        });

        // Task 2: Driver start-pickup
        Future<?> futureProgress = executor.submit(() -> {
            try {
                readyLatch.countDown();
                startLatch.await();
                tripProgressionService.startPickingUp(tripId, driverId);
                successCount.incrementAndGet();
            } catch (OptimisticLockingFailureException ex) {
                conflictCount.incrementAndGet();
            } catch (Exception ex) {
                if (ex.getCause() instanceof OptimisticLockingFailureException
                        || ex.getMessage().contains("Row was updated or deleted by another transaction")) {
                    conflictCount.incrementAndGet();
                } else {
                    throw new RuntimeException(ex);
                }
            }
        });

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        futureCancel.get(10, TimeUnit.SECONDS);
        futureProgress.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Exactly one should succeed and one should detect conflict (or if serialized, terminal checks apply)
        assertThat(successCount.get() + conflictCount.get()).isEqualTo(2);
        assertThat(successCount.get()).isGreaterThanOrEqualTo(1);

        Trip finalTrip = tripRepository.findById(tripId).orElseThrow();
        // State must be either CANCELLED or PICKING_UP (or CANCELLED after PICKING_UP), never inconsistent
        assertThat(finalTrip.getStatus()).isIn(TripStatus.CANCELLED, TripStatus.PICKING_UP);
    }

    @Test
    @DisplayName("Atomic cancellation and history commit in PostgreSQL")
    void testAtomicCancellationAndHistory_CommittedTogether() {
        UUID customerId = UUID.randomUUID();
        UUID driverId = UUID.randomUUID();
        Trip trip = createAndPersistAcceptedTrip(customerId, driverId);
        UUID tripId = trip.getId();

        int initialHistoryCount = historyRepository.findByTripIdOrderByCreatedAtAsc(tripId).size();

        CancelTripRequest request = new CancelTripRequest("VEHICLE_ISSUE", "Driver reported vehicle issue");
        TripResponse response = tripCancellationService.cancelTrip(tripId, driverId, "DRIVER", request);

        assertThat(response.status()).isEqualTo(TripStatus.CANCELLED);

        // Verify in DB
        Trip persisted = tripRepository.findById(tripId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(TripStatus.CANCELLED);
        assertThat(persisted.getCancelledBy()).isEqualTo(driverId);
        assertThat(persisted.getCancellationActorRole()).isEqualTo("DRIVER");
        assertThat(persisted.getCancellationReason()).isEqualTo("VEHICLE_ISSUE: Driver reported vehicle issue");

        List<TripStatusHistory> histories = historyRepository.findByTripIdOrderByCreatedAtAsc(tripId);
        assertThat(histories).hasSize(initialHistoryCount + 1);

        TripStatusHistory lastHistory = histories.get(histories.size() - 1);
        assertThat(lastHistory.getFromStatus()).isEqualTo(TripStatus.ACCEPTED);
        assertThat(lastHistory.getToStatus()).isEqualTo(TripStatus.CANCELLED);
        assertThat(lastHistory.getActorId()).isEqualTo(driverId);
        assertThat(lastHistory.getActorRole()).isEqualTo("DRIVER");
        assertThat(lastHistory.getReason()).contains("VEHICLE_ISSUE");
    }

    @Test
    @DisplayName("Repeated cancellation is idempotent: returns cancelled trip without duplicate history records")
    void testRepeatedCancel_IdempotentAudit() {
        UUID customerId = UUID.randomUUID();
        UUID driverId = UUID.randomUUID();
        Trip trip = createAndPersistAcceptedTrip(customerId, driverId);
        UUID tripId = trip.getId();

        // Cancel first time
        tripCancellationService.cancelTrip(tripId, customerId, "CUSTOMER", new CancelTripRequest("CANCEL_1"));
        int historyCountAfterFirst = historyRepository.findByTripIdOrderByCreatedAtAsc(tripId).size();

        // Cancel second time with same customer
        TripResponse response2 = tripCancellationService.cancelTrip(tripId, customerId, "CUSTOMER", new CancelTripRequest("CANCEL_RETRY"));

        assertThat(response2.status()).isEqualTo(TripStatus.CANCELLED);
        // History count should NOT increase on idempotent retry
        int historyCountAfterSecond = historyRepository.findByTripIdOrderByCreatedAtAsc(tripId).size();
        assertThat(historyCountAfterSecond).isEqualTo(historyCountAfterFirst);
    }

    @Test
    @DisplayName("Sequential lifecycle progression through all states verified in PostgreSQL audit")
    void testSequentialLifecycleProgression_AllHistoryAuditedInPostgres() {
        UUID customerId = UUID.randomUUID();
        UUID driverId = UUID.randomUUID();
        Trip trip = createAndPersistAcceptedTrip(customerId, driverId);
        UUID tripId = trip.getId();

        // 1. ACCEPTED -> PICKING_UP
        TripResponse resp1 = tripProgressionService.startPickingUp(tripId, driverId);
        assertThat(resp1.status()).isEqualTo(TripStatus.PICKING_UP);

        // 2. PICKING_UP -> ARRIVED
        TripResponse resp2 = tripProgressionService.confirmArrived(tripId, driverId);
        assertThat(resp2.status()).isEqualTo(TripStatus.ARRIVED);
        assertThat(resp2.arrivedAt()).isNotNull();

        // 3. ARRIVED -> IN_TRIP
        TripResponse resp3 = tripProgressionService.startTrip(tripId, driverId);
        assertThat(resp3.status()).isEqualTo(TripStatus.IN_TRIP);
        assertThat(resp3.startedAt()).isNotNull();

        // 4. IN_TRIP -> COMPLETED
        TripResponse resp4 = tripProgressionService.completeTrip(tripId, driverId);
        assertThat(resp4.status()).isEqualTo(TripStatus.COMPLETED);
        assertThat(resp4.completedAt()).isNotNull();

        // Verify final state in DB
        Trip persisted = tripRepository.findById(tripId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(TripStatus.COMPLETED);

        // Verify full history chain in PostgreSQL
        List<TripStatusHistory> histories = historyRepository.findByTripIdOrderByCreatedAtAsc(tripId);
        List<TripStatus> toStatuses = histories.stream().map(TripStatusHistory::getToStatus).toList();

        assertThat(toStatuses).containsSubsequence(
                TripStatus.MATCHING,
                TripStatus.ACCEPTED,
                TripStatus.PICKING_UP,
                TripStatus.ARRIVED,
                TripStatus.IN_TRIP,
                TripStatus.COMPLETED
        );
    }
}
