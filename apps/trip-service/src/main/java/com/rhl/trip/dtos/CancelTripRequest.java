package com.rhl.trip.dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload for cancelling a trip (M05 Group 3, FR-CAN-001..007).
 */
public record CancelTripRequest(
        @NotBlank(message = "reasonCode is required")
        @Size(max = 64, message = "reasonCode must not exceed 64 characters")
        String reasonCode,

        @Size(max = 255, message = "description must not exceed 255 characters")
        String description
) {
    public CancelTripRequest(String reasonCode) {
        this(reasonCode, null);
    }
}
