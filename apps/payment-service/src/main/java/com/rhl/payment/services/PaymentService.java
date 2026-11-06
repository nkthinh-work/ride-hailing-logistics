package com.rhl.payment.services;

import com.rhl.payment.dtos.InitiatePaymentRequest;
import com.rhl.payment.dtos.PaymentResponse;
import com.rhl.payment.dtos.TripInternalResponse;
import com.rhl.payment.entities.PaymentAttempt;
import com.rhl.payment.entities.PaymentTransaction;
import com.rhl.payment.enums.PaymentAttemptStatus;
import com.rhl.payment.enums.PaymentMethod;
import com.rhl.payment.enums.PaymentStatus;
import com.rhl.payment.exceptions.IdempotencyConflictException;
import com.rhl.payment.exceptions.InvalidPaymentAmountException;
import com.rhl.payment.exceptions.InvalidPaymentStateTransitionException;
import com.rhl.payment.exceptions.PaymentAccessDeniedException;
import com.rhl.payment.exceptions.PaymentNotFoundException;
import com.rhl.payment.exceptions.PaymentSecurityException;
import com.rhl.payment.exceptions.TripNotPayableException;
import com.rhl.payment.providers.PaymentGatewayProvider;
import com.rhl.payment.providers.PaymentIntentResult;
import com.rhl.payment.providers.PaymentProviderResult;
import com.rhl.payment.providers.PaymentWebhookResult;
import com.rhl.payment.providers.ProviderQueryResult;
import com.rhl.payment.repositories.PaymentAttemptRepository;
import com.rhl.payment.repositories.PaymentTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Core Payment Domain Service orchestrating payment initiation, authoritative amount verification,
 * state machine transitions, idempotency checks, cash collection confirmation, signed webhook processing,
 * and financial audit recording.
 */
