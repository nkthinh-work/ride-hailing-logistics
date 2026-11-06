package com.rhl.trip.services;

import com.rhl.trip.clients.PricingQuoteClient;
import com.rhl.trip.dtos.CreateTripRequest;
import com.rhl.trip.dtos.QuoteValidationClientRequest;
import com.rhl.trip.dtos.QuoteValidationClientResponse;
import com.rhl.trip.dtos.TripResponse;
import com.rhl.trip.entities.Trip;
import com.rhl.trip.entities.TripStatusHistory;
import com.rhl.trip.exceptions.ActiveTripConflictException;
import com.rhl.trip.exceptions.QuoteReplayConflictException;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@EnabledIf("isDockerAvailable")
@DisplayName("PostgreSQL Concurrency & Atomic Trip Creation Tests")
class TripBookingConcurrencyIntegrationTest {

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
    private BookingService bookingService;

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

    private QuoteValidationClientResponse createQuoteSnapshot(UUID quoteId, UUID customerId) {
        return new QuoteValidationClientResponse(
                quoteId,
                customerId,
                ServiceType.RIDE,
                "MOTORBIKE",
                "DEFAULT",
                new BigDecimal("10.7769000"),
                new BigDecimal("106.7009000"),
                "Saigon Opera House",
                new BigDecimal("10.7725000"),
                new BigDecimal("106.6980000"),
                "Ben Thanh Market",
                new BigDecimal("5.00"),
                10,
                new BigDecimal("42762.50"),
                new BigDecimal("1.25"),
                "VND",
                "DEV-v1.0",
                Instant.now().plusSeconds(300),
                true
        );
    }

