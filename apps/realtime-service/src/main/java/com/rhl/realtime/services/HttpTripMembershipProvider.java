package com.rhl.realtime.services;

import com.rhl.realtime.models.TripMembershipInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authoritative HTTP implementation of TripMembershipProvider communicating with Trip Service (:8084).
 * Uses a short-lived memory cache to prevent hot-path N+1 database queries during high-frequency telemetry.
 */
@Service
public class HttpTripMembershipProvider implements TripMembershipProvider {

    private static final Logger log = LoggerFactory.getLogger(HttpTripMembershipProvider.class);
    private static final Duration CACHE_TTL = Duration.ofSeconds(15);

    private record CacheEntry<T>(T value, Instant expiresAt) {
        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    private final RestClient restClient;
    private final Map<UUID, CacheEntry<TripMembershipInfo>> tripCache = new ConcurrentHashMap<>();
    private final Map<UUID, CacheEntry<UUID>> driverActiveTripCache = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> terminalTrips = ConcurrentHashMap.newKeySet();

    @Autowired
    public HttpTripMembershipProvider(
            @Value("${app.trip-service.base-url:http://localhost:8084}") String baseUrl,
            @Value("${app.internal-secret:rhl-internal-secret-2026}") String internalSecret,
            @Value("${app.trip-service.timeout-ms:2000}") int timeoutMs
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(timeoutMs));

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("X-Internal-Secret", internalSecret)
                .defaultHeader("X-Caller-Service", "realtime-service")
                .build();
    }

    public HttpTripMembershipProvider(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public Optional<TripMembershipInfo> getTripMembership(UUID tripId) {
        if (tripId == null) {
            return Optional.empty();
        }

        if (terminalTrips.contains(tripId)) {
            return Optional.of(new TripMembershipInfo(tripId, null, null, "TERMINAL_REVOKED"));
        }

        CacheEntry<TripMembershipInfo> cached = tripCache.get(tripId);
        if (cached != null && !cached.isExpired()) {
            return Optional.ofNullable(cached.value());
        }

        try {
            TripClientResponse response = restClient.get()
                    .uri("/internal/v1/trips/{tripId}", tripId)
                    .retrieve()
                    .body(TripClientResponse.class);

            if (response == null || response.tripId() == null) {
                return Optional.empty();
            }

            TripMembershipInfo info = new TripMembershipInfo(
                    response.tripId(),
                    response.customerId(),
                    response.driverId(),
                    response.status()
            );

            tripCache.put(tripId, new CacheEntry<>(info, Instant.now().plus(CACHE_TTL)));
            if (info.driverId() != null && info.isStreamable()) {
                driverActiveTripCache.put(info.driverId(), new CacheEntry<>(tripId, Instant.now().plus(CACHE_TTL)));
            }

            return Optional.of(info);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return Optional.empty();
            }
            log.error("Failed to query Trip Service for tripId {}: status={}", tripId, e.getStatusCode());
            return Optional.empty();
        } catch (Exception e) {
            log.error("Network error querying Trip Service for tripId {}: {}", tripId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Optional<UUID> findActiveTripByDriverId(UUID driverId) {
        if (driverId == null) {
            return Optional.empty();
        }

        CacheEntry<UUID> cached = driverActiveTripCache.get(driverId);
        if (cached != null && !cached.isExpired()) {
            return Optional.ofNullable(cached.value());
        }

        try {
            TripClientResponse response = restClient.get()
                    .uri("/internal/v1/trips/drivers/{driverId}/active-trip", driverId)
                    .retrieve()
                    .body(TripClientResponse.class);

            if (response == null || response.tripId() == null) {
                return Optional.empty();
            }

            UUID activeTripId = response.tripId();
            driverActiveTripCache.put(driverId, new CacheEntry<>(activeTripId, Instant.now().plus(CACHE_TTL)));

            TripMembershipInfo info = new TripMembershipInfo(
                    response.tripId(),
                    response.customerId(),
                    response.driverId(),
                    response.status()
            );
            tripCache.put(activeTripId, new CacheEntry<>(info, Instant.now().plus(CACHE_TTL)));

            return Optional.of(activeTripId);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return Optional.empty();
            }
            log.warn("Failed to find active trip for driver {}: status={}", driverId, e.getStatusCode());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Network error finding active trip for driver {}: {}", driverId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void invalidateTrip(UUID tripId) {
        if (tripId == null) {
            return;
        }
        terminalTrips.add(tripId);
        CacheEntry<TripMembershipInfo> removed = tripCache.remove(tripId);
        if (removed != null && removed.value() != null && removed.value().driverId() != null) {
            driverActiveTripCache.remove(removed.value().driverId());
        }
        log.debug("Invalidated cached membership and marked terminal for trip {}", tripId);
    }

    public record TripClientResponse(
            UUID tripId,
            UUID customerId,
            UUID driverId,
            String status
    ) {}
}
