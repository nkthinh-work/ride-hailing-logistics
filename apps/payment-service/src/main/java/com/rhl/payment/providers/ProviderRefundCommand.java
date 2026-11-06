package com.rhl.payment.providers;

import java.math.BigDecimal;

public record ProviderRefundCommand(
        String providerRefundOrderId,
        String originalProviderTransactionId,
        String originalProviderOrderId,
        BigDecimal amount,
        String currency,
        String description
) {}
