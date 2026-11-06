package com.rhl.payment.dtos;

import com.rhl.payment.enums.PaymentMethod;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request payload for initiating a payment obligation.
 * Client CANNOT specify amount or currency (Fail-Safe Authoritative Source Invariant).
 */
public record InitiatePaymentRequest(
        @NotNull(message = "tripId is required")
        UUID tripId,

        @NotNull(message = "paymentMethod is required")
        PaymentMethod paymentMethod
) {}
