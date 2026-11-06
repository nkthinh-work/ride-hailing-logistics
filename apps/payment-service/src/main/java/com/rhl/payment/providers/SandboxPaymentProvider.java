package com.rhl.payment.providers;

import com.rhl.payment.entities.PaymentAttempt;
import com.rhl.payment.entities.PaymentTransaction;
import com.rhl.payment.enums.PaymentAttemptStatus;
import com.rhl.payment.enums.PaymentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Baseline Sandbox provider implementation for DEV/TEST environments.
 * Does NOT mark payment as SUCCEEDED without real gateway callback/evidence.
 */
@Component
public class SandboxPaymentProvider implements PaymentGatewayProvider {

    private static final Logger log = LoggerFactory.getLogger(SandboxPaymentProvider.class);

    @Override
    public String getProviderName() {
        return "SANDBOX";
    }

    @Override
    public PaymentIntentResult createPaymentIntent(PaymentTransaction transaction, PaymentAttempt attempt) {
        String mockReference = "sandbox-ref-" + UUID.randomUUID().toString().substring(0, 8);
        String checkoutUrl = "https://sandbox-gateway.rhl.internal/pay?order=" + attempt.getProviderOrderId();
        log.info("Initialized sandbox payment intent for transaction {} attempt {} with ref {}",
                transaction.getId(), attempt.getProviderOrderId(), mockReference);

        return new PaymentIntentResult(
                attempt.getProviderOrderId(),
                checkoutUrl,
                mockReference,
                PaymentAttemptStatus.PROCESSING,
                "Payment intent initialized with sandbox provider"
        );
    }

    @Override
    public PaymentWebhookResult verifyAndParseWebhook(String rawPayload, Map<String, String> headers) {
        log.info("Processing sandbox webhook callback");
        return new PaymentWebhookResult(
                getProviderName(),
                "unknown",
                null,
                null,
                "VND",
                false,
                "SANDBOX_NOT_SUPPORTED",
                "Sandbox provider does not process live webhooks directly",
                rawPayload
        );
    }

    @Override
    public ProviderQueryResult queryTransaction(String providerOrderId) {
        log.info("Querying sandbox transaction status for orderId {}", providerOrderId);
        return new ProviderQueryResult(
                providerOrderId,
                null,
                PaymentAttemptStatus.PROCESSING,
                "PENDING_CAPTURE",
                "Sandbox transaction is pending customer payment"
        );
    }

    @Override
    public PaymentProviderResult processPayment(PaymentTransaction transaction) {
        String mockReference = "sandbox-ref-" + UUID.randomUUID().toString().substring(0, 8);
        log.info("Initialized sandbox payment intent for transaction {} with ref {}", transaction.getId(), mockReference);
        return new PaymentProviderResult(
                mockReference,
                PaymentStatus.PROCESSING,
                "Payment intent initialized with sandbox provider"
        );
    }

    @Override
    public Optional<PaymentProviderResult> queryPaymentStatus(String providerReference) {
        return Optional.of(new PaymentProviderResult(
                providerReference,
                PaymentStatus.PROCESSING,
                "Payment is pending capture"
        ));
    }
}
