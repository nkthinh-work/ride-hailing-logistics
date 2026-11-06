package com.rhl.dispatch.providers;

import com.rhl.dispatch.models.VehicleType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SPI for verifying driver eligibility and vehicle qualifications with Identity Service (FR-MAT-002, FR-DRV-008).
 */
public interface DriverEligibilityProvider {

    /**
     * Retrieves eligibility details for a single driver.
     *
     * @param driverId driver user ID
     * @return eligibility info including active vehicles
     */
    DriverEligibilityInfo getEligibility(UUID driverId);

    /**
     * Batch retrieves eligibility details for multiple drivers to avoid N+1 calls.
     *
     * @param driverIds list of candidate driver user IDs
     * @return map of driver ID to eligibility info
     */
    Map<UUID, DriverEligibilityInfo> getBatchEligibility(List<UUID> driverIds);

    record DriverEligibilityInfo(
            UUID userId,
            boolean eligible,
            String accountStatus,
            String applicationStatus,
            boolean hasDriverRole,
            List<String> reasons,
            List<VehicleSummary> activeVehicles
    ) {
        public boolean isSuspended() {
            return "SUSPENDED".equalsIgnoreCase(applicationStatus);
        }

        public boolean isApproved() {
            return "APPROVED".equalsIgnoreCase(applicationStatus);
        }

        public boolean isAccountActive() {
            return "ACTIVE".equalsIgnoreCase(accountStatus);
        }
    }

    record VehicleSummary(
            UUID vehicleId,
            VehicleType type,
            String plate,
            String status
    ) {
        public boolean isActive() {
            return "ACTIVE".equalsIgnoreCase(status);
        }
    }
}
