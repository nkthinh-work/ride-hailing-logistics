package com.rhl.location.services;

import com.rhl.location.clients.IdentityDriverClient;
import com.rhl.location.dtos.DriverEligibilityClientResponse;
import com.rhl.location.dtos.DriverLocationResponse;
import com.rhl.location.dtos.LocationUpdateRequest;
import com.rhl.location.dtos.NearbyDriverResponse;
import com.rhl.location.exceptions.DriverIneligibleException;
import com.rhl.location.exceptions.DriverOfflineException;
import com.rhl.location.exceptions.IdentityServiceUnavailableException;
import com.rhl.location.exceptions.OutOfOrderTelemetryException;
import com.rhl.location.exceptions.TelemetryValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DriverLocationServiceTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private GeoOperations<String, String> geoOps;

    @Mock
    private ValueOperations<String, String> valueOps;

    @Mock
    private IdentityDriverClient identityClient;

    private DriverLocationService locationService;

    private final UUID driverId = UUID.randomUUID();
    private final LocationUpdateRequest validRequest = new LocationUpdateRequest(105.8342, 21.0278);

    @BeforeEach
    void setUp() {
        locationService = new DriverLocationService(redis, identityClient);
    }

    private void mockRedisOps() {
        lenient().when(redis.opsForGeo()).thenReturn(geoOps);
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        lenient().when(identityClient.getDriverVehicles(any())).thenReturn(List.of(
                new com.rhl.location.dtos.VehicleClientResponse(
                        UUID.randomUUID(), driverId, "MOTORBIKE", "29A-12345", "Honda", "Wave", "Red", "ACTIVE"
                )
        ));
        lenient().when(redis.execute(
                eq(DriverLocationService.UPDATE_SCRIPT),
                anyList(),
                any(), any(), any(), any(), any(), any(), any()
        )).thenReturn(1L);
        lenient().when(redis.execute(
                eq(DriverLocationService.ONLINE_SCRIPT),
                anyList(),
                any(), any(), any(), any(), any(), any(), any(), any()
        )).thenReturn(1L);
        lenient().when(redis.execute(
                eq(DriverLocationService.OFFLINE_SCRIPT),
                anyList(),
                any()
        )).thenReturn(1L);
    }

    @Nested
    @DisplayName("online() Tests - Driver Eligibility Enforcement")
    class OnlineTests {

        @Test
        @DisplayName("Eligible driver successfully turns ONLINE and is registered in Redis via atomic script")
        void online_whenDriverEligible_succeeds() {
            mockRedisOps();

            DriverEligibilityClientResponse eligibility = new DriverEligibilityClientResponse(
                    driverId, true, "ACTIVE", "APPROVED", true, List.of()
            );
            when(identityClient.getEligibility(driverId)).thenReturn(eligibility);

            DriverLocationResponse response = locationService.online(driverId, validRequest);

            assertThat(response.driverId()).isEqualTo(driverId);
            assertThat(response.status()).isEqualTo("ONLINE");
            assertThat(response.longitude()).isEqualTo(105.8342);
            assertThat(response.latitude()).isEqualTo(21.0278);

            verify(identityClient, times(1)).getEligibility(driverId);
            verify(redis, times(1)).execute(
                    eq(DriverLocationService.ONLINE_SCRIPT),
                    eq(List.of(
                            "rhl:driver:live:" + driverId,
                            "rhl:driver:seq:" + driverId,
                            "rhl:geo:drivers",
                            "rhl:driver:session:" + driverId,
                            "rhl:driver:vehicle:" + driverId
                    )),
                    eq(driverId.toString()),
                    eq("105.8342"),
                    eq("21.0278"),
                    eq(""),
                    anyString(),
                    eq("90"),
                    anyString(),
                    anyString()
            );
        }

        @Test
        @DisplayName("Suspended driver is rejected with DriverIneligibleException and not saved to Redis")
        void online_whenDriverSuspended_throwsDriverIneligibleException() {
            DriverEligibilityClientResponse eligibility = new DriverEligibilityClientResponse(
                    driverId, false, "ACTIVE", "SUSPENDED", true, List.of("DRIVER_SUSPENDED")
            );
            when(identityClient.getEligibility(driverId)).thenReturn(eligibility);

            assertThatThrownBy(() -> locationService.online(driverId, validRequest))
                    .isInstanceOf(DriverIneligibleException.class)
                    .hasMessageContaining("Driver is not eligible to go online")
                    .satisfies(ex -> {
                        DriverIneligibleException die = (DriverIneligibleException) ex;
                        assertThat(die.getReasons()).contains("DRIVER_SUSPENDED");
                    });

            verifyNoInteractions(redis);
        }

        @Test
        @DisplayName("Driver with unapproved profile is rejected with DriverIneligibleException")
        void online_whenProfilePending_throwsDriverIneligibleException() {
            DriverEligibilityClientResponse eligibility = new DriverEligibilityClientResponse(
                    driverId, false, "ACTIVE", "PENDING", true, List.of("APPLICATION_NOT_APPROVED")
            );
            when(identityClient.getEligibility(driverId)).thenReturn(eligibility);

            assertThatThrownBy(() -> locationService.online(driverId, validRequest))
                    .isInstanceOf(DriverIneligibleException.class)
                    .satisfies(ex -> {
                        DriverIneligibleException die = (DriverIneligibleException) ex;
                        assertThat(die.getReasons()).contains("APPLICATION_NOT_APPROVED");
                    });

            verifyNoInteractions(redis);
        }

        @Test
        @DisplayName("Driver with no active vehicle is rejected with DriverIneligibleException")
        void online_whenNoActiveVehicle_throwsDriverIneligibleException() {
            DriverEligibilityClientResponse eligibility = new DriverEligibilityClientResponse(
                    driverId, false, "ACTIVE", "APPROVED", true, List.of("NO_ACTIVE_VEHICLE")
            );
            when(identityClient.getEligibility(driverId)).thenReturn(eligibility);

            assertThatThrownBy(() -> locationService.online(driverId, validRequest))
                    .isInstanceOf(DriverIneligibleException.class)
                    .satisfies(ex -> {
                        DriverIneligibleException die = (DriverIneligibleException) ex;
                        assertThat(die.getReasons()).contains("NO_ACTIVE_VEHICLE");
                    });

            verifyNoInteractions(redis);
        }

        @Test
        @DisplayName("Identity service unavailable results in fail-closed (throws IdentityServiceUnavailableException)")
        void online_whenIdentityServiceUnavailable_failsClosed() {
            when(identityClient.getEligibility(driverId))
                    .thenThrow(new IdentityServiceUnavailableException("Identity service unreachable"));

            assertThatThrownBy(() -> locationService.online(driverId, validRequest))
                    .isInstanceOf(IdentityServiceUnavailableException.class);

            verifyNoInteractions(redis);
        }
    }

    @Nested
    @DisplayName("update() Tests - Runtime Suspension Enforcement & Caching")
    class UpdateTests {

        @Test
        @DisplayName("Driver offline in Redis throws DriverOfflineException")
        void update_whenDriverOfflineInRedis_throwsDriverOfflineException() {
            mockRedisOps();
            when(valueOps.get("rhl:driver:live:" + driverId)).thenReturn(null);

            assertThatThrownBy(() -> locationService.update(driverId, validRequest))
                    .isInstanceOf(DriverOfflineException.class);

            verifyNoInteractions(identityClient);
        }

        @Test
        @DisplayName("Driver suspended while online is evicted from Redis and rejected with DriverIneligibleException")
        void update_whenDriverBecomesSuspended_evictsRedisAndThrowsIneligible() {
            mockRedisOps();
            when(valueOps.get("rhl:driver:live:" + driverId)).thenReturn(String.valueOf(System.currentTimeMillis()));

            // Identity returns ineligible (suspended by admin)
            DriverEligibilityClientResponse suspended = new DriverEligibilityClientResponse(
                    driverId, false, "ACTIVE", "SUSPENDED", true, List.of("DRIVER_SUSPENDED")
            );
            when(identityClient.getEligibility(driverId)).thenReturn(suspended);

            assertThatThrownBy(() -> locationService.update(driverId, validRequest))
                    .isInstanceOf(DriverIneligibleException.class)
                    .hasMessageContaining("Driver is no longer eligible")
                    .satisfies(ex -> {
                        DriverIneligibleException die = (DriverIneligibleException) ex;
                        assertThat(die.getReasons()).contains("DRIVER_SUSPENDED");
                    });

            // Must evict from Redis atomically via OFFLINE_SCRIPT
            verify(redis, times(1)).execute(
                    eq(DriverLocationService.OFFLINE_SCRIPT),
                    eq(List.of(
                            "rhl:driver:live:" + driverId,
                            "rhl:driver:seq:" + driverId,
                            "rhl:geo:drivers",
                            "rhl:driver:session:" + driverId,
                            "rhl:driver:vehicle:" + driverId
                    )),
                    eq(driverId.toString())
            );
        }

        @Test
        @DisplayName("Eligible online driver successfully updates GPS in Redis via atomic script")
        void update_whenDriverEligible_updatesRedis() {
            mockRedisOps();
            when(valueOps.get("rhl:driver:live:" + driverId)).thenReturn(String.valueOf(System.currentTimeMillis()));

            DriverEligibilityClientResponse eligible = new DriverEligibilityClientResponse(
                    driverId, true, "ACTIVE", "APPROVED", true, List.of()
            );
            when(identityClient.getEligibility(driverId)).thenReturn(eligible);

            DriverLocationResponse response = locationService.update(driverId, validRequest);

            assertThat(response.driverId()).isEqualTo(driverId);
            assertThat(response.status()).isEqualTo("ONLINE");
            verify(redis, times(1)).execute(
                    eq(DriverLocationService.UPDATE_SCRIPT),
                    eq(List.of(
                            "rhl:driver:live:" + driverId,
                            "rhl:driver:seq:" + driverId,
                            "rhl:geo:drivers",
                            "rhl:driver:session:" + driverId,
                            "rhl:driver:vehicle:" + driverId
                    )),
                    eq(driverId.toString()),
                    eq("105.8342"),
                    eq("21.0278"),
                    eq(""),
                    anyString(),
                    eq("90"),
                    eq("")
            );
        }

        @Test
        @DisplayName("High-frequency update uses cache and does not re-query identity on every tick")
        void update_highFrequencyPings_usesEligibilityCache() {
            mockRedisOps();
            when(valueOps.get("rhl:driver:live:" + driverId)).thenReturn(String.valueOf(System.currentTimeMillis()));
            when(redis.execute(
                    eq(DriverLocationService.UPDATE_SCRIPT),
                    anyList(),
                    any(), any(), any(), any(), any(), any(), any()
            )).thenReturn(1L);

            DriverEligibilityClientResponse eligible = new DriverEligibilityClientResponse(
                    driverId, true, "ACTIVE", "APPROVED", true, List.of()
            );
            when(identityClient.getEligibility(driverId)).thenReturn(eligible);

            // First online / update
            locationService.online(driverId, validRequest);
            // Subsequent rapid update
            locationService.update(driverId, validRequest);

            // getEligibility called once for online, and update used cache within 15s window
            verify(identityClient, times(1)).getEligibility(driverId);
        }
    }

    @Nested
    @DisplayName("offline() Tests - Unconditional Offline")
    class OfflineTests {

        @Test
        @DisplayName("Driver going offline evicts Redis keys unconditionally without querying Identity service")
        void offline_evictsFromRedisWithoutIdentityCall() {
            mockRedisOps();

            DriverLocationResponse response = locationService.offline(driverId);

            assertThat(response.driverId()).isEqualTo(driverId);
            assertThat(response.status()).isEqualTo("OFFLINE");

            verify(redis, times(1)).execute(
                    eq(DriverLocationService.OFFLINE_SCRIPT),
                    eq(List.of(
                            "rhl:driver:live:" + driverId,
                            "rhl:driver:seq:" + driverId,
                            "rhl:geo:drivers",
                            "rhl:driver:session:" + driverId,
                            "rhl:driver:vehicle:" + driverId
                    )),
                    eq(driverId.toString())
            );
            verifyNoInteractions(identityClient);
        }
    }

    @Nested
    @DisplayName("Coordinate Validation Tests (FR-LOC-001, Redis Web Mercator bounds)")
    class CoordinateValidationTests {

        @Test
        @DisplayName("Latitude > 85.0 throws IllegalArgumentException")
        void validateCoords_latitudeAboveMax_throwsIllegalArgumentException() {
            LocationUpdateRequest request = new LocationUpdateRequest(105.8342, 85.052);
            assertThatThrownBy(() -> locationService.online(driverId, request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid GPS coordinates");
            verifyNoInteractions(redis);
        }

        @Test
        @DisplayName("Latitude < -85.0 throws IllegalArgumentException")
        void validateCoords_latitudeBelowMin_throwsIllegalArgumentException() {
            LocationUpdateRequest request = new LocationUpdateRequest(105.8342, -85.052);
            assertThatThrownBy(() -> locationService.online(driverId, request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid GPS coordinates");
            verifyNoInteractions(redis);
        }

        @Test
        @DisplayName("Longitude > 180.0 throws IllegalArgumentException")
        void validateCoords_longitudeAboveMax_throwsIllegalArgumentException() {
            LocationUpdateRequest request = new LocationUpdateRequest(180.1, 21.0278);
            assertThatThrownBy(() -> locationService.online(driverId, request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid GPS coordinates");
            verifyNoInteractions(redis);
        }

        @Test
        @DisplayName("Longitude < -180.0 throws IllegalArgumentException")
        void validateCoords_longitudeBelowMin_throwsIllegalArgumentException() {
            LocationUpdateRequest request = new LocationUpdateRequest(-180.1, 21.0278);
            assertThatThrownBy(() -> locationService.online(driverId, request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid GPS coordinates");
            verifyNoInteractions(redis);
        }

        @Test
        @DisplayName("NaN coordinate throws IllegalArgumentException")
        void validateCoords_nanLatitude_throwsIllegalArgumentException() {
            LocationUpdateRequest request = new LocationUpdateRequest(105.8342, Double.NaN);
            assertThatThrownBy(() -> locationService.online(driverId, request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid GPS coordinates");
            verifyNoInteractions(redis);
        }

        @Test
        @DisplayName("Infinite coordinate throws IllegalArgumentException")
        void validateCoords_infiniteLongitude_throwsIllegalArgumentException() {
            LocationUpdateRequest request = new LocationUpdateRequest(Double.POSITIVE_INFINITY, 21.0278);
            assertThatThrownBy(() -> locationService.online(driverId, request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid GPS coordinates");
            verifyNoInteractions(redis);
        }

        @Test
        @DisplayName("Null request or null coordinates throws IllegalArgumentException")
        void validateCoords_nullCoords_throwsIllegalArgumentException() {
            LocationUpdateRequest request = new LocationUpdateRequest(null, 21.0278);
            assertThatThrownBy(() -> locationService.online(driverId, request))
                    .isInstanceOf(IllegalArgumentException.class);
            verifyNoInteractions(redis);
        }
    }

    @Nested
    @DisplayName("Telemetry Accuracy & Timestamp Tests (FR-LOC-003, DR-GEO-006)")
    class TelemetryAccuracyAndTimestampTests {

        @Test
        @DisplayName("Accuracy exceeding 100 meters is rejected")
        void validateAccuracy_whenExceeds100M_throwsTelemetryValidationException() {
            LocationUpdateRequest request = new LocationUpdateRequest(
                    105.8342, 21.0278, Instant.now(), 105.0, null, null, null
            );
            assertThatThrownBy(() -> locationService.online(driverId, request))
                    .isInstanceOf(TelemetryValidationException.class)
                    .satisfies(ex -> {
                        TelemetryValidationException tve = (TelemetryValidationException) ex;
                        assertThat(tve.getErrorCode()).isEqualTo("TELEMETRY_ACCURACY_UNACCEPTABLE");
                    });
            verifyNoInteractions(redis);
        }

        @Test
        @DisplayName("Non-positive accuracy is rejected")
        void validateAccuracy_whenNonPositive_throwsTelemetryValidationException() {
            LocationUpdateRequest request = new LocationUpdateRequest(
                    105.8342, 21.0278, Instant.now(), -5.0, null, null, null
            );
            assertThatThrownBy(() -> locationService.online(driverId, request))
                    .isInstanceOf(TelemetryValidationException.class)
                    .satisfies(ex -> {
                        TelemetryValidationException tve = (TelemetryValidationException) ex;
                        assertThat(tve.getErrorCode()).isEqualTo("TELEMETRY_ACCURACY_UNACCEPTABLE");
                    });
            verifyNoInteractions(redis);
        }

        @Test
        @DisplayName("Timestamp older than maxStaleSeconds is rejected")
        void validateTimestamp_whenOlderThan60s_throwsTelemetryValidationException() {
            Instant staleTime = Instant.now().minus(65, ChronoUnit.SECONDS);
            LocationUpdateRequest request = new LocationUpdateRequest(
                    105.8342, 21.0278, staleTime, 10.0, null, null, null
            );
            assertThatThrownBy(() -> locationService.online(driverId, request))
                    .isInstanceOf(TelemetryValidationException.class)
                    .satisfies(ex -> {
                        TelemetryValidationException tve = (TelemetryValidationException) ex;
                        assertThat(tve.getErrorCode()).isEqualTo("TELEMETRY_DATA_TOO_OLD");
                    });
            verifyNoInteractions(redis);
        }

        @Test
        @DisplayName("Timestamp further into future than maxFutureSkewSeconds is rejected")
        void validateTimestamp_whenFutureSkewExceeds15s_throwsTelemetryValidationException() {
            Instant futureTime = Instant.now().plus(20, ChronoUnit.SECONDS);
            LocationUpdateRequest request = new LocationUpdateRequest(
                    105.8342, 21.0278, futureTime, 10.0, null, null, null
            );
            assertThatThrownBy(() -> locationService.online(driverId, request))
                    .isInstanceOf(TelemetryValidationException.class)
                    .satisfies(ex -> {
                        TelemetryValidationException tve = (TelemetryValidationException) ex;
                        assertThat(tve.getErrorCode()).isEqualTo("TELEMETRY_TIMESTAMP_IN_FUTURE");
                    });
            verifyNoInteractions(redis);
        }
    }

    @Nested
    @DisplayName("Sequence Ordering & Concurrency Protection Tests (FR-LOC-003, DR-GEO-006)")
    class SequenceOrderingTests {

        @Test
        @DisplayName("First sequence number sets sequence key in Redis and succeeds")
        void sequence_whenFirstSequence_storesInRedis() {
            mockRedisOps();
            when(valueOps.get("rhl:driver:live:" + driverId)).thenReturn(String.valueOf(System.currentTimeMillis()));

            DriverEligibilityClientResponse eligible = new DriverEligibilityClientResponse(
                    driverId, true, "ACTIVE", "APPROVED", true, List.of()
            );
            when(identityClient.getEligibility(driverId)).thenReturn(eligible);

            LocationUpdateRequest request = new LocationUpdateRequest(
                    105.8342, 21.0278, Instant.now(), 10.0, 100L, null, null
            );

            DriverLocationResponse response = locationService.update(driverId, request);

            assertThat(response.status()).isEqualTo("ONLINE");
            verify(redis, times(1)).execute(
                    eq(DriverLocationService.UPDATE_SCRIPT),
                    eq(List.of(
                            "rhl:driver:live:" + driverId,
                            "rhl:driver:seq:" + driverId,
                            "rhl:geo:drivers",
                            "rhl:driver:session:" + driverId,
                            "rhl:driver:vehicle:" + driverId
                    )),
                    eq(driverId.toString()),
                    eq("105.8342"),
                    eq("21.0278"),
                    eq("100"),
                    anyString(),
                    eq("90"),
                    eq("")
            );
        }

        @Test
        @DisplayName("Increasing sequence number updates sequence key in Redis and succeeds")
        void sequence_whenIncreasingSequence_updatesRedis() {
            mockRedisOps();
            when(valueOps.get("rhl:driver:live:" + driverId)).thenReturn(String.valueOf(System.currentTimeMillis()));

            DriverEligibilityClientResponse eligible = new DriverEligibilityClientResponse(
                    driverId, true, "ACTIVE", "APPROVED", true, List.of()
            );
            when(identityClient.getEligibility(driverId)).thenReturn(eligible);

            LocationUpdateRequest request = new LocationUpdateRequest(
                    105.8342, 21.0278, Instant.now(), 10.0, 101L, null, null
            );

            DriverLocationResponse response = locationService.update(driverId, request);

            assertThat(response.status()).isEqualTo("ONLINE");
            verify(redis, times(1)).execute(
                    eq(DriverLocationService.UPDATE_SCRIPT),
                    eq(List.of(
                            "rhl:driver:live:" + driverId,
                            "rhl:driver:seq:" + driverId,
                            "rhl:geo:drivers",
                            "rhl:driver:session:" + driverId,
                            "rhl:driver:vehicle:" + driverId
                    )),
                    eq(driverId.toString()),
                    eq("105.8342"),
                    eq("21.0278"),
                    eq("101"),
                    anyString(),
                    eq("90"),
                    eq("")
            );
        }

        @Test
        @DisplayName("Out-of-order sequence (seq < currentSeq) is rejected with 409 OUT_OF_ORDER_TELEMETRY")
        void sequence_whenLowerThanCurrent_throwsOutOfOrderTelemetryException() {
            mockRedisOps();
            when(valueOps.get("rhl:driver:live:" + driverId)).thenReturn(String.valueOf(System.currentTimeMillis()));
            DriverEligibilityClientResponse eligible = new DriverEligibilityClientResponse(
                    driverId, true, "ACTIVE", "APPROVED", true, List.of()
            );
            when(identityClient.getEligibility(driverId)).thenReturn(eligible);
            when(redis.execute(
                    eq(DriverLocationService.UPDATE_SCRIPT),
                    anyList(),
                    any(), any(), any(), any(), any(), any(), any()
            )).thenReturn(-2L);

            LocationUpdateRequest request = new LocationUpdateRequest(
                    105.8342, 21.0278, Instant.now(), 10.0, 104L, null, null
            );

            assertThatThrownBy(() -> locationService.update(driverId, request))
                    .isInstanceOf(OutOfOrderTelemetryException.class)
                    .satisfies(ex -> {
                        OutOfOrderTelemetryException ooe = (OutOfOrderTelemetryException) ex;
                        assertThat(ooe.getErrorCode()).isEqualTo("OUT_OF_ORDER_TELEMETRY");
                    });
        }

        @Test
        @DisplayName("Duplicate sequence (seq == currentSeq) is rejected with 409 DUPLICATE_TELEMETRY")
        void sequence_whenDuplicate_throwsOutOfOrderTelemetryException() {
            mockRedisOps();
            when(valueOps.get("rhl:driver:live:" + driverId)).thenReturn(String.valueOf(System.currentTimeMillis()));
            DriverEligibilityClientResponse eligible = new DriverEligibilityClientResponse(
                    driverId, true, "ACTIVE", "APPROVED", true, List.of()
            );
            when(identityClient.getEligibility(driverId)).thenReturn(eligible);
            when(redis.execute(
                    eq(DriverLocationService.UPDATE_SCRIPT),
                    anyList(),
                    any(), any(), any(), any(), any(), any(), any()
            )).thenReturn(-3L);

            LocationUpdateRequest request = new LocationUpdateRequest(
                    105.8342, 21.0278, Instant.now(), 10.0, 105L, null, null
            );

            assertThatThrownBy(() -> locationService.update(driverId, request))
                    .isInstanceOf(OutOfOrderTelemetryException.class)
                    .satisfies(ex -> {
                        OutOfOrderTelemetryException ooe = (OutOfOrderTelemetryException) ex;
                        assertThat(ooe.getErrorCode()).isEqualTo("DUPLICATE_TELEMETRY");
                    });
        }
    }

    @Nested
    @DisplayName("Speed Anomaly & Anti-Spoofing Tests (FR-LOC-003, DR-GEO-006)")
    class SpeedAnomalyTests {

        @Test
        @DisplayName("Plausible speed update succeeds without error")
        void speedAnomaly_whenNormalMovement_succeeds() {
            mockRedisOps();
            long prevTime = System.currentTimeMillis() - 10000; // 10 seconds ago
            when(valueOps.get("rhl:driver:live:" + driverId)).thenReturn(String.valueOf(prevTime));

            // Previous position in Hanoi
            when(geoOps.position("rhl:geo:drivers", driverId.toString()))
                    .thenReturn(List.of(new Point(105.8342, 21.0278)));

            DriverEligibilityClientResponse eligible = new DriverEligibilityClientResponse(
                    driverId, true, "ACTIVE", "APPROVED", true, List.of()
            );
            when(identityClient.getEligibility(driverId)).thenReturn(eligible);
            when(redis.execute(
                    eq(DriverLocationService.UPDATE_SCRIPT),
                    anyList(),
                    any(), any(), any(), any(), any(), any(), any()
            )).thenReturn(1L);

            // Moved ~50 meters in 10 seconds = 18 km/h < 150 km/h
            LocationUpdateRequest request = new LocationUpdateRequest(
                    105.8345, 21.0279, Instant.now(), 10.0, null, null, null
            );

            DriverLocationResponse response = locationService.update(driverId, request);
            assertThat(response.status()).isEqualTo("ONLINE");
            verify(redis, times(1)).execute(
                    eq(DriverLocationService.UPDATE_SCRIPT),
                    eq(List.of(
                            "rhl:driver:live:" + driverId,
                            "rhl:driver:seq:" + driverId,
                            "rhl:geo:drivers",
                            "rhl:driver:session:" + driverId,
                            "rhl:driver:vehicle:" + driverId
                    )),
                    eq(driverId.toString()),
                    eq("105.8345"),
                    eq("21.0279"),
                    eq(""),
                    anyString(),
                    eq("90"),
                    eq("")
            );
        }

        @Test
        @DisplayName("Impossible speed jump (> 150 km/h and > 200m) is rejected with TELEMETRY_IMPOSSIBLE_SPEED")
        void speedAnomaly_whenImpossibleJump_throwsTelemetryValidationException() {
            mockRedisOps();
            long prevTime = System.currentTimeMillis() - 2000; // 2 seconds ago
            when(valueOps.get("rhl:driver:live:" + driverId)).thenReturn(String.valueOf(prevTime));

            // Previous position in Hanoi (105.8342, 21.0278)
            when(geoOps.position("rhl:geo:drivers", driverId.toString()))
                    .thenReturn(List.of(new Point(105.8342, 21.0278)));

            // Teleport ~10 km away in 2 seconds = 18000 km/h >> 150 km/h
            LocationUpdateRequest request = new LocationUpdateRequest(
                    105.9342, 21.1278, Instant.now(), 10.0, null, null, null
            );

            assertThatThrownBy(() -> locationService.update(driverId, request))
                    .isInstanceOf(TelemetryValidationException.class)
                    .satisfies(ex -> {
                        TelemetryValidationException tve = (TelemetryValidationException) ex;
                        assertThat(tve.getErrorCode()).isEqualTo("TELEMETRY_IMPOSSIBLE_SPEED");
                    });

            // Rejected GPS must NOT update Redis via UPDATE_SCRIPT
            verify(redis, never()).execute(eq(DriverLocationService.UPDATE_SCRIPT), anyList(), any(), any(), any(), any(), any(), any());
            verifyNoInteractions(identityClient);
        }
    }

    @Nested
    @DisplayName("nearby() Tests - Batch Verification and Self-Healing Cleanup")
    class NearbyTests {

        @Test
        @DisplayName("Nearby search filters out ineligible drivers and cleans them from Redis")
        void nearby_filtersIneligibleAndCleansStaleRedis() {
            mockRedisOps();

            UUID eligibleDriver = UUID.randomUUID();
            UUID suspendedDriver = UUID.randomUUID();

            GeoResult<RedisGeoCommands.GeoLocation<String>> res1 = new GeoResult<>(
                    new RedisGeoCommands.GeoLocation<>(eligibleDriver.toString(), new Point(105.834, 21.028)),
                    new Distance(1.2, Metrics.KILOMETERS)
            );
            GeoResult<RedisGeoCommands.GeoLocation<String>> res2 = new GeoResult<>(
                    new RedisGeoCommands.GeoLocation<>(suspendedDriver.toString(), new Point(105.835, 21.029)),
                    new Distance(1.5, Metrics.KILOMETERS)
            );

            GeoResults<RedisGeoCommands.GeoLocation<String>> geoResults = new GeoResults<>(List.of(res1, res2));

            when(geoOps.search(eq("rhl:geo:drivers"), any(), any(Distance.class), any(RedisGeoCommands.GeoSearchCommandArgs.class)))
                    .thenReturn(geoResults);

            when(valueOps.multiGet(List.of("rhl:driver:live:" + eligibleDriver, "rhl:driver:live:" + suspendedDriver)))
                    .thenReturn(List.of(String.valueOf(System.currentTimeMillis()), String.valueOf(System.currentTimeMillis())));

            when(identityClient.getBatchEligibility(List.of(eligibleDriver, suspendedDriver)))
                    .thenReturn(List.of(
                            new DriverEligibilityClientResponse(eligibleDriver, true, "ACTIVE", "APPROVED", true, List.of()),
                            new DriverEligibilityClientResponse(suspendedDriver, false, "ACTIVE", "SUSPENDED", true, List.of("DRIVER_SUSPENDED"))
                    ));

            List<NearbyDriverResponse> nearby = locationService.nearby(105.8342, 21.0278, 3.0, 10);

            assertThat(nearby).hasSize(1);
            assertThat(nearby.get(0).driverId()).isEqualTo(eligibleDriver);

            // Verify suspended driver was evicted from Redis atomically (self-healing)
            verify(redis, times(1)).execute(
                    eq(DriverLocationService.OFFLINE_SCRIPT),
                    eq(List.of(
                            "rhl:driver:live:" + suspendedDriver,
                            "rhl:driver:seq:" + suspendedDriver,
                            "rhl:geo:drivers",
                            "rhl:driver:session:" + suspendedDriver,
                            "rhl:driver:vehicle:" + suspendedDriver
                    )),
                    eq(suspendedDriver.toString())
            );
        }

        @Test
        @DisplayName("Identity service down during nearby search fails-closed")
        void nearby_whenIdentityDown_failsClosed() {
            mockRedisOps();

            UUID driver1 = UUID.randomUUID();
            GeoResult<RedisGeoCommands.GeoLocation<String>> res1 = new GeoResult<>(
                    new RedisGeoCommands.GeoLocation<>(driver1.toString(), new Point(105.834, 21.028)),
                    new Distance(1.2, Metrics.KILOMETERS)
            );
            when(geoOps.search(eq("rhl:geo:drivers"), any(), any(Distance.class), any(RedisGeoCommands.GeoSearchCommandArgs.class)))
                    .thenReturn(new GeoResults<>(List.of(res1)));
            when(valueOps.multiGet(List.of("rhl:driver:live:" + driver1)))
                    .thenReturn(List.of(String.valueOf(System.currentTimeMillis())));

            when(identityClient.getBatchEligibility(List.of(driver1)))
                    .thenThrow(new IdentityServiceUnavailableException("Identity service unavailable"));

            assertThatThrownBy(() -> locationService.nearby(105.8342, 21.0278, 3.0, 10))
                    .isInstanceOf(IdentityServiceUnavailableException.class);
        }
    }

    @Nested
    @DisplayName("Concurrency & Redis Optimization Tests (M03 Group 2)")
    class ConcurrencyAndOptimizationTests {

        @Test
        @DisplayName("Simultaneous offline race: delayed GPS cannot resurrect presence (Lua returns -1)")
        void concurrency_whenOfflineOccursDuringGpsProcessing_rejectsWithDriverOffline() {
            mockRedisOps();
            when(valueOps.get("rhl:driver:live:" + driverId)).thenReturn(String.valueOf(System.currentTimeMillis()));

            DriverEligibilityClientResponse eligible = new DriverEligibilityClientResponse(
                    driverId, true, "ACTIVE", "APPROVED", true, List.of()
            );
            when(identityClient.getEligibility(driverId)).thenReturn(eligible);

            // Simulate race: Lua script detects liveKey was deleted by concurrent offline()
            when(redis.execute(
                    eq(DriverLocationService.UPDATE_SCRIPT),
                    anyList(),
                    any(), any(), any(), any(), any(), any(), any()
            )).thenReturn(-1L);

            LocationUpdateRequest request = new LocationUpdateRequest(105.8342, 21.0278);

            assertThatThrownBy(() -> locationService.update(driverId, request))
                    .isInstanceOf(DriverOfflineException.class);

            verify(geoOps, never()).add(anyString(), any(Point.class), anyString());
        }

        @Test
        @DisplayName("Nearby MGET skips drivers with expired heartbeat without calling Identity")
        void nearby_multiGet_skipsDriversWithExpiredHeartbeatWithoutCallingIdentity() {
            mockRedisOps();

            UUID expiredDriver = UUID.randomUUID();
            GeoResult<RedisGeoCommands.GeoLocation<String>> res = new GeoResult<>(
                    new RedisGeoCommands.GeoLocation<>(expiredDriver.toString(), new Point(105.834, 21.028)),
                    new Distance(1.0, Metrics.KILOMETERS)
            );
            when(geoOps.search(eq("rhl:geo:drivers"), any(), any(Distance.class), any(RedisGeoCommands.GeoSearchCommandArgs.class)))
                    .thenReturn(new GeoResults<>(List.of(res)));

            // multiGet returns null for the expired driver's liveKey
            when(valueOps.multiGet(List.of("rhl:driver:live:" + expiredDriver)))
                    .thenReturn(new java.util.ArrayList<>(java.util.Collections.singletonList(null)));

            List<NearbyDriverResponse> result = locationService.nearby(105.8342, 21.0278, 3.0, 10);

            assertThat(result).isEmpty();
            verifyNoInteractions(identityClient);
        }

        @Test
        @DisplayName("Atomic script returns null: fails closed with DriverOfflineException")
        void concurrency_whenScriptReturnsNull_failsClosed() {
            mockRedisOps();
            when(valueOps.get("rhl:driver:live:" + driverId)).thenReturn(String.valueOf(System.currentTimeMillis()));

            DriverEligibilityClientResponse eligible = new DriverEligibilityClientResponse(
                    driverId, true, "ACTIVE", "APPROVED", true, List.of()
            );
            when(identityClient.getEligibility(driverId)).thenReturn(eligible);

            when(redis.execute(
                    eq(DriverLocationService.UPDATE_SCRIPT),
                    anyList(),
                    any(), any(), any(), any(), any(), any(), any()
            )).thenReturn(null);

            LocationUpdateRequest request = new LocationUpdateRequest(105.8342, 21.0278);

            assertThatThrownBy(() -> locationService.update(driverId, request))
                    .isInstanceOf(DriverOfflineException.class);
        }
    }
}
