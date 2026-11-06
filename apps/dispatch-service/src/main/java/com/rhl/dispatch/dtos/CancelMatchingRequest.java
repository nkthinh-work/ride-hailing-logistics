package com.rhl.dispatch.dtos;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CancelMatchingRequest(
        @NotNull(message = "tripId is required")
        UUID tripId,
        String reason
) {}
