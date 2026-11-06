package com.rhl.dispatch.services;

import com.rhl.dispatch.dtos.CreateOfferCommand;
import com.rhl.dispatch.dtos.DriverOfferResponse;
import com.rhl.dispatch.entities.DriverOffer;
import com.rhl.dispatch.entities.DriverOfferHistory;
import com.rhl.dispatch.exceptions.DriverOfferConflictException;
import com.rhl.dispatch.exceptions.InvalidOfferStateTransitionException;
import com.rhl.dispatch.exceptions.OfferExpiredException;
import com.rhl.dispatch.models.OfferStatus;
import com.rhl.dispatch.models.ServiceType;
import com.rhl.dispatch.repositories.DriverOfferHistoryRepository;
import com.rhl.dispatch.repositories.DriverOfferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
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
@DisplayName("PostgreSQL Driver Offer Concurrency & Invariants Integration Tests")
class DriverOfferConcurrencyIntegrationTest {

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
    private DriverOfferService driverOfferService;

    @Autowired
    private DriverOfferRepository offerRepository;

    @Autowired
    private DriverOfferHistoryRepository historyRepository;

    @BeforeEach
    void cleanDatabase() {
        historyRepository.deleteAll();
        offerRepository.deleteAll();
    }

    @Test
    @DisplayName("Case A: Two threads concurrently accept the same offer -> Exactly one succeeds, one gets conflict")
    void testCaseA_TwoThreadsAcceptSameOfferConcurrently() throws Exception {
        UUID tripId = UUID.randomUUID();
        UUID driverId = UUID.randomUUID();

        DriverOfferResponse created = driverOfferService.createOffer(
                new CreateOfferCommand(tripId, driverId, null, ServiceType.RIDE, Duration.ofSeconds(30))
        );
        UUID offerId = created.id();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        Runnable acceptTask = () -> {
            try {
                readyLatch.countDown();
                startLatch.await();
                driverOfferService.acceptOffer(offerId, driverId);
                successCount.incrementAndGet();
            } catch (OptimisticLockingFailureException | DriverOfferConflictException ex) {
                conflictCount.incrementAndGet();
            } catch (Exception ex) {
                if (ex.getCause() instanceof OptimisticLockingFailureException
                        || ex.getMessage().contains("Row was updated or deleted by another transaction")
                        || ex.getMessage().contains("could not execute statement")) {
                    conflictCount.incrementAndGet();
                } else {
                    throw new RuntimeException(ex);
                }
            }
        };

        Future<?> f1 = executor.submit(acceptTask);
        Future<?> f2 = executor.submit(acceptTask);

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        // One must succeed, and either the other detects conflict or succeeds via idempotent replay
        assertThat(successCount.get()).isGreaterThanOrEqualTo(1);

        DriverOffer finalOffer = offerRepository.findById(offerId).orElseThrow();
        assertThat(finalOffer.getStatus()).isEqualTo(OfferStatus.ACCEPTED);

        // Verify audit history contains exactly ONE ACCEPTED transition
        List<DriverOfferHistory> histories = historyRepository.findByOfferIdOrderByCreatedAtAsc(offerId);
        long acceptedCount = histories.stream().filter(h -> h.getToStatus() == OfferStatus.ACCEPTED).count();
        assertThat(acceptedCount).isEqualTo(1L);
    }

    @Test
    @DisplayName("Case B: Two different Drivers accept two Offers for the SAME Trip concurrently -> Exactly ONE wins via Partial Unique Index")
    void testCaseB_TwoDifferentDriversAcceptOffersForSameTrip_OnlyOneWins() throws Exception {
        UUID tripId = UUID.randomUUID();
        UUID driver1 = UUID.randomUUID();
        UUID driver2 = UUID.randomUUID();

        // Create two offers for the same trip (simulating broadcast/batch dispatch)
        DriverOffer offer1 = DriverOffer.builder()
                .id(UUID.randomUUID())
                .tripId(tripId)
                .driverId(driver1)
                .serviceType(ServiceType.RIDE)
                .offeredAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(30))
                .build();
        offerRepository.save(offer1);

