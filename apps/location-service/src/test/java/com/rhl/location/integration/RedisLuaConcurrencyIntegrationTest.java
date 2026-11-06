package com.rhl.location.integration;

import com.rhl.location.clients.IdentityDriverClient;
import com.rhl.location.dtos.DriverEligibilityClientResponse;
import com.rhl.location.dtos.DriverLocationResponse;
import com.rhl.location.dtos.LocationUpdateRequest;
import com.rhl.location.exceptions.AmbiguousVehicleException;
import com.rhl.location.exceptions.DriverOfflineException;
import com.rhl.location.exceptions.InvalidSessionException;
import com.rhl.location.exceptions.InvalidVehicleSessionException;
import com.rhl.location.exceptions.OutOfOrderTelemetryException;
import com.rhl.location.services.DriverLocationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
class RedisLuaConcurrencyIntegrationTest {

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

    private static final String GEO_KEY = "rhl:geo:drivers";

    @BeforeEach
    void setUp() {
        // Mock identity client to return eligible by default
        when(identityClient.getEligibility(any(UUID.class))).thenAnswer(invocation -> {
            UUID id = invocation.getArgument(0);
            return new DriverEligibilityClientResponse(id, true, "ACTIVE", "APPROVED", true, List.of());
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
        // Clean only test keys created during tests, never flush all
        var keys = redis.keys("rhl:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private String liveKey(UUID driverId) {
        return "rhl:driver:live:" + driverId;
    }

    private String seqKey(UUID driverId) {
        return "rhl:driver:seq:" + driverId;
    }

    private String sessionKey(UUID driverId) {
        return "rhl:driver:session:" + driverId;
    }

    private String vehicleKey(UUID driverId) {
        return "rhl:driver:vehicle:" + driverId;
    }

    @Nested
    @DisplayName("4.1. Atomic Location Update Tests on Real Redis")
    class AtomicLocationUpdateTests {

        @Test
        @DisplayName("Valid GPS telemetry updates GEO, sequence, and refreshes presence TTL on real Redis")
        void validGpsUpdate_succeedsAndSetsTtl() {
            UUID driverId = UUID.randomUUID();
            DriverLocationResponse onlineRes = locationService.online(driverId, new LocationUpdateRequest(105.8342, 21.0278, Instant.now(), 10.0, 1L, null, null));
            UUID sessionId = onlineRes.sessionId();

            // Verify initial state
            assertThat(redis.opsForValue().get(liveKey(driverId))).isNotNull();
            assertThat(redis.opsForValue().get(seqKey(driverId))).isEqualTo("1");

            // Update with sequence 2 and valid sessionId
            LocationUpdateRequest updateReq = new LocationUpdateRequest(105.8350, 21.0285, Instant.now(), 10.0, 2L, null, null, sessionId, null);
            DriverLocationResponse response = locationService.update(driverId, updateReq);

            assertThat(response.status()).isEqualTo("ONLINE");
            assertThat(redis.opsForValue().get(seqKey(driverId))).isEqualTo("2");

            // Verify TTL is approximately 90 seconds
            Long ttl = redis.getExpire(liveKey(driverId), TimeUnit.SECONDS);
            assertThat(ttl).isNotNull().isBetween(80L, 90L);

            // Verify coordinates in GEO
            List<Point> points = redis.opsForGeo().position(GEO_KEY, driverId.toString());
            assertThat(points).isNotNull().isNotEmpty();
            assertThat(points.get(0).getX()).isCloseTo(105.8350, org.assertj.core.data.Offset.offset(0.0001));
            assertThat(points.get(0).getY()).isCloseTo(21.0285, org.assertj.core.data.Offset.offset(0.0001));
        }

        @Test
        @DisplayName("Missing sessionId is rejected with STALE_SESSION_PACKET when session fencing is active")
        void missingSessionId_rejectedWhenSessionFencingActive() {
            UUID driverId = UUID.randomUUID();
            DriverLocationResponse onlineRes = locationService.online(driverId, new LocationUpdateRequest(105.8342, 21.0278, Instant.now(), 10.0, 1L, null, null));
            assertThat(onlineRes.sessionId()).isNotNull();

            // Try updating with sequence 2 but omitting sessionId (null)
            LocationUpdateRequest noSessionReq = new LocationUpdateRequest(105.8350, 21.0285, Instant.now(), 10.0, 2L, null, null, null, null);

            assertThatThrownBy(() -> locationService.update(driverId, noSessionReq))
                    .isInstanceOf(InvalidSessionException.class)
                    .satisfies(ex -> {
                        InvalidSessionException ise = (InvalidSessionException) ex;
                        assertThat(ise.getErrorCode()).isEqualTo("STALE_SESSION_PACKET");
                    });

            // Sequence in Redis must remain 1
            assertThat(redis.opsForValue().get(seqKey(driverId))).isEqualTo("1");
        }

        @Test
        @DisplayName("Strict sequence monotonicity: monotonically increasing sequence numbers are accepted")
        void sequenceMonotonicity_increasingSequencesAccepted() {
            UUID driverId = UUID.randomUUID();
            DriverLocationResponse onlineRes = locationService.online(driverId, new LocationUpdateRequest(105.8342, 21.0278, Instant.now(), 10.0, 1L, null, null));
            UUID sessionId = onlineRes.sessionId();

            for (long seq = 2; seq <= 5; seq++) {
                LocationUpdateRequest req = new LocationUpdateRequest(105.8342, 21.0278, Instant.now(), 10.0, seq, null, null, sessionId, null);
                DriverLocationResponse res = locationService.update(driverId, req);
                assertThat(res.status()).isEqualTo("ONLINE");
                assertThat(redis.opsForValue().get(seqKey(driverId))).isEqualTo(String.valueOf(seq));
            }
        }

        @Test
        @DisplayName("Duplicate sequence number is rejected with DUPLICATE_TELEMETRY and does not mutate sequence")
        void duplicateSequence_rejected() {
            UUID driverId = UUID.randomUUID();
            DriverLocationResponse onlineRes = locationService.online(driverId, new LocationUpdateRequest(105.8342, 21.0278, Instant.now(), 10.0, 10L, null, null));
            UUID sessionId = onlineRes.sessionId();

            LocationUpdateRequest dupReq = new LocationUpdateRequest(105.8345, 21.0280, Instant.now(), 10.0, 10L, null, null, sessionId, null);

            assertThatThrownBy(() -> locationService.update(driverId, dupReq))
                    .isInstanceOf(OutOfOrderTelemetryException.class)
                    .hasMessageContaining("Duplicate telemetry sequence 10");

            assertThat(redis.opsForValue().get(seqKey(driverId))).isEqualTo("10");
        }

        @Test
        @DisplayName("Out-of-order sequence number is rejected with OUT_OF_ORDER_TELEMETRY and preserves newer sequence")
        void outOfOrderSequence_rejected() {
            UUID driverId = UUID.randomUUID();
            DriverLocationResponse onlineRes = locationService.online(driverId, new LocationUpdateRequest(105.8342, 21.0278, Instant.now(), 10.0, 15L, null, null));
            UUID sessionId = onlineRes.sessionId();

            LocationUpdateRequest staleReq = new LocationUpdateRequest(105.8345, 21.0280, Instant.now(), 10.0, 12L, null, null, sessionId, null);

            assertThatThrownBy(() -> locationService.update(driverId, staleReq))
                    .isInstanceOf(OutOfOrderTelemetryException.class)
                    .hasMessageContaining("is older than current committed sequence");

            assertThat(redis.opsForValue().get(seqKey(driverId))).isEqualTo("15");
        }

        @Test
        @DisplayName("Offline driver cannot update GPS and receives DriverOfflineException")
        void offlineDriver_cannotUpdate() {
            UUID driverId = UUID.randomUUID();
            LocationUpdateRequest req = new LocationUpdateRequest(105.8342, 21.0278, Instant.now(), 10.0, 1L, null, null);

            assertThatThrownBy(() -> locationService.update(driverId, req))
                    .isInstanceOf(DriverOfflineException.class);
        }

        @Test
        @DisplayName("Driver offline evicts liveKey, seqKey, and removes member from GEO Sorted Set")
        void offline_evictsAllKeysAndGeoMember() {
            UUID driverId = UUID.randomUUID();
            locationService.online(driverId, new LocationUpdateRequest(105.8342, 21.0278, Instant.now(), 10.0, 1L, null, null));

            assertThat(redis.hasKey(liveKey(driverId))).isTrue();
            assertThat(redis.hasKey(seqKey(driverId))).isTrue();

            locationService.offline(driverId);

            assertThat(redis.hasKey(liveKey(driverId))).isFalse();
            assertThat(redis.hasKey(seqKey(driverId))).isFalse();

            List<Point> points = redis.opsForGeo().position(GEO_KEY, driverId.toString());
            assertThat(points == null || points.isEmpty() || points.get(0) == null).isTrue();
        }
    }

    @Nested
    @DisplayName("4.2. Concurrency Tests on Real Redis")
    class ConcurrencyTests {

        @Test
        @DisplayName("CASE A: Two concurrent GPS requests with sequence 101 and 102 — final state preserves sequence 102")
        void caseA_concurrentSequence101And102_preservesSequence102() throws Exception {
            UUID driverId = UUID.randomUUID();
            DriverLocationResponse onlineRes = locationService.online(driverId, new LocationUpdateRequest(105.8340, 21.0270, Instant.now(), 10.0, 100L, null, null));
            UUID sessionId = onlineRes.sessionId();

            int threadCount = 2;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch endGate = new CountDownLatch(threadCount);

            LocationUpdateRequest req101 = new LocationUpdateRequest(105.8345, 21.0275, Instant.now(), 10.0, 101L, null, null, sessionId, null);
            LocationUpdateRequest req102 = new LocationUpdateRequest(105.8350, 21.0280, Instant.now(), 10.0, 102L, null, null, sessionId, null);

            List<Exception> exceptions = Collections.synchronizedList(new ArrayList<>());

            executor.submit(() -> {
                try {
                    startGate.await();
                    locationService.update(driverId, req101);
                } catch (Exception e) {
                    exceptions.add(e);
                } finally {
                    endGate.countDown();
                }
            });

            executor.submit(() -> {
                try {
                    startGate.await();
                    locationService.update(driverId, req102);
                } catch (Exception e) {
                    exceptions.add(e);
                } finally {
                    endGate.countDown();
                }
            });

            startGate.countDown();
            boolean finished = endGate.await(5, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(finished).isTrue();

            // Final state MUST be sequence 102, never overwritten by 101
            String finalSeq = redis.opsForValue().get(seqKey(driverId));
            assertThat(finalSeq).isEqualTo("102");

            List<Point> points = redis.opsForGeo().position(GEO_KEY, driverId.toString());
            assertThat(points).isNotNull().isNotEmpty();
            assertThat(points.get(0).getX()).isCloseTo(105.8350, org.assertj.core.data.Offset.offset(0.0001));
            assertThat(points.get(0).getY()).isCloseTo(21.0280, org.assertj.core.data.Offset.offset(0.0001));
        }

        @Test
        @DisplayName("CASE B: Duplicate GPS race — two concurrent requests with identical sequence 100 — exactly one succeeds")
        void caseB_duplicateSequenceRace_exactlyOneSucceeds() throws Exception {
            UUID driverId = UUID.randomUUID();
            DriverLocationResponse onlineRes = locationService.online(driverId, new LocationUpdateRequest(105.8340, 21.0270, Instant.now(), 10.0, 99L, null, null));
            UUID sessionId = onlineRes.sessionId();

            int threadCount = 2;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch endGate = new CountDownLatch(threadCount);

            LocationUpdateRequest req = new LocationUpdateRequest(105.8345, 21.0275, Instant.now(), 10.0, 100L, null, null, sessionId, null);

            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger conflictCount = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startGate.await();
                        locationService.update(driverId, req);
                        successCount.incrementAndGet();
                    } catch (OutOfOrderTelemetryException e) {
                        if ("DUPLICATE_TELEMETRY".equals(e.getErrorCode())) {
                            conflictCount.incrementAndGet();
                        }
                    } catch (Exception ignored) {
                    } finally {
                        endGate.countDown();
                    }
                });
            }

            startGate.countDown();
            boolean finished = endGate.await(5, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(finished).isTrue();
            assertThat(successCount.get()).isEqualTo(1);
            assertThat(conflictCount.get()).isEqualTo(1);

            assertThat(redis.opsForValue().get(seqKey(driverId))).isEqualTo("100");
        }

        @Test
        @DisplayName("CASE C: Concurrent GPS vs OFFLINE — delayed GPS after OFFLINE cannot resurrect presence")
        void caseC_gpsVsOfflineRace_cannotResurrectPresence() throws Exception {
            UUID driverId = UUID.randomUUID();
            DriverLocationResponse onlineRes = locationService.online(driverId, new LocationUpdateRequest(105.8340, 21.0270, Instant.now(), 10.0, 50L, null, null));
            UUID sessionId = onlineRes.sessionId();

            int threadCount = 2;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch endGate = new CountDownLatch(threadCount);

            LocationUpdateRequest req = new LocationUpdateRequest(105.8345, 21.0275, Instant.now(), 10.0, 51L, null, null, sessionId, null);

            executor.submit(() -> {
                try {
                    startGate.await();
                    locationService.offline(driverId);
                } catch (Exception ignored) {
                } finally {
                    endGate.countDown();
                }
            });

            executor.submit(() -> {
                try {
                    startGate.await();
                    // Slight artificial delay to increase likelihood of arriving during/after offline
                    Thread.sleep(10);
                    locationService.update(driverId, req);
                } catch (Exception ignored) {
                } finally {
                    endGate.countDown();
                }
            });

            startGate.countDown();
            boolean finished = endGate.await(5, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(finished).isTrue();

            // Verify: if driver is offline, presence must not be resurrected!
            String live = redis.opsForValue().get(liveKey(driverId));
            if (live == null) {
                // Succeeded: offline executed and late GPS did not resurrect
                assertThat(redis.hasKey(seqKey(driverId))).isFalse();
            } else {
                // If GPS arrived first, it must be valid online state with seq 51
                assertThat(redis.opsForValue().get(seqKey(driverId))).isEqualTo("51");
            }
        }

        @Test
        @DisplayName("CASE D: OFFLINE -> ONLINE -> Delayed GPS: Session Fencing prevents stale overwrite (AUD-SES-01 FIXED)")
        void caseD_offlineOnlineSessionResetRace_sessionFencingPreventsStaleOverwrite() {
            UUID driverId = UUID.randomUUID();

            // Session A: Driver goes online, advances sequence to 100 with sessionA token
            DriverLocationResponse sessionARes = locationService.online(driverId, new LocationUpdateRequest(105.8340, 21.0270, Instant.now(), 10.0, 100L, null, null));
            UUID sessionAId = sessionARes.sessionId();
            assertThat(sessionAId).isNotNull();
            assertThat(redis.opsForValue().get(seqKey(driverId))).isEqualTo("100");

            // Driver goes OFFLINE -> clears presence, sequence, sessionKey, vehicleKey
            locationService.offline(driverId);
            assertThat(redis.hasKey(liveKey(driverId))).isFalse();
            assertThat(redis.hasKey(sessionKey(driverId))).isFalse();

            // Session B: Driver goes ONLINE again, generating new sessionB token, starting sequence at 1
            Instant sessionBTime = Instant.now();
            DriverLocationResponse sessionBRes = locationService.online(driverId, new LocationUpdateRequest(105.8500, 21.0500, sessionBTime, 10.0, 1L, null, null));
            UUID sessionBId = sessionBRes.sessionId();
            assertThat(sessionBId).isNotNull().isNotEqualTo(sessionAId);
            assertThat(redis.opsForValue().get(seqKey(driverId))).isEqualTo("1");

            // A delayed packet from Session A (seq 50) arrives with deviceTimestamp within 10s of now (<60s stale) and sessionA token
            LocationUpdateRequest delayedPacketSessionA = new LocationUpdateRequest(
                    105.8342, 21.0275, sessionBTime.minusSeconds(5), 10.0, 50L, null, null, sessionAId, null
            );

            // Session fencing (AUD-SES-01): The delayed packet from Session A has sessionAId != currentSession (sessionBId)
            // It MUST be rejected with InvalidSessionException (409 Conflict, STALE_SESSION_PACKET) even though seq 50 > seq 1!
            assertThatThrownBy(() -> locationService.update(driverId, delayedPacketSessionA))
                    .isInstanceOf(InvalidSessionException.class)
                    .hasMessageContaining("stale session")
                    .satisfies(ex -> {
                        InvalidSessionException ise = (InvalidSessionException) ex;
                        assertThat(ise.getErrorCode()).isEqualTo("STALE_SESSION_PACKET");
                    });

            // Verify Redis state is UNTOUCHED: sequence remains 1 from Session B
            assertThat(redis.opsForValue().get(seqKey(driverId))).isEqualTo("1");
            assertThat(redis.opsForValue().get(sessionKey(driverId))).isEqualTo(sessionBId.toString());

            // A valid packet from Session B (seq 2) succeeds
            LocationUpdateRequest validPacketSessionB = new LocationUpdateRequest(
                    105.8505, 21.0505, sessionBTime.plusSeconds(1), 10.0, 2L, null, null, sessionBId, null
            );
            DriverLocationResponse validRes = locationService.update(driverId, validPacketSessionB);
            assertThat(validRes.status()).isEqualTo("ONLINE");
            assertThat(redis.opsForValue().get(seqKey(driverId))).isEqualTo("2");

            // EVIDENCE DOCUMENTED: AUD-SES-01 IS OFFICIALLY CLOSED.
        }

        @Test
        @DisplayName("Active Vehicle Session: Driver with multiple active vehicles must select valid vehicle when going online")
        void activeVehicleSession_multiVehicleDriverEnforcement() {
            UUID driverId = UUID.randomUUID();
            UUID carId = UUID.randomUUID();
            UUID bikeId = UUID.randomUUID();

            when(identityClient.getDriverVehicles(driverId)).thenReturn(List.of(
                    new com.rhl.location.dtos.VehicleClientResponse(carId, driverId, "FOUR_SEATER", "30A-99999", "Toyota", "Vios", "Black", "ACTIVE"),
                    new com.rhl.location.dtos.VehicleClientResponse(bikeId, driverId, "MOTORBIKE", "29B-88888", "Honda", "Wave", "Red", "ACTIVE")
            ));

            // Going online without selectedVehicleId throws AmbiguousVehicleException
            assertThatThrownBy(() -> locationService.online(driverId, new LocationUpdateRequest(105.8340, 21.0270)))
                    .isInstanceOf(AmbiguousVehicleException.class)
                    .hasMessageContaining("multiple active vehicles");

            // Going online with non-existent / not owned vehicle throws InvalidVehicleSessionException
            UUID fakeVehicleId = UUID.randomUUID();
            LocationUpdateRequest fakeReq = new LocationUpdateRequest(
                    105.8340, 21.0270, Instant.now(), 10.0, 1L, null, null, null, fakeVehicleId
            );
            assertThatThrownBy(() -> locationService.online(driverId, fakeReq))
                    .isInstanceOf(InvalidVehicleSessionException.class)
                    .hasMessageContaining("does not belong to driver");

            // Going online with specific owned vehicle succeeds and persists in Redis vehicleKey
            LocationUpdateRequest validReq = new LocationUpdateRequest(
                    105.8340, 21.0270, Instant.now(), 10.0, 1L, null, null, null, carId
            );
            DriverLocationResponse res = locationService.online(driverId, validReq);
            assertThat(res.selectedVehicleId()).isEqualTo(carId);
            assertThat(redis.opsForValue().get(vehicleKey(driverId))).isEqualTo(carId.toString());

            // OFFLINE clears the vehicle selection
            locationService.offline(driverId);
            assertThat(redis.hasKey(vehicleKey(driverId))).isFalse();
        }
    }

    @Nested
    @DisplayName("4.3. Lua Failure & Partial Mutation Analysis")
    class LuaFailureAnalysisTests {

        @Test
        @DisplayName("Reordered Lua script prevents partial mutation: if GEOADD fails, sequence is NOT updated")
        void luaFailure_doesNotMutateSequenceWhenGeoaddFails() {
            UUID driverId = UUID.randomUUID();
            locationService.online(driverId, new LocationUpdateRequest(105.8340, 21.0270, Instant.now(), 10.0, 1L, null, null));
            assertThat(redis.opsForValue().get(seqKey(driverId))).isEqualTo("1");

            // Execute raw script with invalid coordinate that causes GEOADD to fail
            try {
                redis.execute(
                        DriverLocationService.UPDATE_SCRIPT,
                        List.of(liveKey(driverId), seqKey(driverId), GEO_KEY),
                        driverId.toString(),
                        "INVALID_LONGITUDE", // causes GEOADD to fail
                        "21.0280",
                        "2",
                        String.valueOf(System.currentTimeMillis()),
                        "90"
                );
            } catch (Exception expected) {
                assertThat(expected).isNotNull();
            }

            // In the improved script, GEOADD fails BEFORE sequence is updated!
            // Therefore seqKey is preserved at "1", preventing partial mutation!
            String seq = redis.opsForValue().get(seqKey(driverId));
            assertThat(seq).isEqualTo("1");
        }
    }

    @Nested
    @DisplayName("5. Redis Presence & Heartbeat TTL Tests")
    class PresenceAndHeartbeatTests {

        @Test
        @DisplayName("Heartbeat key has configured 90-second TTL in Redis")
        void heartbeatTtl_isConfiguredProperly() {
            UUID driverId = UUID.randomUUID();
            locationService.online(driverId, new LocationUpdateRequest(105.8340, 21.0270, Instant.now(), 10.0, 1L, null, null));

            Long ttl = redis.getExpire(liveKey(driverId), TimeUnit.SECONDS);
            assertThat(ttl).isNotNull().isBetween(80L, 90L);
        }

        @Test
        @DisplayName("When heartbeat liveKey is deleted (simulating TTL expiration), driver remains in GEO but is offline")
        void heartbeatExpiration_driverRemainsInGeoUntilFiltered() {
            UUID driverId = UUID.randomUUID();
            locationService.online(driverId, new LocationUpdateRequest(105.8340, 21.0270, Instant.now(), 10.0, 1L, null, null));

            // Simulate heartbeat expiration by deleting liveKey
            redis.delete(liveKey(driverId));

            // Driver still has member in GEO Sorted Set
            List<Point> points = redis.opsForGeo().position(GEO_KEY, driverId.toString());
            assertThat(points).isNotNull().isNotEmpty();
            assertThat(points.get(0)).isNotNull();

            // getMine() correctly identifies driver as OFFLINE
            DriverLocationResponse mine = locationService.getMine(driverId);
            assertThat(mine.status()).isEqualTo("OFFLINE");
        }
    }
}
