package com.rhl.location.services;

import com.rhl.location.clients.IdentityDriverClient;
import com.rhl.location.dtos.*;
import com.rhl.location.exceptions.AmbiguousVehicleException;
import com.rhl.location.exceptions.DriverIneligibleException;
import com.rhl.location.exceptions.DriverOfflineException;
import com.rhl.location.exceptions.InvalidSessionException;
import com.rhl.location.exceptions.InvalidVehicleSessionException;
import com.rhl.location.exceptions.OutOfOrderTelemetryException;
import com.rhl.location.exceptions.TelemetryValidationException;
import com.rhl.location.utils.GeoUtils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

@Service
public class DriverLocationService {

    private static final Logger log = LoggerFactory.getLogger(DriverLocationService.class);

    private static final String GEO_KEY = "rhl:geo:drivers";

    private static final Duration LIVE_TTL = Duration.ofSeconds(90);

    private static final Duration ELIGIBILITY_CACHE_TTL = Duration.ofSeconds(15);

    public static final RedisScript<Long> UPDATE_SCRIPT;
    public static final RedisScript<Long> ONLINE_SCRIPT;
    public static final RedisScript<Long> OFFLINE_SCRIPT;

    static {
        DefaultRedisScript<Long> update = new DefaultRedisScript<>();
        update.setLocation(new ClassPathResource("scripts/atomic_location_update.lua"));
        update.setResultType(Long.class);
        UPDATE_SCRIPT = update;

        DefaultRedisScript<Long> online = new DefaultRedisScript<>();
        online.setLocation(new ClassPathResource("scripts/atomic_driver_online.lua"));
        online.setResultType(Long.class);
        ONLINE_SCRIPT = online;

        DefaultRedisScript<Long> offline = new DefaultRedisScript<>();
        offline.setLocation(new ClassPathResource("scripts/atomic_driver_offline.lua"));
        offline.setResultType(Long.class);
        OFFLINE_SCRIPT = offline;
    }

    private final StringRedisTemplate redis;
    private final IdentityDriverClient identityClient;
    private final ConcurrentMap<UUID, Instant> verifiedEligibilityCache = new ConcurrentHashMap<>();

    private com.rhl.location.clients.RealtimeLocationPublisherClient realtimePublisher;

    private final long maxStaleSeconds;
    private final long maxFutureSkewSeconds;
    private final double maxAccuracyMeters;
    private final double maxSpeedKmh;
    private final double minJumpDistanceMeters;

    @Autowired(required = false)
    public void setRealtimePublisher(com.rhl.location.clients.RealtimeLocationPublisherClient realtimePublisher) {
        this.realtimePublisher = realtimePublisher;
    }

    public DriverLocationService(
            StringRedisTemplate redis,
            IdentityDriverClient identityClient) {
        this(redis, identityClient, 60, 15, 100.0, 150.0, 200.0);
    }

    @Autowired
    public DriverLocationService(
            StringRedisTemplate redis,
            IdentityDriverClient identityClient,
            @Value("${location.telemetry.max-stale-seconds:60}") long maxStaleSeconds,
            @Value("${location.telemetry.max-future-skew-seconds:15}") long maxFutureSkewSeconds,
            @Value("${location.telemetry.max-accuracy-meters:100.0}") double maxAccuracyMeters,
            @Value("${location.telemetry.max-speed-kmh:150.0}") double maxSpeedKmh,
            @Value("${location.telemetry.min-jump-distance-meters:200.0}") double minJumpDistanceMeters) {
        this.redis = redis;
        this.identityClient = identityClient;
        this.maxStaleSeconds = maxStaleSeconds;
        this.maxFutureSkewSeconds = maxFutureSkewSeconds;
        this.maxAccuracyMeters = maxAccuracyMeters;
        this.maxSpeedKmh = maxSpeedKmh;
        this.minJumpDistanceMeters = minJumpDistanceMeters;
    }

    private String liveKey(UUID driverId) {
        return "rhl:driver:live:" + driverId;
    }

