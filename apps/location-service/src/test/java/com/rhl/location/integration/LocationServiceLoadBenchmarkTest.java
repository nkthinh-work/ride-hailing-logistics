package com.rhl.location.integration;

import com.rhl.location.clients.IdentityDriverClient;
import com.rhl.location.dtos.DriverEligibilityClientResponse;
import com.rhl.location.dtos.DriverLocationResponse;
import com.rhl.location.dtos.LocationUpdateRequest;
import com.rhl.location.dtos.NearbyDriverResponse;
import com.rhl.location.services.DriverLocationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@SpringBootTest
class LocationServiceLoadBenchmarkTest {

    static GenericContainer<?> redisContainer = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    static {
        redisContainer.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redisContainer::getHost);
        registry.add("spring.data.redis.port", redisContainer::getFirstMappedPort);
    }

    @Autowired
    private DriverLocationService locationService;

    @Autowired
    private StringRedisTemplate redis;

    @MockitoBean
    private IdentityDriverClient identityClient;

    private static final int SIMULATED_DRIVERS = 100;
    private static final int UPDATES_PER_DRIVER = 5;

    @BeforeEach
    void setUp() {
        when(identityClient.getEligibility(any(UUID.class))).thenAnswer(invocation -> {
            UUID id = invocation.getArgument(0);
            return new DriverEligibilityClientResponse(id, true, "ACTIVE", "APPROVED", true, List.of());
        });

        when(identityClient.getBatchEligibility(anyList())).thenAnswer(invocation -> {
            List<UUID> ids = invocation.getArgument(0);
            return ids.stream()
                    .map(id -> new DriverEligibilityClientResponse(id, true, "ACTIVE", "APPROVED", true, List.of()))
                    .toList();
        });

        when(identityClient.getDriverVehicles(any(UUID.class))).thenAnswer(invocation -> {
            UUID id = invocation.getArgument(0);
            return List.of(new com.rhl.location.dtos.VehicleClientResponse(
                    UUID.randomUUID(), id, "MOTORBIKE", "29A-12345", "Honda", "Wave", "Red", "ACTIVE"
            ));
        });
    }

    @AfterEach
    void tearDown() {
        var keys = redis.keys("rhl:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    @DisplayName("NFR-PERF-001 & NFR-PERF-002: Realtime GPS Telemetry Load Benchmark (100 Drivers Concurrent)")
    void benchmarkGpsTelemetryLoad_100DriversConcurrent() throws Exception {
        // Step 1: Create 100 distinct driver UUID fixtures
        List<UUID> driverIds = new ArrayList<>(SIMULATED_DRIVERS);
        for (int i = 0; i < SIMULATED_DRIVERS; i++) {
            driverIds.add(UUID.randomUUID());
        }

        // Base coordinates in Hanoi (around Hoan Kiem lake)
        double baseLon = 105.8342;
        double baseLat = 21.0278;

        // Step 2: Put all 100 drivers ONLINE
        Map<UUID, UUID> driverSessions = new HashMap<>(SIMULATED_DRIVERS);
        for (int i = 0; i < SIMULATED_DRIVERS; i++) {
            UUID driverId = driverIds.get(i);
            double lon = baseLon + (i * 0.0005);
            double lat = baseLat + (i * 0.0005);
            DriverLocationResponse onlineRes = locationService.online(driverId, new LocationUpdateRequest(lon, lat, Instant.now(), 10.0, 1L, null, null));
            driverSessions.put(driverId, onlineRes.sessionId());
        }

        // Step 3: Concurrently simulate 100 drivers sending successive GPS telemetry
        int threadPoolSize = 30;
        ExecutorService executor = Executors.newFixedThreadPool(threadPoolSize);
        List<Long> latenciesNanos = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        long benchmarkStartTime = System.currentTimeMillis();

        CountDownLatch completionLatch = new CountDownLatch(SIMULATED_DRIVERS * UPDATES_PER_DRIVER);

        for (int round = 2; round <= UPDATES_PER_DRIVER + 1; round++) {
            final long seq = round;
            for (int i = 0; i < SIMULATED_DRIVERS; i++) {
                final UUID driverId = driverIds.get(i);
                final double lon = baseLon + (i * 0.0005) + (round * 0.0001);
                final double lat = baseLat + (i * 0.0005) + (round * 0.0001);

                executor.submit(() -> {
                    long start = System.nanoTime();
                    try {
                        LocationUpdateRequest req = new LocationUpdateRequest(
                                lon, lat, Instant.now(), 8.0, seq, 45.0, 8.5, driverSessions.get(driverId), null
                        );
                        DriverLocationResponse resp = locationService.update(driverId, req);
                        if ("ONLINE".equals(resp.status())) {
                            successCount.incrementAndGet();
                        } else {
                            failureCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        failureCount.incrementAndGet();
                    } finally {
                        long end = System.nanoTime();
                        latenciesNanos.add(end - start);
                        completionLatch.countDown();
                    }
                });
            }
        }

        boolean finishedInTime = completionLatch.await(30, TimeUnit.SECONDS);
        long benchmarkEndTime = System.currentTimeMillis();
        executor.shutdown();

        long totalDurationMs = benchmarkEndTime - benchmarkStartTime;
        int totalRequests = successCount.get() + failureCount.get();
        double throughputRps = (totalRequests * 1000.0) / totalDurationMs;

        // Calculate latency stats in milliseconds
        List<Double> latenciesMs = latenciesNanos.stream()
                .map(n -> n / 1_000_000.0)
                .sorted()
                .toList();

        double avgLatencyMs = latenciesMs.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double p50 = latenciesMs.get((int) (latenciesMs.size() * 0.50));
        double p95 = latenciesMs.get((int) (latenciesMs.size() * 0.95));
        double p99 = latenciesMs.get((int) (latenciesMs.size() * 0.99));
        double maxLatency = latenciesMs.get(latenciesMs.size() - 1);
        double minLatency = latenciesMs.get(0);
        double errorRate = (failureCount.get() * 100.0) / totalRequests;

        System.out.println("===============================================================");
        System.out.println("=== M03 GPS INGESTION LOAD BENCHMARK (NFR-PERF-001 & 002) ===");
        System.out.println("===============================================================");
        System.out.println("Simulated Drivers     : " + SIMULATED_DRIVERS);
        System.out.println("Total Requests Sent   : " + totalRequests);
        System.out.println("Successful Requests   : " + successCount.get());
        System.out.println("Failed Requests       : " + failureCount.get());
        System.out.println("Error Rate            : " + String.format("%.2f", errorRate) + "%");
        System.out.println("Total Duration (ms)   : " + totalDurationMs + " ms");
        System.out.println("Measured Throughput   : " + String.format("%.2f", throughputRps) + " req/sec (SRS target: ~33.3 req/sec)");
        System.out.println("Min Latency           : " + String.format("%.2f", minLatency) + " ms");
        System.out.println("Average Latency       : " + String.format("%.2f", avgLatencyMs) + " ms");
        System.out.println("p50 (Median) Latency  : " + String.format("%.2f", p50) + " ms");
        System.out.println("p95 Latency           : " + String.format("%.2f", p95) + " ms (SRS NFR target: <= 500 ms)");
        System.out.println("p99 Latency           : " + String.format("%.2f", p99) + " ms");
        System.out.println("Max Latency           : " + String.format("%.2f", maxLatency) + " ms");
        System.out.println("===============================================================");

        assertThat(finishedInTime).isTrue();
        assertThat(failureCount.get()).isEqualTo(0);
        assertThat(totalRequests).isEqualTo(SIMULATED_DRIVERS * UPDATES_PER_DRIVER);
        // NFR-PERF-001 Verification: 95% of valid updates <= 500 ms
        assertThat(p95).isLessThan(500.0);
    }

    @Test
    @DisplayName("NFR-PERF-003: Nearby Search Matching Latency Benchmark (100 Concurrent Queries)")
    void benchmarkNearbySearch_100QueriesConcurrent() throws Exception {
        // Setup 50 online drivers distributed across 5km radius
        List<UUID> driverIds = new ArrayList<>(50);
        for (int i = 0; i < 50; i++) {
            UUID id = UUID.randomUUID();
            driverIds.add(id);
            double lon = 105.8342 + (i * 0.001);
            double lat = 21.0278 + (i * 0.001);
            locationService.online(id, new LocationUpdateRequest(lon, lat, Instant.now(), 10.0, 1L, null, null));
        }

        int queryCount = 100;
        int threadPoolSize = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadPoolSize);

        // JVM & Connection Pool Warmup (10 queries to compile JIT hotspots)
        for (int w = 0; w < 10; w++) {
            locationService.nearby(105.8342, 21.0278, 5.0, 10);
        }

        List<Long> latenciesNanos = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(queryCount);

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < queryCount; i++) {
            final double searchLon = 105.8342 + ((i % 10) * 0.0005);
            final double searchLat = 21.0278 + ((i % 10) * 0.0005);
            executor.submit(() -> {
                long start = System.nanoTime();
                try {
                    List<NearbyDriverResponse> res = locationService.nearby(searchLon, searchLat, 5.0, 10);
                    assertThat(res).isNotEmpty();
                } finally {
                    long end = System.nanoTime();
                    latenciesNanos.add(end - start);
                    latch.countDown();
                }
            });
        }

        boolean finished = latch.await(20, TimeUnit.SECONDS);
        long endTime = System.currentTimeMillis();
        executor.shutdown();

        long totalDurationMs = endTime - startTime;
        double throughputRps = (queryCount * 1000.0) / totalDurationMs;

        List<Double> latenciesMs = latenciesNanos.stream()
                .map(n -> n / 1_000_000.0)
                .sorted()
                .toList();

        double avgLatencyMs = latenciesMs.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double p50 = latenciesMs.get((int) (latenciesMs.size() * 0.50));
        double p95 = latenciesMs.get((int) (latenciesMs.size() * 0.95));
        double p99 = latenciesMs.get((int) (latenciesMs.size() * 0.99));

        System.out.println("===============================================================");
        System.out.println("=== M03 NEARBY SEARCH BENCHMARK (NFR-PERF-003)              ===");
        System.out.println("===============================================================");
        System.out.println("Total Queries Sent    : " + queryCount);
        System.out.println("Total Duration (ms)   : " + totalDurationMs + " ms");
        System.out.println("Throughput (QPS)      : " + String.format("%.2f", throughputRps) + " queries/sec");
        System.out.println("Average Latency       : " + String.format("%.2f", avgLatencyMs) + " ms");
        System.out.println("p50 (Median) Latency  : " + String.format("%.2f", p50) + " ms");
        System.out.println("p95 Latency           : " + String.format("%.2f", p95) + " ms (SRS NFR target: <= 200 ms)");
        System.out.println("p99 Latency           : " + String.format("%.2f", p99) + " ms");
        System.out.println("===============================================================");

        assertThat(finished).isTrue();
        // NFR-PERF-003 Verification: 95% of requests complete <= 200 ms
        assertThat(p95).isLessThan(200.0);
    }
}
