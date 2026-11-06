package com.rhl.trip.dtos;

import java.util.UUID;

public record DriverBusyResponse(
        UUID driverId,
        boolean busy
) {}
