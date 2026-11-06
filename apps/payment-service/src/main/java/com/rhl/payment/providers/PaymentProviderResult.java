package com.rhl.payment.providers;

import com.rhl.payment.enums.PaymentStatus;

public record PaymentProviderResult(
        String providerReference,
        PaymentStatus targetStatus,
        String message
) {}
