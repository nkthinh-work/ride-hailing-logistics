package com.rhl.identity.dtos;

import java.util.List;
import java.util.UUID;

public record BatchDriverEligibilityRequest(
        List<UUID> userIds
) {
}