    private String liveKeyStr(String driverIdStr) {
        return "rhl:driver:live:" + driverIdStr;
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

    private String vehicleKeyStr(String driverIdStr) {
        return "rhl:driver:vehicle:" + driverIdStr;
    }

    public DriverLocationResponse online(
            UUID driverId,
            LocationUpdateRequest request) {

        Instant now = Instant.now();
        validateTelemetry(request, now);

        DriverEligibilityClientResponse eligibility = identityClient.getEligibility(driverId);
        if (eligibility == null || !eligibility.eligible()) {
            List<String> reasons = eligibility != null ? eligibility.reasons() : List.of("UNKNOWN_REASON");
            throw new DriverIneligibleException("Driver is not eligible to go online", reasons);
        }

        // Active Vehicle Selection & Ownership Validation (M06 Group 4, FR-DRV-008)
        List<VehicleClientResponse> driverVehicles = identityClient.getDriverVehicles(driverId);
        List<VehicleClientResponse> activeVehicles = driverVehicles != null
                ? driverVehicles.stream().filter(v -> v.status() != null && "ACTIVE".equalsIgnoreCase(v.status())).toList()
                : List.of();

        if (activeVehicles.isEmpty()) {
            throw new DriverIneligibleException("Driver has no active vehicles registered", List.of("NO_ACTIVE_VEHICLE"));
        }

        UUID resolvedVehicleId;
        if (request.selectedVehicleId() != null) {
            boolean matched = activeVehicles.stream().anyMatch(v -> v.vehicleId() != null && v.vehicleId().equals(request.selectedVehicleId()));
            if (!matched) {
                throw new InvalidVehicleSessionException("Selected vehicle " + request.selectedVehicleId() + " does not belong to driver or is not active", "INVALID_VEHICLE_SESSION");
            }
            resolvedVehicleId = request.selectedVehicleId();
        } else {
            if (activeVehicles.size() == 1) {
                resolvedVehicleId = activeVehicles.get(0).vehicleId();
            } else {
                throw new AmbiguousVehicleException("Driver has multiple active vehicles (" + activeVehicles.size() + "). Must specify selectedVehicleId when going online", "AMBIGUOUS_ACTIVE_VEHICLES");
            }
        }

        if (resolvedVehicleId == null) {
            throw new DriverIneligibleException("Driver active vehicle ID is missing or invalid", List.of("NO_ACTIVE_VEHICLE"));
        }

        UUID resolvedSessionId = request.sessionId() != null ? request.sessionId() : UUID.randomUUID();

        verifiedEligibilityCache.put(driverId, now);

        redis.execute(
                ONLINE_SCRIPT,
                List.of(liveKey(driverId), seqKey(driverId), GEO_KEY, sessionKey(driverId), vehicleKey(driverId)),
                driverId.toString(),
                String.valueOf(request.longitude()),
                String.valueOf(request.latitude()),
                request.sequence() != null ? request.sequence().toString() : "",
                String.valueOf(now.toEpochMilli()),
                String.valueOf(LIVE_TTL.toSeconds()),
                resolvedSessionId.toString(),
                resolvedVehicleId.toString()
        );

        return new DriverLocationResponse(
                driverId,
                "ONLINE",
                request.longitude(),
                request.latitude(),
                now,
                resolvedSessionId,
                resolvedVehicleId
        );
    }

    public DriverLocationResponse update(
            UUID driverId,
            LocationUpdateRequest request) {

        Instant now = Instant.now();
        validateTelemetry(request, now);

        if (redis.opsForValue().get(liveKey(driverId)) == null) {
            throw new DriverOfflineException();
        }

        if (request.sequence() != null && request.sequence() <= 0) {
            throw new IllegalArgumentException("Sequence must be a positive number");
        }

        validateSpeedAnomaly(driverId, request.longitude(), request.latitude(), now);

        Instant lastVerified = verifiedEligibilityCache.get(driverId);

        if (lastVerified == null || Duration.between(lastVerified, now).compareTo(ELIGIBILITY_CACHE_TTL) > 0) {
            DriverEligibilityClientResponse eligibility = identityClient.getEligibility(driverId);
            if (eligibility == null || !eligibility.eligible()) {
                verifiedEligibilityCache.remove(driverId);
                redis.execute(
                        OFFLINE_SCRIPT,
                        List.of(liveKey(driverId), seqKey(driverId), GEO_KEY, sessionKey(driverId), vehicleKey(driverId)),
                        driverId.toString()
                );

                List<String> reasons = eligibility != null ? eligibility.reasons() : List.of("UNKNOWN_REASON");
                throw new DriverIneligibleException("Driver is no longer eligible", reasons);
            }
            verifiedEligibilityCache.put(driverId, now);
        }

        Long result = redis.execute(
                UPDATE_SCRIPT,
                List.of(liveKey(driverId), seqKey(driverId), GEO_KEY, sessionKey(driverId), vehicleKey(driverId)),
                driverId.toString(),
                String.valueOf(request.longitude()),
                String.valueOf(request.latitude()),
                request.sequence() != null ? request.sequence().toString() : "",
                String.valueOf(now.toEpochMilli()),
                String.valueOf(LIVE_TTL.toSeconds()),
                request.sessionId() != null ? request.sessionId().toString() : ""
        );

        if (result == null || result == -1L) {
            throw new DriverOfflineException();
        } else if (result == -2L) {
            throw new OutOfOrderTelemetryException(
                    "Telemetry sequence " + request.sequence() + " is older than current committed sequence",
                    "OUT_OF_ORDER_TELEMETRY"
            );
        } else if (result == -3L) {
            throw new OutOfOrderTelemetryException(
                    "Duplicate telemetry sequence " + request.sequence(),
                    "DUPLICATE_TELEMETRY"
            );
        } else if (result == -4L) {
            throw new InvalidSessionException(
                    "Telemetry packet belongs to an invalid or stale session",
                    "STALE_SESSION_PACKET"
            );
        }

        String activeSessionStr = redis.opsForValue().get(sessionKey(driverId));
        String activeVehicleStr = redis.opsForValue().get(vehicleKey(driverId));

        if (realtimePublisher != null) {
            try {
                realtimePublisher.publishDriverLocation(
                        driverId,
                        request.latitude(),
                        request.longitude(),
                        request.headingDegrees(),
                        request.speedMetersPerSecond(),
                        request.sequence(),
                        activeSessionStr != null ? UUID.fromString(activeSessionStr) : request.sessionId(),
                        now
                );
            } catch (Exception ex) {
                log.warn("Fail-safe: Error dispatching live GPS update to realtime publisher: {}", ex.getMessage());
            }
        }

        return new DriverLocationResponse(
                driverId,
                "ONLINE",
                request.longitude(),
                request.latitude(),
                now,
                activeSessionStr != null ? UUID.fromString(activeSessionStr) : request.sessionId(),
                activeVehicleStr != null ? UUID.fromString(activeVehicleStr) : null
        );
    }

    public DriverLocationResponse offline(UUID driverId) {

        verifiedEligibilityCache.remove(driverId);

        redis.execute(
                OFFLINE_SCRIPT,
                List.of(liveKey(driverId), seqKey(driverId), GEO_KEY, sessionKey(driverId), vehicleKey(driverId)),
                driverId.toString()
        );

        return new DriverLocationResponse(
                driverId,
                "OFFLINE",
                null,
                null,
                null,
                null,
                null
        );
    }

    public DriverLocationResponse getMine(UUID driverId) {

        String lastSeen =
                redis.opsForValue().get(liveKey(driverId));

        if (lastSeen == null) {
            return new DriverLocationResponse(
                    driverId, "OFFLINE", null, null, null, null, null
            );
        }

        List<Point> points = redis.opsForGeo().position(
                GEO_KEY,
                driverId.toString()
        );

        if (points == null || points.isEmpty()
                || points.get(0) == null) {
            return new DriverLocationResponse(
                    driverId, "OFFLINE", null, null, null, null, null
            );
        }

        Point point = points.get(0);
        String sessionStr = redis.opsForValue().get(sessionKey(driverId));
        String vehicleStr = redis.opsForValue().get(vehicleKey(driverId));

        return new DriverLocationResponse(
                driverId,
                "ONLINE",
                point.getX(),
                point.getY(),
                Instant.ofEpochMilli(Long.parseLong(lastSeen)),
                sessionStr != null ? UUID.fromString(sessionStr) : null,
                vehicleStr != null ? UUID.fromString(vehicleStr) : null
        );
    }

    public List<NearbyDriverResponse> nearby(
            double longitude,
            double latitude,
            double radiusKm,
            int limit) {

        validateCoordinates(longitude, latitude);

        if (!Double.isFinite(radiusKm)
                || radiusKm < 0.1 || radiusKm > 20) {
            throw new IllegalArgumentException(
                    "radiusKm must be between 0.1 and 20"
            );
        }

        if (limit < 1 || limit > 20) {
            throw new IllegalArgumentException(
                    "limit must be between 1 and 20"
            );
        }

        var reference =
                GeoReference.<String>fromCoordinate(
                        longitude, latitude
                );

        var args = RedisGeoCommands.GeoSearchCommandArgs
                .newGeoSearchArgs()
                .includeDistance()
                .includeCoordinates()
                .sortAscending()
                .limit(500);

        GeoResults<RedisGeoCommands.GeoLocation<String>> results =
                redis.opsForGeo().search(
                        GEO_KEY,
                        reference,
                        new Distance(radiusKm, Metrics.KILOMETERS),
                        args
                );

        if (results == null || results.getContent().isEmpty()) {
            return List.of();
        }

        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content = results.getContent();
        List<String> liveKeys = new ArrayList<>(content.size());
        List<String> vehicleKeys = new ArrayList<>(content.size());
        for (GeoResult<RedisGeoCommands.GeoLocation<String>> result : content) {
            String driverIdStr = result.getContent().getName();
            liveKeys.add(liveKeyStr(driverIdStr));
            vehicleKeys.add(vehicleKeyStr(driverIdStr));
        }

        // Single roundtrip MGET to eliminate N+1 Redis calls (AUD-PRF-01)
        List<String> lastSeenList = redis.opsForValue().multiGet(liveKeys);
        List<String> vehicleList = redis.opsForValue().multiGet(vehicleKeys);

        record Candidate(UUID id, Point point, double distance, Instant lastSeenTimestamp, UUID selectedVehicleId) {}
        List<Candidate> liveCandidates = new ArrayList<>();

        for (int i = 0; i < content.size(); i++) {
            GeoResult<RedisGeoCommands.GeoLocation<String>> result = content.get(i);
            String lastSeen = (lastSeenList != null && i < lastSeenList.size()) ? lastSeenList.get(i) : null;

            if (lastSeen == null) {
                // Heartbeat expired in Redis -> driver is not live
                continue;
            }

            Point point = result.getContent().getPoint();
            Distance distance = result.getDistance();

            if (point == null || distance == null) {
                continue;
            }

            try {
                UUID driverUuid = UUID.fromString(result.getContent().getName());
                Instant lastSeenInstant = Instant.ofEpochMilli(Long.parseLong(lastSeen));
                String vehicleStr = (vehicleList != null && i < vehicleList.size()) ? vehicleList.get(i) : null;
                UUID vehicleUuid = (vehicleStr != null && !vehicleStr.isBlank()) ? UUID.fromString(vehicleStr) : null;
                liveCandidates.add(new Candidate(driverUuid, point, distance.getValue(), lastSeenInstant, vehicleUuid));
            } catch (IllegalArgumentException ignored) {
            }
        }

        if (liveCandidates.isEmpty()) {
            return List.of();
        }

        List<UUID> candidateIds = liveCandidates.stream().map(Candidate::id).toList();
        List<DriverEligibilityClientResponse> eligibilityResponses =
                identityClient.getBatchEligibility(candidateIds);

        Map<UUID, Boolean> eligibilityMap = eligibilityResponses.stream()
                .collect(Collectors.toMap(
                        DriverEligibilityClientResponse::userId,
                        DriverEligibilityClientResponse::eligible,
                        (existing, replacement) -> existing
                ));

        List<NearbyDriverResponse> eligibleDrivers = new ArrayList<>();

        for (Candidate candidate : liveCandidates) {
            Boolean isEligible = eligibilityMap.get(candidate.id());

            if (Boolean.TRUE.equals(isEligible)) {
                eligibleDrivers.add(new NearbyDriverResponse(
                        candidate.id(),
                        candidate.point().getX(),
                        candidate.point().getY(),
                        candidate.distance(),
                        candidate.lastSeenTimestamp(),
                        candidate.selectedVehicleId()
                ));

                if (eligibleDrivers.size() >= limit) {
                    break;
                }
            } else {
                verifiedEligibilityCache.remove(candidate.id());
                redis.execute(
                        OFFLINE_SCRIPT,
                        List.of(liveKey(candidate.id()), seqKey(candidate.id()), GEO_KEY, sessionKey(candidate.id()), vehicleKey(candidate.id())),
                        candidate.id().toString()
                );
            }
        }

        return eligibleDrivers;
    }

    private void validateCoordinates(
            Double longitude,
            Double latitude) {

        if (longitude == null || latitude == null
                || Double.isNaN(longitude) || Double.isNaN(latitude)
                || Double.isInfinite(longitude) || Double.isInfinite(latitude)
                || longitude < -180.0 || longitude > 180.0
                || latitude < -85.0 || latitude > 85.0) {

            throw new IllegalArgumentException(
                    "Invalid GPS coordinates"
            );
        }
    }

    private void validateTelemetry(LocationUpdateRequest request, Instant now) {
        if (request == null) {
            throw new IllegalArgumentException("LocationUpdateRequest cannot be null");
        }
        validateCoordinates(request.longitude(), request.latitude());

        if (request.accuracyMeters() != null) {
            double acc = request.accuracyMeters();
            if (!Double.isFinite(acc) || acc <= 0 || acc > maxAccuracyMeters) {
                throw new TelemetryValidationException(
                        "GPS accuracy of " + acc + "m is invalid or exceeds acceptable threshold of " + maxAccuracyMeters + "m",
                        "TELEMETRY_ACCURACY_UNACCEPTABLE"
                );
            }
        }

        if (request.deviceTimestamp() != null) {
            Duration age = Duration.between(request.deviceTimestamp(), now);
            if (age.toSeconds() > maxStaleSeconds) {
                throw new TelemetryValidationException(
                        "Telemetry is too old: " + age.toSeconds() + "s ago (max allowed: " + maxStaleSeconds + "s)",
                        "TELEMETRY_DATA_TOO_OLD"
                );
            }
            if (age.toSeconds() < -maxFutureSkewSeconds) {
                throw new TelemetryValidationException(
                        "Telemetry timestamp is in the future by " + (-age.toSeconds()) + "s (max allowed skew: " + maxFutureSkewSeconds + "s)",
                        "TELEMETRY_TIMESTAMP_IN_FUTURE"
                );
            }
        }
    }


    private void validateSpeedAnomaly(UUID driverId, double newLon, double newLat, Instant now) {
        String lastSeenStr = redis.opsForValue().get(liveKey(driverId));
        if (lastSeenStr == null) {
            return;
        }

        List<Point> points = redis.opsForGeo().position(GEO_KEY, driverId.toString());
        if (points == null || points.isEmpty() || points.get(0) == null) {
            return;
        }

        Point oldPoint = points.get(0);
        long lastSeenMillis = Long.parseLong(lastSeenStr);
        long timeDeltaMillis = now.toEpochMilli() - lastSeenMillis;
        double timeDeltaSec = timeDeltaMillis / 1000.0;

        if (timeDeltaSec >= 1.0) {
            double distanceMeters = GeoUtils.distanceMeters(oldPoint.getY(), oldPoint.getX(), newLat, newLon);
            double speedKmh = GeoUtils.calculateSpeedKmh(distanceMeters, timeDeltaSec);

            if (distanceMeters > minJumpDistanceMeters && speedKmh > maxSpeedKmh) {
                throw new TelemetryValidationException(
                        "Calculated speed of " + Math.round(speedKmh) + " km/h exceeds maximum threshold of " + maxSpeedKmh + " km/h",
                        "TELEMETRY_IMPOSSIBLE_SPEED"
                );
            }
        }
    }
}