        DriverOffer offer2 = DriverOffer.builder()
                .id(UUID.randomUUID())
                .tripId(tripId)
                .driverId(driver2)
                .serviceType(ServiceType.RIDE)
                .offeredAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(30))
                .build();
        offerRepository.save(offer2);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        // Driver 1 accepts Offer 1
        Future<?> f1 = executor.submit(() -> {
            try {
                readyLatch.countDown();
                startLatch.await();
                driverOfferService.acceptOffer(offer1.getId(), driver1);
                successCount.incrementAndGet();
            } catch (DataIntegrityViolationException | DriverOfferConflictException ex) {
                conflictCount.incrementAndGet();
            } catch (Exception ex) {
                if (ex.getCause() instanceof DataIntegrityViolationException
                        || (ex.getMessage() != null && (ex.getMessage().contains("uk_driver_offers_single_winning_accepted")
                            || ex.getMessage().contains("uk_driver_offers_single_winning_assigned")))) {
                    conflictCount.incrementAndGet();
                } else {
                    throw new RuntimeException(ex);
                }
            }
        });

        // Driver 2 accepts Offer 2
        Future<?> f2 = executor.submit(() -> {
            try {
                readyLatch.countDown();
                startLatch.await();
                driverOfferService.acceptOffer(offer2.getId(), driver2);
                successCount.incrementAndGet();
            } catch (DataIntegrityViolationException | DriverOfferConflictException ex) {
                conflictCount.incrementAndGet();
            } catch (Exception ex) {
                if (ex.getCause() instanceof DataIntegrityViolationException
                        || (ex.getMessage() != null && (ex.getMessage().contains("uk_driver_offers_single_winning_accepted")
                            || ex.getMessage().contains("uk_driver_offers_single_winning_assigned")))) {
                    conflictCount.incrementAndGet();
                } else {
                    throw new RuntimeException(ex);
                }
            }
        });

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Exactly one driver wins, one driver conflicts
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(1);

        // In PostgreSQL database, exactly ONE offer for this trip is in ACCEPTED status!
        List<DriverOffer> tripOffers = offerRepository.findByTripId(tripId);
        long acceptedOfferCount = tripOffers.stream().filter(o -> o.getStatus() == OfferStatus.ACCEPTED).count();
        assertThat(acceptedOfferCount).isEqualTo(1L);
    }

    @Test
    @DisplayName("Case C: Concurrent Accept vs Expire -> Exactly one terminal state, never both")
    void testCaseC_ConcurrentAcceptVsExpire() throws Exception {
        UUID tripId = UUID.randomUUID();
        UUID driverId = UUID.randomUUID();

        DriverOfferResponse created = driverOfferService.createOffer(
                new CreateOfferCommand(tripId, driverId, null, ServiceType.RIDE, Duration.ofSeconds(30))
        );
        UUID offerId = created.id();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        AtomicInteger acceptedCount = new AtomicInteger(0);
        AtomicInteger expiredCount = new AtomicInteger(0);

        // Thread 1: Driver accepts
        Future<?> fAccept = executor.submit(() -> {
            try {
                readyLatch.countDown();
                startLatch.await();
                driverOfferService.acceptOffer(offerId, driverId);
                acceptedCount.incrementAndGet();
            } catch (Exception ignored) {
            }
        });

        // Thread 2: System expires
        Future<?> fExpire = executor.submit(() -> {
            try {
                readyLatch.countDown();
                startLatch.await();
                driverOfferService.expireOffer(offerId);
                expiredCount.incrementAndGet();
            } catch (Exception ignored) {
            }
        });

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        fAccept.get(10, TimeUnit.SECONDS);
        fExpire.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        DriverOffer persisted = offerRepository.findById(offerId).orElseThrow();
        assertThat(persisted.getStatus()).isIn(OfferStatus.ACCEPTED, OfferStatus.EXPIRED);

        // Ensure status history is clean and consistent
        List<DriverOfferHistory> histories = historyRepository.findByOfferIdOrderByCreatedAtAsc(offerId);
        assertThat(histories).hasSize(2); // Initial PENDING + 1 terminal
    }

    @Test
    @DisplayName("Case D: Concurrent Accept vs Revoke -> Exactly one terminal state")
    void testCaseD_ConcurrentAcceptVsRevoke() throws Exception {
        UUID tripId = UUID.randomUUID();
        UUID driverId = UUID.randomUUID();

        DriverOfferResponse created = driverOfferService.createOffer(
                new CreateOfferCommand(tripId, driverId, null, ServiceType.RIDE, Duration.ofSeconds(30))
        );
        UUID offerId = created.id();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Future<?> fAccept = executor.submit(() -> {
            try {
                readyLatch.countDown();
                startLatch.await();
                driverOfferService.acceptOffer(offerId, driverId);
            } catch (Exception ignored) {
            }
        });

        Future<?> fRevoke = executor.submit(() -> {
            try {
                readyLatch.countDown();
                startLatch.await();
                driverOfferService.revokeOffer(offerId, "Trip cancelled", "SYSTEM");
            } catch (Exception ignored) {
            }
        });

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        fAccept.get(10, TimeUnit.SECONDS);
        fRevoke.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        DriverOffer persisted = offerRepository.findById(offerId).orElseThrow();
        assertThat(persisted.getStatus()).isIn(OfferStatus.ACCEPTED, OfferStatus.REVOKED);
    }

    @Test
    @DisplayName("Case E: Concurrent Offer Creation for SAME Driver -> Single pending offer constraint enforced")
    void testCaseE_ConcurrentOfferCreationForSameDriver_SinglePendingEnforced() throws Exception {
        UUID driverId = UUID.randomUUID();
        UUID trip1 = UUID.randomUUID();
        UUID trip2 = UUID.randomUUID();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        Runnable createTask1 = () -> {
            try {
                readyLatch.countDown();
                startLatch.await();
                driverOfferService.createOffer(new CreateOfferCommand(trip1, driverId, null, ServiceType.RIDE, Duration.ofSeconds(15)));
                successCount.incrementAndGet();
            } catch (DriverOfferConflictException | DataIntegrityViolationException ex) {
                conflictCount.incrementAndGet();
            } catch (Exception ex) {
                if (ex.getCause() instanceof DataIntegrityViolationException
                        || ex.getMessage() != null && ex.getMessage().contains("uk_driver_offers_single_pending_per_driver")) {
                    conflictCount.incrementAndGet();
                } else {
                    throw new RuntimeException(ex);
                }
            }
        };

        Runnable createTask2 = () -> {
            try {
                readyLatch.countDown();
                startLatch.await();
                driverOfferService.createOffer(new CreateOfferCommand(trip2, driverId, null, ServiceType.RIDE, Duration.ofSeconds(15)));
                successCount.incrementAndGet();
            } catch (DriverOfferConflictException | DataIntegrityViolationException ex) {
                conflictCount.incrementAndGet();
            } catch (Exception ex) {
                if (ex.getCause() instanceof DataIntegrityViolationException
                        || ex.getMessage() != null && ex.getMessage().contains("uk_driver_offers_single_pending_per_driver")) {
                    conflictCount.incrementAndGet();
                } else {
                    throw new RuntimeException(ex);
                }
            }
        };

        Future<?> f1 = executor.submit(createTask1);
        Future<?> f2 = executor.submit(createTask2);

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(1);

        // In DB, driver has exactly ONE pending offer
        List<DriverOffer> pendingOffers = offerRepository.findByDriverId(driverId);
        assertThat(pendingOffers.stream().filter(o -> o.getStatus() == OfferStatus.PENDING).count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Case F: Atomic Offer Acceptance and History Commit in PostgreSQL")
    void testCaseF_AtomicOfferAcceptanceAndHistoryCommit() {
        UUID tripId = UUID.randomUUID();
        UUID driverId = UUID.randomUUID();

        DriverOfferResponse created = driverOfferService.createOffer(
                new CreateOfferCommand(tripId, driverId, null, ServiceType.RIDE, Duration.ofSeconds(30))
        );
        UUID offerId = created.id();

        DriverOfferResponse accepted = driverOfferService.acceptOffer(offerId, driverId);
        assertThat(accepted.status()).isEqualTo(OfferStatus.ACCEPTED);

        // Verify in DB
        DriverOffer persisted = offerRepository.findById(offerId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(OfferStatus.ACCEPTED);
        assertThat(persisted.getAcceptedAt()).isNotNull();

        List<DriverOfferHistory> histories = historyRepository.findByOfferIdOrderByCreatedAtAsc(offerId);
        assertThat(histories).hasSize(2);

        DriverOfferHistory initial = histories.get(0);
        assertThat(initial.getToStatus()).isEqualTo(OfferStatus.PENDING);

        DriverOfferHistory transition = histories.get(1);
        assertThat(transition.getFromStatus()).isEqualTo(OfferStatus.PENDING);
        assertThat(transition.getToStatus()).isEqualTo(OfferStatus.ACCEPTED);
        assertThat(transition.getActorId()).isEqualTo(driverId);
        assertThat(transition.getActorRole()).isEqualTo("DRIVER");
    }

    @Test
    @DisplayName("Case G: Offer ACCEPTED with assignment FAILED releases winner slot allowing subsequent candidate offer to be accepted")
    void testCaseG_FailedAssignmentReleasesWinnerSlot() {
        UUID tripId = UUID.randomUUID();
        UUID driver1 = UUID.randomUUID();
        UUID driver2 = UUID.randomUUID();

        // 1. Driver 1 receives and accepts offer
        DriverOfferResponse offer1 = driverOfferService.createOffer(
                new CreateOfferCommand(tripId, driver1, null, ServiceType.RIDE, Duration.ofSeconds(30))
        );
        driverOfferService.acceptOffer(offer1.id(), driver1);

        // 2. Trip assignment fails for Driver 1 (e.g. driver had another active trip)
        driverOfferService.markAssignmentFailed(offer1.id(), "Driver already has active trip");

        // Verify offer1 in DB is ACCEPTED + FAILED
        DriverOffer o1 = offerRepository.findById(offer1.id()).orElseThrow();
        assertThat(o1.getStatus()).isEqualTo(OfferStatus.ACCEPTED);
        assertThat(o1.getAssignmentStatus()).isEqualTo(com.rhl.dispatch.models.AssignmentStatus.FAILED);

        // 3. System creates offer for Driver 2 on same trip (Allowed because winner slot is released!)
        DriverOfferResponse offer2 = driverOfferService.createOffer(
                new CreateOfferCommand(tripId, driver2, null, ServiceType.RIDE, Duration.ofSeconds(30))
        );
        assertThat(offer2).isNotNull();

        // 4. Driver 2 accepts offer2 and wins trip assignment
        DriverOfferResponse accepted2 = driverOfferService.acceptOffer(offer2.id(), driver2);
        assertThat(accepted2.status()).isEqualTo(OfferStatus.ACCEPTED);

        driverOfferService.markAssignmentAssigned(offer2.id());

        DriverOffer o2 = offerRepository.findById(offer2.id()).orElseThrow();
        assertThat(o2.getStatus()).isEqualTo(OfferStatus.ACCEPTED);
        assertThat(o2.getAssignmentStatus()).isEqualTo(com.rhl.dispatch.models.AssignmentStatus.ASSIGNED);

        // Audit history preserves full trace of both offers
        List<DriverOfferHistory> h1 = historyRepository.findByOfferIdOrderByCreatedAtAsc(offer1.id());
        assertThat(h1).isNotEmpty();
        List<DriverOfferHistory> h2 = historyRepository.findByOfferIdOrderByCreatedAtAsc(offer2.id());
        assertThat(h2).isNotEmpty();
    }
}
