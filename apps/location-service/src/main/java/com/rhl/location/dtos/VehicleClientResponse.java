package com.rhl.location.dtos;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

public record VehicleClientResponse(
        @JsonAlias({"id", "vehicleId"})
        @JsonProperty("vehicleId")
        UUID vehicleId,
        UUID driverProfileId,
        String type,
        String plate,
        String make,
        String model,
        String color,
        String status
) {
}
