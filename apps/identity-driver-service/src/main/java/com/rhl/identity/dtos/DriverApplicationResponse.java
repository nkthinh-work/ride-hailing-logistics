
package com.rhl.identity.dtos;
import com.rhl.identity.entities.enums.DriverApplicationStatus;

import java.time.Instant;
import java.util.UUID;

public record DriverApplicationResponse(
        UUID id,
        UUID userId,
        String fullName,
        String phone,
        String driverLicenseNumber,
        String vehiclePlate,
        DriverApplicationStatus status,
        String rejectionReason,
        Instant createdAt,
        Instant reviewedAt
) {
}
