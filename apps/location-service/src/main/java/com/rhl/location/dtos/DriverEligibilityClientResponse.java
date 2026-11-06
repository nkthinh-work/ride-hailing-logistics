package com.rhl.location.dtos;

import java.util.List;
import java.util.UUID;

public record DriverEligibilityClientResponse(
        UUID userId,
        boolean eligible,
        String accountStatus,
        String applicationStatus,
        boolean hasDriverRole,
        List<String> reasons
) {
}
