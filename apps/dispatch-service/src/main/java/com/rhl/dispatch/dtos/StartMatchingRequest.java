package com.rhl.dispatch.dtos;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record StartMatchingRequest(
        @NotNull(message = "tripId is required")
        UUID tripId
) {}
