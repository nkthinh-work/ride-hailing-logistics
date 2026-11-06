package com.rhl.pricing.surge;

import com.rhl.pricing.models.ServiceType;
import com.rhl.pricing.models.VehicleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Cross-service integration adapter contract for {@code location-service} (FR-PRI-008).
 *
 * <p><strong>CRITICAL ARCHITECTURAL DISTINCTIONS & AUDIT GAP:</strong>
 * <ul>
 *   <li><strong>Driver ONLINE != Driver AVAILABLE:</strong> {@code location-service} currently tracks ONLINE presence
 *       via Redis GEO and live TTL keys ({@code rhl:driver:live:{id}}). However, an ONLINE driver may be BUSY (on a trip)
 *       or OFFERED (evaluating a dispatch dispatch offer). The AVAILABLE state machine belongs to M05/M06.</li>
 *   <li><strong>Missing Active Vehicle Selection:</strong> In {@code identity-driver-service}, a driver may own multiple vehicles.
 *       However, the current location telemetry contract ({@code LocationUpdateRequest}) does not record which vehicle
 *       is actively being driven in the current online session.</li>
 *   <li><strong>Machine-to-Machine Security:</strong> Location's {@code /api/v1/locations/nearby} currently requires
 *       {@code hasRole('ADMINISTRATOR')}. Reusing administrative credentials for service-to-service calls is a security anti-pattern.
 *       Dedicated service-to-service authentication (e.g. mTLS or signed internal JWTs) is required before production activation.</li>
 * </ul>
 *
 * <p>Status: {@code REALTIME_SUPPLY_INTEGRATION_PENDING — DEPENDS_ON_DISPATCH_AND_LOCATION_MACHINE_API}.
 */
@Component
public class LocationServiceSupplyAdapter {

    private static final Logger log = LoggerFactory.getLogger(LocationServiceSupplyAdapter.class);

    /**
     * Contract method for fetching eligible available driver counts from Location and Dispatch.
     *
     * @param serviceType service category
     * @param vehicleType vehicle category
     * @param regionId    region identifier
     * @param targetTime  evaluation time
     * @return count of available drivers, or -1 if the dependency is not yet available in production
     */
    public long fetchAvailableDriverCount(
            ServiceType serviceType,
            VehicleType vehicleType,
            String regionId,
            Instant targetTime) {

        log.debug("Realtime supply query requested for service: {}, vehicle: {}, region: {} (Status: PENDING M05/M06 integration)",
                serviceType, vehicleType, regionId);

        // When external location/dispatch machine API is provisioned, HTTP/REST calls with timeout and circuit breaking will live here.
        // For Group 4, this adapter records cross-service readiness without faking production realtime telemetry.
        return -1;
    }

    public boolean isProductionReady() {
        return false;
    }
}
