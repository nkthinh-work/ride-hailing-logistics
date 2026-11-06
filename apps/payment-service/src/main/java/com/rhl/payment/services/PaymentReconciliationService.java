package com.rhl.payment.services;

import com.rhl.payment.dtos.PaymentResponse;
import com.rhl.payment.entities.PaymentAttempt;
import com.rhl.payment.entities.PaymentOutboxEvent;
import com.rhl.payment.entities.PaymentReconciliationRecord;
import com.rhl.payment.entities.PaymentTransaction;
import com.rhl.payment.enums.PaymentAttemptStatus;
import com.rhl.payment.enums.PaymentStatus;
import com.rhl.payment.enums.ReconciliationMismatchType;
import com.rhl.payment.enums.ReconciliationResolutionState;
import com.rhl.payment.events.PaymentEventPayload;
import com.rhl.payment.events.ReconciliationFlaggedPayload;
import com.rhl.payment.exceptions.PaymentNotFoundException;
import com.rhl.payment.providers.PaymentGatewayProvider;
import com.rhl.payment.providers.ProviderQueryResult;
import com.rhl.payment.repositories.PaymentAttemptRepository;
import com.rhl.payment.repositories.PaymentOutboxRepository;
import com.rhl.payment.repositories.PaymentReconciliationRepository;
import com.rhl.payment.repositories.PaymentTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable Payment Reconciliation Service (SRS Section 4.8.1, FR-PAY-004..006).
 * Periodically detects pending/unknown payment attempts, executes authoritative status queries
 * against external payment gateways without holding open DB transactions, and atomically reconciles
 * local state while logging any financial mismatches.
 */
