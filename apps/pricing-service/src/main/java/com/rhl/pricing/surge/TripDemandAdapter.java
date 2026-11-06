package com.rhl.pricing.surge;

import com.rhl.pricing.models.ServiceType;
import com.rhl.pricing.models.VehicleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Cross-service integration adapter contract for Trip Service demand data (FR-PRI-008).
 *
 * <p><strong>CRITICAL ARCHITECTURAL DISTINCTIONS & AUDIT GAP:</strong>
 * <ul>
 *   <li><strong>Demand != Quote Requests Count:</strong> SRS FR-PRI-008 mandates that Demand must be derived
 *       from <em>valid trip requests</em> in a configured time window, NOT customer app opens or quote calculations.
 *       Customers frequently browse prices without booking; treating quote requests as demand creates artificial,
 *       speculative surge inflation.</li>
 *   <li><strong>Dependency on Trip Service (M05):</strong> The repository currently does not have a Trip Service
 *       or trip request database. Generating fake trip requests in production is strictly prohibited.</li>
 * </ul>
 *
 * <p>Status: {@code REALTIME_DEMAND_INTEGRATION_PENDING — DEPENDS_ON_TRIP_SERVICE}.
 */
@Component
public class TripDemandAdapter {

    private static final Logger log = LoggerFactory.getLogger(TripDemandAdapter.class);

    /**
     * Contract method for fetching valid trip request count within a time window.
     *
     * @param serviceType service category
     * @param vehicleType vehicle category
     * @param regionId    region identifier
     * @param windowStart start of time window
     * @param windowEnd   end of time window
     * @return count of valid trip requests, or -1 if the dependency is not yet provisioned
     */
    public long fetchValidTripRequestCount(
            ServiceType serviceType,
            VehicleType vehicleType,
            String regionId,
            Instant windowStart,
            Instant windowEnd) {

        log.debug("Realtime demand query requested for service: {}, vehicle: {}, region: {} (Status: PENDING M05 integration)",
                serviceType, vehicleType, regionId);

        // When Trip Service is implemented at M05, cross-service queries or event aggregation will be wired here.
        return -1;
    }

    public boolean isProductionReady() {
        return false;
    }
}