import com.rhl.payment.entities.PaymentOutboxEvent;
import com.rhl.payment.repositories.PaymentOutboxRepository;
import tools.jackson.databind.ObjectMapper;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentTransactionRepository paymentRepository;
    private final PaymentAttemptRepository attemptRepository;
    private final TripServiceClient tripServiceClient;
    private final PaymentGatewayProvider defaultPaymentGatewayProvider;
    private final List<PaymentGatewayProvider> availableProviders;
    private final PaymentOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Autowired
    public PaymentService(
            PaymentTransactionRepository paymentRepository,
            PaymentAttemptRepository attemptRepository,
            TripServiceClient tripServiceClient,
            PaymentGatewayProvider defaultPaymentGatewayProvider,
            List<PaymentGatewayProvider> availableProviders,
            @Autowired(required = false) PaymentOutboxRepository outboxRepository,
            @Autowired(required = false) ObjectMapper objectMapper) {
        this.paymentRepository = paymentRepository;
        this.attemptRepository = attemptRepository;
        this.tripServiceClient = tripServiceClient;
        this.defaultPaymentGatewayProvider = defaultPaymentGatewayProvider;
        this.availableProviders = availableProviders != null ? availableProviders : List.of();
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public PaymentService(
            PaymentTransactionRepository paymentRepository,
            PaymentAttemptRepository attemptRepository,
            TripServiceClient tripServiceClient,
            PaymentGatewayProvider defaultPaymentGatewayProvider,
            List<PaymentGatewayProvider> availableProviders) {
        this(paymentRepository, attemptRepository, tripServiceClient, defaultPaymentGatewayProvider, availableProviders, null, null);
    }

    /**
     * Backwards-compatible constructor for existing tests.
     */
    public PaymentService(
            PaymentTransactionRepository paymentRepository,
            TripServiceClient tripServiceClient,
            PaymentGatewayProvider paymentGatewayProvider) {
        this(paymentRepository, null, tripServiceClient, paymentGatewayProvider, List.of(paymentGatewayProvider));
    }

    /**
     * Initiates a payment obligation for a trip with strict idempotency and authoritative amount verification.
     */
    @Transactional
    public PaymentResponse initiatePayment(
            UUID authenticatedCustomerId,
            String idempotencyKey,
            InitiatePaymentRequest request) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required");
        }

        // 1. Idempotency Check: Customer + IdempotencyKey
        Optional<PaymentTransaction> existingOpt = paymentRepository
                .findByCustomerIdAndIdempotencyKey(authenticatedCustomerId, idempotencyKey);

        if (existingOpt.isPresent()) {
            PaymentTransaction existing = existingOpt.get();
            // Verify payload fingerprint
            if (existing.getTripId().equals(request.tripId()) &&
                    existing.getPaymentMethod().equals(request.paymentMethod())) {
                log.info("Idempotent replay detected for customer {} and key {}", authenticatedCustomerId, idempotencyKey);
                List<PaymentAttempt> attempts = (attemptRepository != null)
                        ? attemptRepository.findByPaymentIdOrderByAttemptNumberAsc(existing.getId())
                        : List.of();
                String checkoutUrl = attempts.stream()
                        .filter(a -> a.getCheckoutUrl() != null)
                        .findFirst()
                        .map(PaymentAttempt::getCheckoutUrl)
                        .orElse(null);
                return PaymentResponse.from(existing, checkoutUrl, attempts);
            } else {
                log.warn("Idempotency conflict for customer {}: key {} with differing payload", authenticatedCustomerId, idempotencyKey);
                throw new IdempotencyConflictException("Idempotency key has already been used with different parameters");
            }
        }

        // 2. Double Charging Prevention: Has this trip already been paid successfully?
        if (paymentRepository.existsByTripIdAndStatus(request.tripId(), PaymentStatus.SUCCEEDED)) {
            log.warn("Trip {} has already been paid successfully", request.tripId());
            throw new TripNotPayableException("Trip " + request.tripId() + " has already been paid successfully");
        }

        // 2.1 Defense-in-depth: Prevent concurrent active charge attempts for the same trip
        if (paymentRepository.existsByTripIdAndStatusIn(request.tripId(), List.of(PaymentStatus.PENDING, PaymentStatus.PROCESSING))) {
            log.warn("Trip {} already has a payment transaction currently in PENDING or PROCESSING", request.tripId());
            throw new TripNotPayableException("Trip " + request.tripId() + " already has an active payment in progress");
        }

        // 3. Authoritative Trip Verification via internal Trip Service contract
        TripInternalResponse trip = tripServiceClient.getTrip(request.tripId())
                .orElseThrow(() -> new PaymentNotFoundException("Trip " + request.tripId() + " not found"));

        // Verify Customer Ownership (FR-PAY-002, Security)
        if (!trip.customerId().equals(authenticatedCustomerId)) {
            log.warn("Security violation: customer {} attempted to initiate payment for trip {} owned by {}",
                    authenticatedCustomerId, request.tripId(), trip.customerId());
            throw new PaymentAccessDeniedException("Customer does not own trip " + request.tripId());
        }

        // Verify Trip Status and Determine Authoritative Payable Amount
        BigDecimal payableAmount;
        String status = trip.status();

        if ("COMPLETED".equalsIgnoreCase(status)) {
            payableAmount = trip.estimatedFare();
            if (payableAmount == null || payableAmount.compareTo(BigDecimal.ZERO) <= 0) {
                throw new InvalidPaymentAmountException("Trip completed but has non-positive authoritative fare: " + payableAmount);
            }
        } else if ("CANCELLED".equalsIgnoreCase(status)) {
            payableAmount = trip.cancellationFee();
            if (payableAmount == null || payableAmount.compareTo(BigDecimal.ZERO) <= 0) {
                throw new TripNotPayableException("Cancelled trip has no payable cancellation fee (policy pending)");
            }
        } else {
            throw new TripNotPayableException("Trip in state '" + status + "' is not eligible for payment");
        }

        String currency = (trip.currency() != null && !trip.currency().isBlank()) ? trip.currency() : "VND";

        // 4. Construct Aggregate Root (Pending state with initial creation audit)
        Instant now = Instant.now();
        PaymentTransaction transaction = PaymentTransaction.builder()
                .tripId(trip.tripId())
                .customerId(authenticatedCustomerId)
                .amount(payableAmount)
                .currency(currency)
                .paymentMethod(request.paymentMethod())
                .idempotencyKey(idempotencyKey)
                .now(now)
                .initialActor(authenticatedCustomerId.toString(), "CUSTOMER")
                .build();

        // 5. Branch by Payment Method
        if (request.paymentMethod() == PaymentMethod.CASH) {
            transaction.startProcessing(authenticatedCustomerId.toString(), "CUSTOMER",
                    "Cash collection pending upon trip settlement by assigned driver", now);

            try {
                PaymentTransaction saved = paymentRepository.save(transaction);
                log.info("Created CASH payment transaction {} for trip {} amount {} {}",
                        saved.getId(), saved.getTripId(), saved.getAmount(), saved.getCurrency());
                return PaymentResponse.from(saved);
            } catch (DataIntegrityViolationException e) {
                log.warn("Concurrent payment attempt conflict for customer {} key {} or trip {}: {}",
                        authenticatedCustomerId, idempotencyKey, request.tripId(), e.getMessage());
                Optional<PaymentTransaction> concurrent = paymentRepository
                        .findByCustomerIdAndIdempotencyKey(authenticatedCustomerId, idempotencyKey);
                if (concurrent.isPresent()) {
                    return PaymentResponse.from(concurrent.get());
                }
                throw new IdempotencyConflictException("Concurrent payment transaction already in progress");
            }
        }

        // Digital Payment: Save transaction first with PENDING state
        PaymentTransaction savedTransaction;
        try {
            savedTransaction = paymentRepository.save(transaction);
        } catch (DataIntegrityViolationException e) {
            log.warn("Concurrent payment conflict for customer {} key {}: {}", authenticatedCustomerId, idempotencyKey, e.getMessage());
            Optional<PaymentTransaction> concurrent = paymentRepository
                    .findByCustomerIdAndIdempotencyKey(authenticatedCustomerId, idempotencyKey);
            if (concurrent.isPresent()) {
                return PaymentResponse.from(concurrent.get());
            }
            throw new IdempotencyConflictException("Concurrent payment transaction already in progress");
        }

        // 6. Select provider and initialize PaymentAttempt
        PaymentGatewayProvider provider = resolveProvider(request.paymentMethod());
        String providerName = (provider != null && provider.getProviderName() != null)
                ? provider.getProviderName() : "SANDBOX";
        String providerOrderId = "RHL-" + savedTransaction.getId().toString().substring(0, 8) + "-" + System.currentTimeMillis();

        PaymentAttempt attempt = null;
        if (attemptRepository != null) {
            attempt = PaymentAttempt.builder()
                    .paymentId(savedTransaction.getId())
                    .attemptNumber(1)
                    .provider(providerName)
                    .providerOrderId(providerOrderId)
                    .amount(savedTransaction.getAmount())
                    .currency(savedTransaction.getCurrency())
                    .idempotencyReference(idempotencyKey)
                    .status(PaymentAttemptStatus.PENDING)
                    .now(now)
                    .build();
            attempt = attemptRepository.save(attempt);
        }

        // 7. Call Provider to create checkout intent
        PaymentIntentResult intentResult;
        try {
            intentResult = provider.createPaymentIntent(savedTransaction, attempt != null ? attempt :
                    PaymentAttempt.builder()
                            .paymentId(savedTransaction.getId())
                            .attemptNumber(1)
                            .provider(providerName)
                            .providerOrderId(providerOrderId)
                            .amount(savedTransaction.getAmount())
                            .currency(savedTransaction.getCurrency())
                            .idempotencyReference(idempotencyKey)
                            .build()
            );
            // Fallback for mocks/providers that only implement legacy processPayment
            if (intentResult == null) {
                PaymentProviderResult legacyResult = provider.processPayment(savedTransaction);
                String ref = legacyResult != null ? legacyResult.providerReference() : providerOrderId;
                intentResult = new PaymentIntentResult(
                        providerOrderId,
                        null,
                        ref,
                        PaymentAttemptStatus.PROCESSING,
                        legacyResult != null ? legacyResult.message() : "Intent initialized"
                );
            }
        } catch (Exception e) {
            log.error("Provider createPaymentIntent threw exception: {}", e.getMessage());
            intentResult = new PaymentIntentResult(
                    providerOrderId,
                    null,
                    providerOrderId,
                    PaymentAttemptStatus.UNKNOWN,
                    "Provider intent call failed: " + e.getMessage()
            );
        }

        // 8. Update statuses based on provider intent result
        String checkoutUrl = intentResult.checkoutUrl();
        if (intentResult.status() == PaymentAttemptStatus.PROCESSING) {
            if (attempt != null) {
                attempt.markProcessing(checkoutUrl, now);
                attemptRepository.save(attempt);
            }
            savedTransaction.startProcessing(authenticatedCustomerId.toString(), "CUSTOMER",
                    "Dispatched to provider " + provider.getProviderName() + ": " + providerOrderId, now);
        } else if (intentResult.status() == PaymentAttemptStatus.UNKNOWN) {
            if (attempt != null) {
                attempt.markUnknown(intentResult.message(), now);
                attemptRepository.save(attempt);
            }
            savedTransaction.startProcessing(authenticatedCustomerId.toString(), "CUSTOMER",
                    "Provider intent uncertain: " + intentResult.message(), now);
        } else {
            if (attempt != null) {
                attempt.markFailed("INTENT_DECLINED", intentResult.message(), now);
                attemptRepository.save(attempt);
            }
            savedTransaction.markFailed("Provider declined intent: " + intentResult.message(),
                    provider.getProviderName(), "PROVIDER", now);
        }

        PaymentTransaction updated = paymentRepository.save(savedTransaction);
        List<PaymentAttempt> attempts = (attemptRepository != null && attempt != null) ? List.of(attempt) : List.of();
        return PaymentResponse.from(updated, checkoutUrl, attempts);
    }

    /**
     * Driver confirms cash collection upon trip completion (FR-PAY-007, Cash Flow).
     */
    @Transactional
    public PaymentResponse confirmCashPayment(UUID authenticatedDriverId, UUID paymentId) {
        if (authenticatedDriverId == null) {
            throw new PaymentAccessDeniedException("Driver must be authenticated");
        }

        PaymentTransaction payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException("Payment " + paymentId + " not found"));

        if (payment.getPaymentMethod() != PaymentMethod.CASH) {
            throw new InvalidPaymentStateTransitionException(payment.getStatus(), PaymentStatus.SUCCEEDED);
        }

        // Idempotency: If already confirmed SUCCEEDED, return safely without duplicate audit entry
        if (payment.getStatus() == PaymentStatus.SUCCEEDED) {
            log.info("CASH payment {} already confirmed SUCCEEDED, returning idempotent result", paymentId);
            return PaymentResponse.from(payment);
        }

        if (payment.getStatus().isTerminal()) {
            throw new InvalidPaymentStateTransitionException(payment.getStatus(), PaymentStatus.SUCCEEDED);
        }

        // Authoritative verification: Driver must be assigned to this completed trip
        TripInternalResponse trip = tripServiceClient.getTrip(payment.getTripId())
                .orElseThrow(() -> new PaymentNotFoundException("Trip " + payment.getTripId() + " not found"));

        if (!"COMPLETED".equalsIgnoreCase(trip.status())) {
            throw new TripNotPayableException("Trip " + payment.getTripId() + " is not COMPLETED (status: " + trip.status() + ")");
        }

        if (trip.driverId() == null || !trip.driverId().equals(authenticatedDriverId)) {
            log.warn("Unauthorized cash confirmation attempt: driver {} is not assigned driver {} for trip {}",
                    authenticatedDriverId, trip.driverId(), trip.tripId());
            throw new PaymentAccessDeniedException("Driver " + authenticatedDriverId + " is not assigned to trip " + payment.getTripId());
        }

        Instant now = Instant.now();
        payment.markSucceeded("CASH_SETTLED", authenticatedDriverId.toString(), "DRIVER", now);
        PaymentTransaction saved = paymentRepository.save(payment);
        log.info("CASH payment {} successfully confirmed by assigned driver {}", paymentId, authenticatedDriverId);

        publishOutboxEvent("PAYMENT", saved.getId().toString(), "PAYMENT_SUCCEEDED",
                Map.of(
                        "paymentId", saved.getId().toString(),
                        "tripId", saved.getTripId().toString(),
                        "customerId", saved.getCustomerId().toString(),
                        "amount", saved.getAmount(),
                        "currency", saved.getCurrency(),
                        "paymentMethod", saved.getPaymentMethod().name(),
                        "status", saved.getStatus().name(),
                        "occurredAt", now.toString()
                ), now);

        return PaymentResponse.from(saved);
    }

    /**
     * Secure IPN Webhook callback processing from external payment provider (P0 Security).
     */
    @Transactional
    public Map<String, Object> processProviderWebhook(
            String providerName,
            String rawPayload,
            Map<String, String> headers) {

        PaymentGatewayProvider provider = resolveProviderByName(providerName);
        PaymentWebhookResult webhookResult = provider.verifyAndParseWebhook(rawPayload, headers);

        if (attemptRepository == null) {
            log.warn("AttemptRepository not wired, skipping attempt state update");
            return Map.of("message", "Processed", "resultCode", 0);
        }

        PaymentAttempt attempt = attemptRepository.findByProviderOrderId(webhookResult.providerOrderId())
                .orElseThrow(() -> new PaymentNotFoundException("Payment attempt not found for orderId: " + webhookResult.providerOrderId()));

        PaymentTransaction payment = paymentRepository.findById(attempt.getPaymentId())
                .orElseThrow(() -> new PaymentNotFoundException("Payment transaction not found for id: " + attempt.getPaymentId()));

        // Verification of Amount and Currency Integrity
        if (webhookResult.amount().compareTo(attempt.getAmount()) != 0) {
            log.error("Amount tampering detected in webhook! Expected {} but received {}",
                    attempt.getAmount(), webhookResult.amount());
            throw new PaymentSecurityException("Amount mismatch in provider callback");
        }

        if (!webhookResult.currency().equalsIgnoreCase(attempt.getCurrency())) {
            log.error("Currency mismatch in webhook! Expected {} but received {}",
                    attempt.getCurrency(), webhookResult.currency());
            throw new PaymentSecurityException("Currency mismatch in provider callback");
        }

        Instant now = Instant.now();

        // Idempotency: If attempt is already SUCCEEDED
        if (attempt.getStatus() == PaymentAttemptStatus.SUCCEEDED && payment.getStatus() == PaymentStatus.SUCCEEDED) {
            log.info("Duplicate successful webhook received for orderId {}, acknowledging idempotently",
                    webhookResult.providerOrderId());
            return Map.of("message", "Already acknowledged", "resultCode", 0);
        }

        // Out-of-order protection: A late failure webhook CANNOT revert an already SUCCEEDED payment
        if (payment.getStatus() == PaymentStatus.SUCCEEDED) {
            log.warn("Ignoring late failure webhook for already SUCCEEDED payment {}", payment.getId());
            return Map.of("message", "Ignored: Payment already succeeded", "resultCode", 0);
        }

        // Process transition based on classified status (P0: not all non-zero codes mean FAILED)
        PaymentAttemptStatus status = webhookResult.status();
        if (status == PaymentAttemptStatus.SUCCEEDED) {
            attempt.markSucceeded(webhookResult.providerTransactionId(), now);
            payment.markSucceeded(
                    webhookResult.providerTransactionId(),
                    provider.getProviderName(),
                    "PROVIDER",
                    now
            );
            log.info("Successfully confirmed digital payment {} via {} callback (orderId: {})",
                    payment.getId(), provider.getProviderName(), webhookResult.providerOrderId());

            publishOutboxEvent("PAYMENT", payment.getId().toString(), "PAYMENT_SUCCEEDED",
                    Map.of(
                            "paymentId", payment.getId().toString(),
                            "tripId", payment.getTripId().toString(),
                            "customerId", payment.getCustomerId().toString(),
                            "amount", payment.getAmount(),
                            "currency", payment.getCurrency(),
                            "paymentMethod", payment.getPaymentMethod().name(),
                            "providerTransactionId", webhookResult.providerTransactionId() != null ? webhookResult.providerTransactionId() : "",
                            "status", payment.getStatus().name(),
                            "occurredAt", now.toString()
                    ), now);
        } else if (status == PaymentAttemptStatus.PROCESSING) {
            log.info("Provider callback reported in-flight PROCESSING for orderId {}: code {} msg {}",
                    webhookResult.providerOrderId(), webhookResult.resultCode(), webhookResult.message());
        } else if (status == PaymentAttemptStatus.UNKNOWN) {
            attempt.markUnknown(webhookResult.message(), now);
            log.warn("Provider callback reported UNKNOWN for orderId {}: code {} msg {}",
                    webhookResult.providerOrderId(), webhookResult.resultCode(), webhookResult.message());
        } else {
            attempt.markFailed(webhookResult.resultCode(), webhookResult.message(), now);
            payment.markFailed(
                    "Provider declined: " + webhookResult.message(),
                    provider.getProviderName(),
                    "PROVIDER",
                    now
            );
            log.warn("Payment {} failed via {} callback: code {} msg {}",
                    payment.getId(), provider.getProviderName(), webhookResult.resultCode(), webhookResult.message());

            publishOutboxEvent("PAYMENT", payment.getId().toString(), "PAYMENT_FAILED",
                    Map.of(
                            "paymentId", payment.getId().toString(),
                            "tripId", payment.getTripId().toString(),
                            "customerId", payment.getCustomerId().toString(),
                            "amount", payment.getAmount(),
                            "currency", payment.getCurrency(),
                            "status", payment.getStatus().name(),
                            "reason", webhookResult.message() != null ? webhookResult.message() : "FAILED",
                            "occurredAt", now.toString()
                    ), now);
        }

        attemptRepository.save(attempt);
        paymentRepository.save(payment);

        return Map.of("message", "Callback processed successfully", "resultCode", 0);
    }

    /**
     * Minimal Safety Reconciliation: Resolves UNKNOWN/stuck processing attempts against provider.
     */
    @Transactional
    public PaymentResponse reconcileAttempt(UUID attemptId) {
        if (attemptRepository == null) {
            throw new IllegalStateException("AttemptRepository is not configured");
        }

        PaymentAttempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new PaymentNotFoundException("Payment attempt " + attemptId + " not found"));

        if (attempt.getStatus() == PaymentAttemptStatus.SUCCEEDED) {
            PaymentTransaction payment = paymentRepository.findById(attempt.getPaymentId())
                    .orElseThrow(() -> new PaymentNotFoundException("Payment not found"));
            return PaymentResponse.from(payment, attempt.getCheckoutUrl(), List.of(attempt));
        }

        PaymentGatewayProvider provider = resolveProviderByName(attempt.getProvider());
        ProviderQueryResult queryResult = provider.queryTransaction(attempt.getProviderOrderId());

        PaymentTransaction payment = paymentRepository.findById(attempt.getPaymentId())
                .orElseThrow(() -> new PaymentNotFoundException("Payment not found"));

        Instant now = Instant.now();
        if (queryResult.status() == PaymentAttemptStatus.SUCCEEDED && payment.getStatus() != PaymentStatus.SUCCEEDED) {
            attempt.markSucceeded(queryResult.providerTransactionId(), now);
            payment.markSucceeded(queryResult.providerTransactionId(), provider.getProviderName(), "SYSTEM_RECONCILER", now);
            attemptRepository.save(attempt);
            paymentRepository.save(payment);
            log.info("Reconciliation resolved attempt {} to SUCCEEDED", attemptId);

            publishOutboxEvent("PAYMENT", payment.getId().toString(), "PAYMENT_SUCCEEDED",
                    Map.of(
                            "paymentId", payment.getId().toString(),
                            "tripId", payment.getTripId().toString(),
                            "customerId", payment.getCustomerId().toString(),
                            "amount", payment.getAmount(),
                            "currency", payment.getCurrency(),
                            "status", payment.getStatus().name(),
                            "occurredAt", now.toString()
                    ), now);
        } else if (queryResult.status() == PaymentAttemptStatus.FAILED && !payment.getStatus().isTerminal()) {
            attempt.markFailed(queryResult.resultCode(), queryResult.message(), now);
            payment.markFailed("Reconciled failure: " + queryResult.message(), provider.getProviderName(), "SYSTEM_RECONCILER", now);
            attemptRepository.save(attempt);
            paymentRepository.save(payment);
            log.info("Reconciliation resolved attempt {} to FAILED", attemptId);

            publishOutboxEvent("PAYMENT", payment.getId().toString(), "PAYMENT_FAILED",
                    Map.of(
                            "paymentId", payment.getId().toString(),
                            "tripId", payment.getTripId().toString(),
                            "customerId", payment.getCustomerId().toString(),
                            "amount", payment.getAmount(),
                            "currency", payment.getCurrency(),
                            "status", payment.getStatus().name(),
                            "reason", queryResult.message() != null ? queryResult.message() : "RECONCILED_FAILED",
                            "occurredAt", now.toString()
                    ), now);
        }

        return PaymentResponse.from(payment, attempt.getCheckoutUrl(), List.of(attempt));
    }

    private void publishOutboxEvent(String aggregateType, String aggregateId, String eventType, Map<String, Object> payload, Instant now) {
        if (outboxRepository == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(payload);
            PaymentOutboxEvent event = new PaymentOutboxEvent(
                    UUID.randomUUID(),
                    eventType,
                    aggregateType,
                    UUID.fromString(aggregateId),
                    null,
                    null,
                    json,
                    now
            );
            outboxRepository.save(event);
        } catch (Exception e) {
            log.error("Failed to publish financial outbox event {} for aggregate {}: {}", eventType, aggregateId, e.getMessage());
        }
    }

    private PaymentGatewayProvider resolveProvider(PaymentMethod method) {
        if (availableProviders != null) {
            for (PaymentGatewayProvider p : availableProviders) {
                if ("MOMO".equalsIgnoreCase(p.getProviderName())) {
                    return p;
                }
            }
        }
        return defaultPaymentGatewayProvider;
    }

    private PaymentGatewayProvider resolveProviderByName(String name) {
        if (name != null && availableProviders != null) {
            for (PaymentGatewayProvider p : availableProviders) {
                if (p.getProviderName().equalsIgnoreCase(name)) {
                    return p;
                }
            }
        }
        return defaultPaymentGatewayProvider;
    }

    /**
     * Customer retrieves their own payment by ID.
     */
    @Transactional(readOnly = true)
    public PaymentResponse getPaymentById(UUID authenticatedCustomerId, UUID paymentId) {
        PaymentTransaction payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException("Payment " + paymentId + " not found"));

        if (!payment.getCustomerId().equals(authenticatedCustomerId)) {
            throw new PaymentAccessDeniedException("Customer does not own payment " + paymentId);
        }

        List<PaymentAttempt> attempts = (attemptRepository != null)
                ? attemptRepository.findByPaymentIdOrderByAttemptNumberAsc(paymentId)
                : List.of();

        String checkoutUrl = attempts.stream()
                .filter(a -> a.getCheckoutUrl() != null)
                .findFirst()
                .map(PaymentAttempt::getCheckoutUrl)
                .orElse(null);

        return PaymentResponse.from(payment, checkoutUrl, attempts);
    }

    /**
     * Customer retrieves all their payment transactions ordered by creation date descending.
     */
    @Transactional(readOnly = true)
    public List<PaymentResponse> getMyPayments(UUID authenticatedCustomerId) {
        if (authenticatedCustomerId == null) {
            throw new PaymentAccessDeniedException("Customer must be authenticated");
        }
        List<PaymentTransaction> payments = paymentRepository.findByCustomerIdOrderByCreatedAtDesc(authenticatedCustomerId);
        return payments.stream()
                .map(p -> {
                    List<PaymentAttempt> attempts = (attemptRepository != null)
                            ? attemptRepository.findByPaymentIdOrderByAttemptNumberAsc(p.getId())
                            : List.of();
                    String checkoutUrl = attempts.stream()
                            .filter(a -> a.getCheckoutUrl() != null)
                            .findFirst()
                            .map(PaymentAttempt::getCheckoutUrl)
                            .orElse(null);
                    return PaymentResponse.from(p, checkoutUrl, attempts);
                })
                .toList();
    }

    /**
     * Customer retrieves payments for a trip they own.
     */
    @Transactional(readOnly = true)
    public List<PaymentResponse> getPaymentsByTripId(UUID authenticatedCustomerId, UUID tripId) {
        List<PaymentTransaction> payments = paymentRepository.findByTripId(tripId);
        if (payments.isEmpty()) {
            return List.of();
        }

        // Verify customer owns the trip
        boolean allOwned = payments.stream().allMatch(p -> p.getCustomerId().equals(authenticatedCustomerId));
        if (!allOwned) {
            throw new PaymentAccessDeniedException("Customer does not own trip payments for " + tripId);
        }

        return payments.stream()
                .map(p -> {
                    List<PaymentAttempt> attempts = (attemptRepository != null)
                            ? attemptRepository.findByPaymentIdOrderByAttemptNumberAsc(p.getId())
                            : List.of();
                    String checkoutUrl = attempts.stream()
                            .filter(a -> a.getCheckoutUrl() != null)
                            .findFirst()
                            .map(PaymentAttempt::getCheckoutUrl)
                            .orElse(null);
                    return PaymentResponse.from(p, checkoutUrl, attempts);
                })
                .toList();
    }

    /**
     * Driver queries payments for a completed trip assigned to them (FR-PAY-007).
     */
    @Transactional(readOnly = true)
    public List<PaymentResponse> getPaymentsForDriverTrip(UUID authenticatedDriverId, UUID tripId) {
        if (authenticatedDriverId == null) {
            throw new PaymentAccessDeniedException("Driver must be authenticated");
        }

        TripInternalResponse trip = tripServiceClient.getTrip(tripId)
                .orElseThrow(() -> new PaymentNotFoundException("Trip " + tripId + " not found"));

        if (trip.driverId() == null || !trip.driverId().equals(authenticatedDriverId)) {
            log.warn("Unauthorized trip payment query: driver {} is not assigned driver {} for trip {}",
                    authenticatedDriverId, trip.driverId(), tripId);
            throw new PaymentAccessDeniedException("Driver " + authenticatedDriverId + " is not assigned to trip " + tripId);
        }

        List<PaymentTransaction> payments = paymentRepository.findByTripId(tripId);
        return payments.stream()
                .map(p -> {
                    List<PaymentAttempt> attempts = (attemptRepository != null)
                            ? attemptRepository.findByPaymentIdOrderByAttemptNumberAsc(p.getId())
                            : List.of();
                    String checkoutUrl = attempts.stream()
                            .filter(a -> a.getCheckoutUrl() != null)
                            .findFirst()
                            .map(PaymentAttempt::getCheckoutUrl)
                            .orElse(null);
                    return PaymentResponse.from(p, checkoutUrl, attempts);
                })
                .toList();
    }

    /**
     * Internal M2M lookup by payment ID.
     */
    @Transactional(readOnly = true)
    public PaymentResponse getPaymentByIdInternal(UUID paymentId) {
        PaymentTransaction payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException("Payment " + paymentId + " not found"));

        List<PaymentAttempt> attempts = (attemptRepository != null)
                ? attemptRepository.findByPaymentIdOrderByAttemptNumberAsc(paymentId)
                : List.of();

        return PaymentResponse.from(payment, null, attempts);
    }

    /**
     * Internal M2M lookup by trip ID.
     */
    @Transactional(readOnly = true)
    public List<PaymentResponse> getPaymentsByTripIdInternal(UUID tripId) {
        return paymentRepository.findByTripId(tripId).stream()
                .map(p -> {
                    List<PaymentAttempt> attempts = (attemptRepository != null)
                            ? attemptRepository.findByPaymentIdOrderByAttemptNumberAsc(p.getId())
                            : List.of();
                    return PaymentResponse.from(p, null, attempts);
                })
                .toList();
    }
}
