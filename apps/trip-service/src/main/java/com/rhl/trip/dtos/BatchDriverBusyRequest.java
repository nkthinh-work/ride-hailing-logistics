package com.rhl.trip.dtos;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record BatchDriverBusyRequest(
        @NotNull(message = "driverIds list cannot be null")
        @Size(max = 100, message = "Batch size cannot exceed 100 drivers")
        List<UUID> driverIds
) {}
