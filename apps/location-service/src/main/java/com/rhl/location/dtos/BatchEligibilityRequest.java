package com.rhl.location.dtos;

import java.util.List;
import java.util.UUID;

public record BatchEligibilityRequest(
        List<UUID> userIds
) {
}
