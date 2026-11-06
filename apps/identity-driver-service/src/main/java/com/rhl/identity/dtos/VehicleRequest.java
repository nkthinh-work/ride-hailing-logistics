package com.rhl.identity.dtos;

import com.rhl.identity.entities.enums.VehicleType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record VehicleRequest(
        @NotNull(message = "Vehicle type is required")
        VehicleType type,

        @NotBlank(message = "Vehicle plate is required")
        @Size(max = 20, message = "Vehicle plate must not exceed 20 characters")
        String plate,

        @NotBlank(message = "Vehicle make is required")
        @Size(max = 50, message = "Vehicle make must not exceed 50 characters")
        String make,

        @NotBlank(message = "Vehicle model is required")
        @Size(max = 50, message = "Vehicle model must not exceed 50 characters")
        String model,

        @NotBlank(message = "Vehicle color is required")
        @Size(max = 30, message = "Vehicle color must not exceed 30 characters")
        String color
) {
}
