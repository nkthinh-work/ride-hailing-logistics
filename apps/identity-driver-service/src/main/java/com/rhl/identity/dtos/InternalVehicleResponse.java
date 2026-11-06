package com.rhl.identity.dtos;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

public record InternalVehicleResponse(
        @JsonAlias({"id", "vehicleId"})
        @JsonProperty("id")
        UUID id,
        UUID driverProfileId,
        String type,
        String plate,
        String make,
        String model,
        String color,
        String status
) {
    @JsonProperty("vehicleId")
    public UUID vehicleId() {
        return id;
    }
}
