package com.rhl.dispatch.dtos;

import jakarta.validation.constraints.Size;

public record RejectOfferRequest(
        @Size(max = 500, message = "Reason cannot exceed 500 characters")
        String reason
) {
    public RejectOfferRequest() {
        this(null);
    }
}