    @Test
    @DisplayName("Concurrency: 2 concurrent requests with same quoteId -> exactly 1 succeeds, 1 receives QuoteReplayConflictException")
    void concurrentRequests_SameQuoteId_ExactlyOneSucceeds() throws Exception {
        UUID quoteId = UUID.randomUUID();
        UUID customerA = UUID.randomUUID();
        UUID customerB = UUID.randomUUID();

        when(pricingQuoteClient.validateQuote(any(QuoteValidationClientRequest.class)))
                .thenAnswer(inv -> {
                    QuoteValidationClientRequest req = inv.getArgument(0);
                    return createQuoteSnapshot(quoteId, req.customerId());
                });

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger quoteReplayConflictCount = new AtomicInteger(0);
        List<Throwable> unexpectedErrors = Collections.synchronizedList(new ArrayList<>());

        // Thread 1
        executor.submit(() -> {
            try {
                startLatch.await();
                CreateTripRequest req = new CreateTripRequest(
                        quoteId, "key-1", ServiceType.RIDE, "MOTORBIKE", true, null, null, null, null
                );
                bookingService.bookTrip(customerA, req);
                successCount.incrementAndGet();
            } catch (QuoteReplayConflictException ex) {
                quoteReplayConflictCount.incrementAndGet();
            } catch (Throwable t) {
                unexpectedErrors.add(t);
            } finally {
                endLatch.countDown();
            }
        });

        // Thread 2
        executor.submit(() -> {
            try {
                startLatch.await();
                CreateTripRequest req = new CreateTripRequest(
                        quoteId, "key-2", ServiceType.RIDE, "MOTORBIKE", true, null, null, null, null
                );
                bookingService.bookTrip(customerB, req);
                successCount.incrementAndGet();
            } catch (QuoteReplayConflictException ex) {
                quoteReplayConflictCount.incrementAndGet();
            } catch (Throwable t) {
                unexpectedErrors.add(t);
            } finally {
                endLatch.countDown();
            }
        });

        startLatch.countDown();
        assertThat(endLatch.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        assertThat(unexpectedErrors).isEmpty();
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(quoteReplayConflictCount.get()).isEqualTo(1);

        // Verify only 1 row in database
        assertThat(tripRepository.count()).isEqualTo(1);
        assertThat(tripRepository.existsByQuoteId(quoteId)).isTrue();
    }

    @Test
    @DisplayName("Concurrency: 2 concurrent requests for SAME customer with DIFFERENT quotes -> exactly 1 succeeds, 1 receives ActiveTripConflictException")
    void concurrentRequests_SameCustomer_SingleActiveTripEnforced() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID quote1 = UUID.randomUUID();
        UUID quote2 = UUID.randomUUID();

        when(pricingQuoteClient.validateQuote(any(QuoteValidationClientRequest.class)))
                .thenAnswer(inv -> {
                    QuoteValidationClientRequest req = inv.getArgument(0);
                    return createQuoteSnapshot(req.quoteId(), customerId);
                });

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger activeTripConflictCount = new AtomicInteger(0);
        List<Throwable> unexpectedErrors = Collections.synchronizedList(new ArrayList<>());

        executor.submit(() -> {
            try {
                startLatch.await();
                CreateTripRequest req = new CreateTripRequest(
                        quote1, "key-c1", ServiceType.RIDE, "MOTORBIKE", true, null, null, null, null
                );
                bookingService.bookTrip(customerId, req);
                successCount.incrementAndGet();
            } catch (ActiveTripConflictException ex) {
                activeTripConflictCount.incrementAndGet();
            } catch (Throwable t) {
                unexpectedErrors.add(t);
            } finally {
                endLatch.countDown();
            }
        });

        executor.submit(() -> {
            try {
                startLatch.await();
                CreateTripRequest req = new CreateTripRequest(
                        quote2, "key-c2", ServiceType.RIDE, "MOTORBIKE", true, null, null, null, null
                );
                bookingService.bookTrip(customerId, req);
                successCount.incrementAndGet();
            } catch (ActiveTripConflictException ex) {
                activeTripConflictCount.incrementAndGet();
            } catch (Throwable t) {
                unexpectedErrors.add(t);
            } finally {
                endLatch.countDown();
            }
        });

        startLatch.countDown();
        assertThat(endLatch.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        assertThat(unexpectedErrors).isEmpty();
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(activeTripConflictCount.get()).isEqualTo(1);

        // Verify customer has exactly 1 active trip in PostgreSQL
        List<Trip> trips = tripRepository.findByCustomerIdOrderByRequestedAtDesc(customerId);
        assertThat(trips).hasSize(1);
        assertThat(trips.get(0).getStatus()).isEqualTo(TripStatus.CREATED);
    }

    @Test
    @DisplayName("Idempotent Concurrency: Identical retries simultaneously committed return same Trip")
    void concurrentIdenticalRetries_BothReturnSameTrip() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        String idempotencyKey = "concurrent-idemp-1";

        when(pricingQuoteClient.validateQuote(any(QuoteValidationClientRequest.class)))
                .thenReturn(createQuoteSnapshot(quoteId, customerId));

        CreateTripRequest req = new CreateTripRequest(
                quoteId, idempotencyKey, ServiceType.RIDE, "MOTORBIKE", true, null, null, null, null
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Future<TripResponse> future1 = executor.submit(() -> {
            startLatch.await();
            return bookingService.bookTrip(customerId, req);
        });

        Future<TripResponse> future2 = executor.submit(() -> {
            startLatch.await();
            return bookingService.bookTrip(customerId, req);
        });

        startLatch.countDown();
        TripResponse res1 = future1.get(10, TimeUnit.SECONDS);
        TripResponse res2 = future2.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(res1.tripId()).isEqualTo(res2.tripId());
        assertThat(res1.quoteId()).isEqualTo(quoteId);
        assertThat(tripRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Local Atomicity: Trip and Status History created atomically in same transaction")
    void atomicTripCreation_InsertsInitialStatusHistory() {
        UUID customerId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        String idempotencyKey = "atomicity-test-key";

        when(pricingQuoteClient.validateQuote(any(QuoteValidationClientRequest.class)))
                .thenReturn(createQuoteSnapshot(quoteId, customerId));

        CreateTripRequest req = new CreateTripRequest(
                quoteId, idempotencyKey, ServiceType.RIDE, "MOTORBIKE", true, null, null, null, null
        );

        TripResponse response = bookingService.bookTrip(customerId, req);

        assertThat(response.status()).isEqualTo(TripStatus.CREATED);

        List<TripStatusHistory> history = historyRepository.findByTripIdOrderByCreatedAtAsc(response.tripId());
        assertThat(history).hasSize(1);
        TripStatusHistory initial = history.get(0);
        assertThat(initial.getFromStatus()).isNull();
        assertThat(initial.getToStatus()).isEqualTo(TripStatus.CREATED);
        assertThat(initial.getActorId()).isEqualTo(customerId);
        assertThat(initial.getActorRole()).isEqualTo("CUSTOMER");
    }
}
