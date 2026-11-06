package com.rhl.location.integration;

import com.rhl.location.clients.IdentityDriverClient;
import com.rhl.location.dtos.DriverEligibilityClientResponse;
import com.rhl.location.dtos.LocationUpdateRequest;
import com.rhl.location.dtos.NearbyDriverResponse;
import com.rhl.location.exceptions.IdentityServiceUnavailableException;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@SpringBootTest
class NearbySearchRedisIntegrationTest {

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

    private String liveKey(UUID driverId) {
        return "rhl:driver:live:" + driverId;
    }

    @Test
    @DisplayName("Nearby search returns empty list when Redis GEO index is empty")
    void nearby_emptyIndex_returnsEmptyList() {
        List<NearbyDriverResponse> responses = locationService.nearby(105.8342, 21.0278, 5.0, 10);
        assertThat(responses).isEmpty();
    }

    @Test
    @DisplayName("Nearby search finds single eligible online driver within radius")
    void nearby_singleEligibleDriver_found() {
        UUID driverId = UUID.randomUUID();
        locationService.online(driverId, new LocationUpdateRequest(105.8350, 21.0280, Instant.now(), 10.0, 1L, null, null));

        List<NearbyDriverResponse> responses = locationService.nearby(105.8342, 21.0278, 5.0, 10);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).driverId()).isEqualTo(driverId);
        assertThat(responses.get(0).distanceKm()).isLessThan(1.0);
    }

    @Test
    @DisplayName("Nearby search filters out stale GEO member whose heartbeat liveKey has expired")
    void nearby_staleGeoMember_filteredOutByMultiGet() {
        UUID activeDriverId = UUID.randomUUID();
        UUID staleDriverId = UUID.randomUUID();

        // Put active driver online
        locationService.online(activeDriverId, new LocationUpdateRequest(105.8350, 21.0280, Instant.now(), 10.0, 1L, null, null));

        // Put second driver online, then simulate heartbeat expiration by deleting liveKey
        locationService.online(staleDriverId, new LocationUpdateRequest(105.8360, 21.0290, Instant.now(), 10.0, 1L, null, null));
        redis.delete(liveKey(staleDriverId));

        // Nearby search must filter out stale driver using multiGet
        List<NearbyDriverResponse> responses = locationService.nearby(105.8342, 21.0278, 5.0, 10);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).driverId()).isEqualTo(activeDriverId);
    }

    @Test
    @DisplayName("Nearby search filters out SUSPENDED driver and triggers self-healing cleanup")
    void nearby_suspendedDriver_filteredAndCleanedUp() {
        UUID eligibleDriverId = UUID.randomUUID();
        UUID suspendedDriverId = UUID.randomUUID();

        locationService.online(eligibleDriverId, new LocationUpdateRequest(105.8350, 21.0280, Instant.now(), 10.0, 1L, null, null));
        locationService.online(suspendedDriverId, new LocationUpdateRequest(105.8360, 21.0290, Instant.now(), 10.0, 1L, null, null));

        // Identity reports suspendedDriverId is ineligible
        when(identityClient.getBatchEligibility(anyList())).thenReturn(List.of(
                new DriverEligibilityClientResponse(eligibleDriverId, true, "ACTIVE", "APPROVED", true, List.of()),
                new DriverEligibilityClientResponse(suspendedDriverId, false, "SUSPENDED", "APPROVED", true, List.of("DRIVER_SUSPENDED"))
        ));

        List<NearbyDriverResponse> responses = locationService.nearby(105.8342, 21.0278, 5.0, 10);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).driverId()).isEqualTo(eligibleDriverId);

        // Verify self-healing cleanup: suspended driver was evicted from Redis
        assertThat(redis.hasKey(liveKey(suspendedDriverId))).isFalse();
        var points = redis.opsForGeo().position(GEO_KEY, suspendedDriverId.toString());
        assertThat(points == null || points.isEmpty() || points.get(0) == null).isTrue();
    }

    @Test
    @DisplayName("Nearby search fail-closed: Identity service failure aborts candidate dispatch")
    void nearby_identityServiceUnavailable_failClosed() {
        UUID driverId = UUID.randomUUID();
        locationService.online(driverId, new LocationUpdateRequest(105.8350, 21.0280, Instant.now(), 10.0, 1L, null, null));

        when(identityClient.getBatchEligibility(anyList()))
                .thenThrow(new IdentityServiceUnavailableException("Identity service down"));

        assertThatThrownBy(() -> locationService.nearby(105.8342, 21.0278, 5.0, 10))
                .isInstanceOf(IdentityServiceUnavailableException.class);
    }
}