@Service
public class PaymentReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationService.class);

    private final PaymentAttemptRepository attemptRepository;
    private final PaymentTransactionRepository paymentRepository;
    private final PaymentReconciliationRepository reconciliationRepository;
    private final PaymentOutboxRepository outboxRepository;
    private final List<PaymentGatewayProvider> availableProviders;
    private final PaymentGatewayProvider defaultProvider;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final boolean enabled;
    private final Duration staleThreshold;

    @Autowired
    public PaymentReconciliationService(
            PaymentAttemptRepository attemptRepository,
            PaymentTransactionRepository paymentRepository,
            PaymentReconciliationRepository reconciliationRepository,
            PaymentOutboxRepository outboxRepository,
            List<PaymentGatewayProvider> availableProviders,
            PaymentGatewayProvider defaultProvider,
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${payment.reconciliation.enabled:true}") boolean enabled,
            @Value("${payment.reconciliation.stale-threshold-seconds:15}") long staleThresholdSeconds
    ) {
        this.attemptRepository = Objects.requireNonNull(attemptRepository, "attemptRepository is required");
        this.paymentRepository = Objects.requireNonNull(paymentRepository, "paymentRepository is required");
        this.reconciliationRepository = Objects.requireNonNull(reconciliationRepository, "reconciliationRepository is required");
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository is required");
        this.availableProviders = availableProviders != null ? availableProviders : List.of();
        this.defaultProvider = defaultProvider;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.enabled = enabled;
        this.staleThreshold = Duration.ofSeconds(Math.max(1, staleThresholdSeconds));
    }

    @Scheduled(fixedDelayString = "${payment.reconciliation.poll-interval-ms:5000}")
    public void scheduledReconciliation() {
        if (!enabled) {
            return;
        }
        reconcileStalePendingAttempts();
    }

    public int reconcileStalePendingAttempts() {
        Instant cutoff = clock.instant().minus(staleThreshold);
        List<PaymentAttempt> candidates = attemptRepository.findByStatusInAndCreatedAtBefore(
                List.of(PaymentAttemptStatus.PROCESSING, PaymentAttemptStatus.UNKNOWN),
                cutoff
        );

        if (candidates.isEmpty()) {
            return 0;
        }

        int reconciledCount = 0;
        for (PaymentAttempt attempt : candidates) {
            try {
                // Query provider outside database transaction to avoid holding pool connections
                PaymentGatewayProvider provider = resolveProvider(attempt.getProvider());
                ProviderQueryResult queryResult = provider.queryTransaction(attempt.getProviderOrderId());

                // Apply result atomically in local database transaction
                applyAuthoritativeQueryResult(attempt.getId(), queryResult, provider.getProviderName());
                reconciledCount++;
            } catch (Exception ex) {
                log.warn("Error reconciling attempt {}: {}", attempt.getId(), ex.getMessage());
            }
        }
        return reconciledCount;
    }

    /**
     * Public manual or on-demand reconciliation endpoint.
     */
    public PaymentResponse reconcileAttempt(UUID attemptId) {
        PaymentAttempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new PaymentNotFoundException("Payment attempt " + attemptId + " not found"));

        if (attempt.getStatus() == PaymentAttemptStatus.SUCCEEDED) {
            PaymentTransaction payment = paymentRepository.findById(attempt.getPaymentId())
                    .orElseThrow(() -> new PaymentNotFoundException("Payment not found"));
            return PaymentResponse.from(payment, attempt.getCheckoutUrl(), List.of(attempt));
        }

        PaymentGatewayProvider provider = resolveProvider(attempt.getProvider());
        ProviderQueryResult queryResult = provider.queryTransaction(attempt.getProviderOrderId());

        applyAuthoritativeQueryResult(attempt.getId(), queryResult, provider.getProviderName());

        PaymentTransaction updatedPayment = paymentRepository.findById(attempt.getPaymentId())
                .orElseThrow(() -> new PaymentNotFoundException("Payment not found"));
        PaymentAttempt updatedAttempt = attemptRepository.findById(attemptId)
                .orElse(attempt);

        return PaymentResponse.from(updatedPayment, updatedAttempt.getCheckoutUrl(), List.of(updatedAttempt));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void applyAuthoritativeQueryResult(UUID attemptId, ProviderQueryResult queryResult, String providerName) {
        PaymentAttempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new PaymentNotFoundException("Attempt not found: " + attemptId));

        PaymentTransaction payment = paymentRepository.findById(attempt.getPaymentId())
                .orElseThrow(() -> new PaymentNotFoundException("Payment not found: " + attempt.getPaymentId()));

        Instant now = clock.instant();

        // If attempt is already SUCCEEDED, skip redundant reconciliation
        if (attempt.getStatus() == PaymentAttemptStatus.SUCCEEDED) {
            return;
        }

        if (queryResult.status() == PaymentAttemptStatus.SUCCEEDED) {
            attempt.markSucceeded(queryResult.providerTransactionId(), now);

            if (payment.getStatus() == PaymentStatus.FAILED) {
                // CRITICAL FINANCIAL MISMATCH: Provider succeeded, but local payment is marked FAILED!
                log.error("CRITICAL DISCREPANCY: Provider {} confirmed order {} SUCCEEDED, but local Payment {} is FAILED!",
                        providerName, attempt.getProviderOrderId(), payment.getId());

                PaymentReconciliationRecord record = new PaymentReconciliationRecord(
                        payment.getId(),
                        providerName,
                        attempt.getProviderOrderId(),
                        ReconciliationMismatchType.LOCAL_FAILED_PROVIDER_SUCCESS,
                        payment.getStatus().name(),
                        "SUCCEEDED",
                        payment.getAmount(),
                        attempt.getAmount(),
                        ReconciliationResolutionState.FLAGGED_MANUAL_REVIEW,
                        "Discrepancy: Provider confirmed payment success after local transaction was marked FAILED. Manual resolution required.",
                        now
                );
                reconciliationRepository.save(record);

                // Publish outbox event for finance investigation
                try {
                    ReconciliationFlaggedPayload payload = new ReconciliationFlaggedPayload(
                            record.getId(),
                            payment.getId(),
                            providerName,
                            record.getMismatchType().name(),
                            payment.getStatus().name(),
                            "SUCCEEDED",
                            payment.getAmount(),
                            attempt.getAmount(),
                            record.getDetails(),
                            now
                    );
                    PaymentOutboxEvent outboxEvent = new PaymentOutboxEvent(
                            UUID.randomUUID(),
                            "PAYMENT_RECONCILIATION_FLAGGED",
                            "PAYMENT",
                            payment.getId(),
                            attempt.getProviderOrderId(),
                            payment.getCustomerId(),
                            objectMapper.writeValueAsString(payload),
                            now
                    );
                    outboxRepository.save(outboxEvent);
                } catch (Exception ex) {
                    log.error("Failed to serialize reconciliation flagged outbox event: {}", ex.getMessage());
                }
            } else if (payment.getStatus() != PaymentStatus.SUCCEEDED) {
                payment.markSucceeded(queryResult.providerTransactionId(), providerName, "SYSTEM_RECONCILER", now);

                // Publish outbox event PAYMENT_SUCCEEDED atomically
                try {
                    PaymentEventPayload payload = new PaymentEventPayload(
                            payment.getId(),
                            payment.getTripId(),
                            payment.getCustomerId(),
                            payment.getAmount(),
                            payment.getCurrency(),
                            payment.getPaymentMethod().name(),
                            payment.getStatus().name(),
                            providerName,
                            queryResult.providerTransactionId(),
                            now
                    );
                    PaymentOutboxEvent outboxEvent = new PaymentOutboxEvent(
                            UUID.randomUUID(),
                            "PAYMENT_SUCCEEDED",
                            "PAYMENT",
                            payment.getId(),
                            attempt.getProviderOrderId(),
                            payment.getCustomerId(),
                            objectMapper.writeValueAsString(payload),
                            now
                    );
                    outboxRepository.save(outboxEvent);
                } catch (Exception ex) {
                    log.error("Failed to serialize payment succeeded outbox event: {}", ex.getMessage());
                }
            }
        } else if (queryResult.status() == PaymentAttemptStatus.FAILED) {
            if (payment.getStatus() == PaymentStatus.SUCCEEDED) {
                // Provider reports FAILED but local is SUCCEEDED (terminal invariant: do not rollback!)
                log.warn("Mismatch: Provider {} reported FAILED for order {}, but local payment is already SUCCEEDED",
                        providerName, attempt.getProviderOrderId());
                PaymentReconciliationRecord record = new PaymentReconciliationRecord(
                        payment.getId(),
                        providerName,
                        attempt.getProviderOrderId(),
                        ReconciliationMismatchType.LOCAL_SUCCESS_PROVIDER_REFUNDED,
                        payment.getStatus().name(),
                        "FAILED",
                        payment.getAmount(),
                        attempt.getAmount(),
                        ReconciliationResolutionState.RESOLVED_AUTOMATICALLY,
                        "Provider reported failure after local success. Terminal invariant preserved.",
                        now
                );
                reconciliationRepository.save(record);
            } else if (!payment.getStatus().isTerminal()) {
                attempt.markFailed(queryResult.resultCode(), queryResult.message(), now);
                payment.markFailed("Provider query confirmed failure: " + queryResult.message(),
                        providerName, "SYSTEM_RECONCILER", now);

                // Publish outbox event PAYMENT_FAILED atomically
                try {
                    PaymentEventPayload payload = new PaymentEventPayload(
                            payment.getId(),
                            payment.getTripId(),
                            payment.getCustomerId(),
                            payment.getAmount(),
                            payment.getCurrency(),
                            payment.getPaymentMethod().name(),
                            payment.getStatus().name(),
                            providerName,
                            null,
                            now
                    );
                    PaymentOutboxEvent outboxEvent = new PaymentOutboxEvent(
                            UUID.randomUUID(),
                            "PAYMENT_FAILED",
                            "PAYMENT",
                            payment.getId(),
                            attempt.getProviderOrderId(),
                            payment.getCustomerId(),
                            objectMapper.writeValueAsString(payload),
                            now
                    );
                    outboxRepository.save(outboxEvent);
                } catch (Exception ex) {
                    log.error("Failed to serialize payment failed outbox event: {}", ex.getMessage());
                }
            }
        } else {
            // UNKNOWN or PROCESSING: Leave in active/uncertain state, do NOT mark FAILED!
            attempt.markUnknown(queryResult.message(), now);
            log.info("Attempt {} provider query remains uncertain: code={} message={}",
                    attemptId, queryResult.resultCode(), queryResult.message());
        }

        attemptRepository.save(attempt);
        paymentRepository.save(payment);
    }

    private PaymentGatewayProvider resolveProvider(String providerName) {
        if (providerName != null && availableProviders != null) {
            for (PaymentGatewayProvider p : availableProviders) {
                if (p.getProviderName().equalsIgnoreCase(providerName)) {
                    return p;
                }
            }
        }
        return defaultProvider;
    }
}
