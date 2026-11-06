package com.rhl.identity.dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SuspendDriverRequest(
        @NotBlank(message = "Suspension reason is required")
        @Size(max = 500, message = "Suspension reason must not exceed 500 characters")
        String reason
) {}
