package com.rhl.identity.dtos;

import com.rhl.identity.entities.enums.DriverApplicationStatus;

import java.util.List;
import java.util.UUID;

public record DriverEligibilityResponse(
        UUID userId,
        boolean eligible,
        String accountStatus,
        DriverApplicationStatus applicationStatus,
        boolean hasDriverRole,
        List<String> reasons
) {
}
