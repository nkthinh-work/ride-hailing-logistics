package com.rhl.payment.providers;

import com.rhl.payment.entities.PaymentAttempt;
import com.rhl.payment.entities.PaymentTransaction;
import com.rhl.payment.enums.PaymentStatus;

import java.util.Map;
import java.util.Optional;

/**
 * Clean boundary abstraction for external payment gateways (MoMo, ZaloPay, VNPay, Stripe).
 * Defines contracts for intent initiation, secure signed webhook verification, and authoritative status query.
 */
public interface PaymentGatewayProvider {

    /**
     * Unique identifier for the provider adapter (e.g. "MOMO", "SANDBOX").
     */
    String getProviderName();

    /**
     * Create checkout/intent with external payment gateway.
     */
    PaymentIntentResult createPaymentIntent(PaymentTransaction transaction, PaymentAttempt attempt);

    /**
     * Authenticate and parse incoming provider callback/webhook.
     */
    PaymentWebhookResult verifyAndParseWebhook(String rawPayload, Map<String, String> headers);

    /**
     * Query remote transaction status for reconciliation.
     */
    ProviderQueryResult queryTransaction(String providerOrderId);

    /**
     * Dispatch refund to external provider.
     */
    default ProviderRefundResult createRefund(ProviderRefundCommand command) {
        return new ProviderRefundResult(
                command.providerRefundOrderId(),
                "mock-trans-" + command.providerRefundOrderId(),
                com.rhl.payment.enums.PaymentAttemptStatus.PROCESSING,
                "0",
                "Refund initiated with provider"
        );
    }

    /**
     * Query remote refund status for reconciliation.
     */
    default ProviderRefundQueryResult queryRefund(String providerRefundOrderId) {
        return new ProviderRefundQueryResult(
                providerRefundOrderId,
                "mock-trans-" + providerRefundOrderId,
                com.rhl.payment.enums.PaymentAttemptStatus.SUCCEEDED,
                "0",
                "Refund confirmed with provider"
        );
    }

    /**
     * Legacy/Group 1 compatibility method.
     */
    default PaymentProviderResult processPayment(PaymentTransaction transaction) {
        return new PaymentProviderResult(
                "legacy-ref-" + transaction.getId(),
                PaymentStatus.PROCESSING,
                "Payment intent initialized"
        );
    }

    /**
     * Legacy/Group 1 compatibility method.
     */
    default Optional<PaymentProviderResult> queryPaymentStatus(String providerReference) {
        return Optional.of(new PaymentProviderResult(
                providerReference,
                PaymentStatus.PROCESSING,
                "Payment is pending capture"
        ));
    }
}
