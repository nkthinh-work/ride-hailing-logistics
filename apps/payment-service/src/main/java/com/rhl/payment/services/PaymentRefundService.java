package com.rhl.payment.services;

import com.rhl.payment.dtos.CreateRefundRequest;
import com.rhl.payment.dtos.RefundEligibilityResponse;
import com.rhl.payment.dtos.RefundResponse;
import com.rhl.payment.entities.PaymentAttempt;
import com.rhl.payment.entities.PaymentOutboxEvent;
import com.rhl.payment.entities.PaymentRefund;
import com.rhl.payment.entities.PaymentTransaction;
import com.rhl.payment.entities.RefundAttempt;
import com.rhl.payment.enums.PaymentAttemptStatus;
import com.rhl.payment.enums.PaymentMethod;
import com.rhl.payment.enums.PaymentStatus;
import com.rhl.payment.enums.RefundStatus;
import com.rhl.payment.events.RefundEventPayload;
import com.rhl.payment.exceptions.IdempotencyConflictException;
import com.rhl.payment.exceptions.InvalidPaymentStateTransitionException;
import com.rhl.payment.exceptions.OverRefundException;
import com.rhl.payment.exceptions.PaymentAccessDeniedException;
import com.rhl.payment.exceptions.PaymentNotFoundException;
import com.rhl.payment.exceptions.RefundNotEligibleException;
import com.rhl.payment.exceptions.RefundNotFoundException;
import com.rhl.payment.providers.PaymentGatewayProvider;
import com.rhl.payment.providers.ProviderRefundCommand;
import com.rhl.payment.providers.ProviderRefundQueryResult;
import com.rhl.payment.providers.ProviderRefundResult;
import com.rhl.payment.repositories.PaymentAttemptRepository;
import com.rhl.payment.repositories.PaymentOutboxRepository;
import com.rhl.payment.repositories.PaymentRefundRepository;
import com.rhl.payment.repositories.PaymentTransactionRepository;
import com.rhl.payment.repositories.RefundAttemptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Core Refund Domain Service (SRS Section 4.8.1, FR-PAY-012..013).
 * Enforces financial monetary invariants, atomic over-refund prevention via pessimistic DB locking,
 * strict state transitions, policy gating, idempotent replays, and reliable transactional outbox publishing.
 */
@Service
public class PaymentRefundService {

    private static final Logger log = LoggerFactory.getLogger(PaymentRefundService.class);

    private final PaymentRefundRepository refundRepository;
    private final RefundAttemptRepository refundAttemptRepository;
    private final PaymentTransactionRepository paymentRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final PaymentOutboxRepository outboxRepository;
    private final List<PaymentGatewayProvider> availableProviders;
    private final PaymentGatewayProvider defaultProvider;
    private final ObjectMapper objectMapper;
    private final boolean policyGated;
    private final boolean autoApprove;

    @Autowired
    public PaymentRefundService(
            PaymentRefundRepository refundRepository,
            RefundAttemptRepository refundAttemptRepository,
            PaymentTransactionRepository paymentRepository,
            PaymentAttemptRepository paymentAttemptRepository,
            PaymentOutboxRepository outboxRepository,
            List<PaymentGatewayProvider> availableProviders,
            PaymentGatewayProvider defaultProvider,
            ObjectMapper objectMapper,
            @Value("${payment.refund.policy-gated:true}") boolean policyGated,
            @Value("${payment.refund.auto-approve:false}") boolean autoApprove
    ) {
        this.refundRepository = Objects.requireNonNull(refundRepository, "refundRepository is required");
        this.refundAttemptRepository = Objects.requireNonNull(refundAttemptRepository, "refundAttemptRepository is required");
        this.paymentRepository = Objects.requireNonNull(paymentRepository, "paymentRepository is required");
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository is required");
        this.availableProviders = availableProviders != null ? availableProviders : List.of();
        this.defaultProvider = defaultProvider;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
        this.policyGated = policyGated;
        this.autoApprove = autoApprove;
    }

    /**
     * Checks whether a payment is eligible for refund and calculates the remaining refundable balance.
     */
    @Transactional(readOnly = true)
    public RefundEligibilityResponse checkRefundEligibility(UUID actorId, UUID paymentId, boolean isStaff) {
        PaymentTransaction payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException("Payment " + paymentId + " not found"));

        if (!isStaff && !payment.getCustomerId().equals(actorId)) {
            throw new PaymentAccessDeniedException("Customer does not own payment " + paymentId);
        }

        if (payment.getStatus() != PaymentStatus.SUCCEEDED) {
            return new RefundEligibilityResponse(
                    paymentId,
                    false,
                    payment.getAmount(),
                    BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                    BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                    BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                    payment.getCurrency(),
                    "Payment in status " + payment.getStatus() + " is not eligible for refund (must be SUCCEEDED)",
                    policyGated ? "REFUND_POLICY_PENDING" : "ACTIVE"
            );
        }

        BigDecimal completedRefunds = refundRepository.sumAmountByPaymentIdAndStatusIn(
                paymentId, Set.of(RefundStatus.SUCCEEDED));
        BigDecimal inFlightRefunds = refundRepository.sumAmountByPaymentIdAndStatusIn(
                paymentId, Set.of(RefundStatus.REQUESTED, RefundStatus.APPROVED, RefundStatus.PROCESSING));

        BigDecimal totalRefunded = completedRefunds.add(inFlightRefunds);
        BigDecimal remaining = payment.getAmount().subtract(totalRefunded);
        if (remaining.compareTo(BigDecimal.ZERO) < 0) {
            remaining = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }

        boolean eligible = remaining.compareTo(BigDecimal.ZERO) > 0;
        String reason = eligible ? "Eligible for refund up to " + remaining + " " + payment.getCurrency()
                : "Payment amount has already been fully refunded or committed in flight";

        return new RefundEligibilityResponse(
                paymentId,
                eligible,
                payment.getAmount(),
                completedRefunds,
                inFlightRefunds,
                remaining,
                payment.getCurrency(),
                reason,
                policyGated ? "REFUND_POLICY_PENDING" : "ACTIVE"
        );
    }

    /**
     * Requests a refund with strict idempotency and over-refund prevention using pessimistic locking.
     */
    @Transactional
    public RefundResponse requestRefund(
            UUID actorId,
            UUID paymentId,
            String idempotencyKey,
            CreateRefundRequest request,
            boolean isStaff
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required for refund requests");
        }

        // 1. Idempotency Check: (paymentId, idempotencyKey)
        Optional<PaymentRefund> existingOpt = refundRepository.findByPaymentIdAndIdempotencyKey(paymentId, idempotencyKey);
        if (existingOpt.isPresent()) {
            PaymentRefund existing = existingOpt.get();
            if (request.amount() == null || existing.getAmount().compareTo(request.amount()) == 0) {
                log.info("Idempotent replay detected for refund on payment {} key {}", paymentId, idempotencyKey);
                return RefundResponse.from(existing);
            } else {
                log.warn("Idempotency conflict for refund on payment {}: key {} with differing amount", paymentId, idempotencyKey);
                throw new IdempotencyConflictException("Refund idempotency key already used with different parameters");
            }
        }

        // 2. Lock PaymentTransaction row with PESSIMISTIC_WRITE to prevent concurrent over-refund race conditions
        PaymentTransaction payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException("Payment " + paymentId + " not found"));

        if (!isStaff && !payment.getCustomerId().equals(actorId)) {
            throw new PaymentAccessDeniedException("Customer does not own payment " + paymentId);
        }

        if (payment.getStatus() != PaymentStatus.SUCCEEDED) {
            throw new RefundNotEligibleException("Cannot refund payment in status '" + payment.getStatus() + "' (must be SUCCEEDED)");
        }

        // 3. Over-Refund Invariant Check: SUM(completed + in_flight) + requestedAmount <= payment.amount
        BigDecimal totalCommitted = refundRepository.sumAmountByPaymentIdAndStatusIn(
                paymentId,
                Set.of(RefundStatus.REQUESTED, RefundStatus.APPROVED, RefundStatus.PROCESSING, RefundStatus.SUCCEEDED)
        );
        BigDecimal remainingRefundable = payment.getAmount().subtract(totalCommitted);

        BigDecimal refundAmount;
        if (request.amount() != null) {
            refundAmount = request.amount().setScale(2, RoundingMode.HALF_UP);
        } else {
            refundAmount = remainingRefundable;
        }

        if (refundAmount.compareTo(BigDecimal.ZERO) <= 0 || refundAmount.compareTo(remainingRefundable) > 0) {
            log.warn("Over-refund attempt rejected: requested={}, remainingRefundable={}, original={}",
                    refundAmount, remainingRefundable, payment.getAmount());
            throw new OverRefundException(refundAmount, remainingRefundable);
        }

        Instant now = Instant.now();
        String actorRole = isStaff ? "FINANCE_STAFF" : "CUSTOMER";
        String providerName = (payment.getPaymentMethod() == PaymentMethod.CASH) ? "CASH" : "MOMO";

        // 4. Handle CASH payment refund policy
        if (payment.getPaymentMethod() == PaymentMethod.CASH) {
            PaymentRefund refund = PaymentRefund.builder()
                    .paymentId(payment.getId())
                    .customerId(payment.getCustomerId())
                    .tripId(payment.getTripId())
                    .amount(refundAmount)
                    .currency(payment.getCurrency())
                    .reason(request.reason())
                    .idempotencyKey(idempotencyKey)
                    .requestedBy(actorId.toString())
                    .actorRole(actorRole)
                    .provider("CASH")
                    .policyNote("CASH_REFUND_SETTLEMENT_PENDING")
                    .initialStatus(RefundStatus.REQUESTED)
                    .now(now)
                    .build();

            PaymentRefund saved = refundRepository.save(refund);
            publishOutboxEvent(saved, "PAYMENT_REFUND_REQUESTED", now);
            log.info("Created CASH refund request {} for payment {} amount {}", saved.getId(), paymentId, refundAmount);
            return RefundResponse.from(saved);
        }

        // 5. Digital Payment Refund (Policy Gating)
        boolean canAutoApprove = !policyGated && (autoApprove || isStaff);
        RefundStatus initialStatus = canAutoApprove ? RefundStatus.APPROVED : RefundStatus.REQUESTED;
        String policyNote = policyGated ? "REFUND_POLICY_PENDING" : null;

        PaymentRefund refund = PaymentRefund.builder()
                .paymentId(payment.getId())
                .customerId(payment.getCustomerId())
                .tripId(payment.getTripId())
                .amount(refundAmount)
                .currency(payment.getCurrency())
                .reason(request.reason())
                .idempotencyKey(idempotencyKey)
                .requestedBy(actorId.toString())
                .actorRole(actorRole)
                .provider(providerName)
                .policyNote(policyNote)
                .initialStatus(initialStatus)
                .now(now)
                .build();

        PaymentRefund saved = refundRepository.save(refund);
        publishOutboxEvent(saved, "PAYMENT_REFUND_REQUESTED", now);

        if (initialStatus == RefundStatus.APPROVED) {
            // Execute provider refund immediately if auto-approved or initiated by staff
            return executeProviderRefund(saved, actorId.toString(), now);
        }

        log.info("Registered digital refund request {} for payment {} amount {} (status: {})",
                saved.getId(), paymentId, refundAmount, initialStatus);
        return RefundResponse.from(saved);
    }

    /**
     * Approves and executes an existing refund request (restricted to FINANCE_STAFF or ADMINISTRATOR).
     */
    @Transactional
    public RefundResponse approveAndExecuteRefund(UUID financeStaffId, UUID refundId, String note) {
        PaymentRefund refund = refundRepository.findById(refundId)
                .orElseThrow(() -> new RefundNotFoundException("Refund " + refundId + " not found"));

        if (refund.getStatus() == RefundStatus.SUCCEEDED) {
            log.info("Refund {} already SUCCEEDED, returning idempotent response", refundId);
            return RefundResponse.from(refund);
        }

        if (refund.getStatus() != RefundStatus.REQUESTED && refund.getStatus() != RefundStatus.APPROVED) {
            throw new InvalidPaymentStateTransitionException("Cannot approve refund in status " + refund.getStatus());
        }

        if (policyGated && "REFUND_POLICY_PENDING".equals(refund.getPolicyNote())) {
            log.warn("Cannot approve refund {} while refund policy is pending (REFUND_POLICY_PENDING)", refundId);
            throw new RefundNotEligibleException("Refund policy is currently pending business approval (REFUND_POLICY_PENDING)");
        }

        Instant now = Instant.now();
        if (refund.getStatus() == RefundStatus.REQUESTED) {
            refund.approve(financeStaffId.toString(), note, now);
            refund = refundRepository.save(refund);
        }

        // For CASH payments: manual settlement by finance staff completes the refund
        if ("CASH".equalsIgnoreCase(refund.getProvider())) {
            refund.markSucceeded("CASH-SETTLED-" + refundId.toString().substring(0, 8),
                    financeStaffId.toString(), "FINANCE_STAFF", now);
            PaymentRefund saved = refundRepository.save(refund);
            publishOutboxEvent(saved, "PAYMENT_REFUNDED", now);
            log.info("CASH refund {} marked SUCCEEDED manually by finance staff {}", refundId, financeStaffId);
            return RefundResponse.from(saved);
        }

        // Execute digital provider refund
        return executeProviderRefund(refund, financeStaffId.toString(), now);
    }

    private RefundResponse executeProviderRefund(PaymentRefund refund, String actor, Instant now) {
        String providerRefundOrderId = "REF-" + refund.getId().toString().substring(0, 8) + "-" + System.currentTimeMillis();
        refund.startProcessing(providerRefundOrderId, actor, "SYSTEM", "Dispatched to provider", now);
        refund = refundRepository.save(refund);

        // Record attempt
        RefundAttempt attempt = new RefundAttempt(
                UUID.randomUUID(),
                refund.getId(),
                1,
                refund.getProvider() != null ? refund.getProvider() : "MOMO",
                providerRefundOrderId,
                refund.getAmount(),
                refund.getCurrency(),
                PaymentAttemptStatus.PROCESSING,
                now
        );
        attempt = refundAttemptRepository.save(attempt);

        // Find original provider transaction ID from payment attempts
        String originalTransId = null;
        if (paymentAttemptRepository != null) {
            List<PaymentAttempt> paymentAttempts = paymentAttemptRepository.findByPaymentIdOrderByAttemptNumberAsc(refund.getPaymentId());
            originalTransId = paymentAttempts.stream()
                    .filter(a -> a.getProviderTransactionId() != null)
                    .map(PaymentAttempt::getProviderTransactionId)
                    .findFirst()
                    .orElse(null);
        }

        PaymentGatewayProvider provider = resolveProvider(refund.getProvider());
        ProviderRefundCommand command = new ProviderRefundCommand(
                providerRefundOrderId,
                originalTransId != null ? originalTransId : "99999999",
                providerRefundOrderId,
                refund.getAmount(),
                refund.getCurrency(),
                refund.getReason()
        );

        ProviderRefundResult result = provider.createRefund(command);

        if (result.status() == PaymentAttemptStatus.SUCCEEDED) {
            attempt.markSucceeded(result.providerRefundTransactionId(), now);
            refund.markSucceeded(result.providerRefundTransactionId(), provider.getProviderName(), "PROVIDER", now);
            publishOutboxEvent(refund, "PAYMENT_REFUNDED", now);
            log.info("Provider confirmed refund {} SUCCEEDED (transId: {})", refund.getId(), result.providerRefundTransactionId());
        } else if (result.status() == PaymentAttemptStatus.FAILED) {
            attempt.markFailed(result.resultCode(), result.message(), now);
            refund.markFailed("Provider declined refund: " + result.message(), provider.getProviderName(), "PROVIDER", now);
            log.warn("Provider declined refund {}: code {} msg {}", refund.getId(), result.resultCode(), result.message());
        } else {
            // UNKNOWN or PROCESSING: Leave in uncertainty, do NOT mark FAILED!
            attempt.markUnknown(result.message(), now);
            log.warn("Provider refund intent returned uncertain status for refund {}: {}", refund.getId(), result.message());
        }

        refundAttemptRepository.save(attempt);
        PaymentRefund saved = refundRepository.save(refund);
        return RefundResponse.from(saved);
    }

    /**
     * Reconciles an in-flight or unknown refund attempt with provider.
     */
    @Transactional
    public RefundResponse reconcileRefund(UUID refundId) {
        PaymentRefund refund = refundRepository.findById(refundId)
                .orElseThrow(() -> new RefundNotFoundException("Refund " + refundId + " not found"));

        if (refund.getStatus() == RefundStatus.SUCCEEDED) {
            return RefundResponse.from(refund);
        }

        if (refund.getProviderRefundOrderId() == null) {
            return RefundResponse.from(refund);
        }

        PaymentGatewayProvider provider = resolveProvider(refund.getProvider());
        ProviderRefundQueryResult queryResult = provider.queryRefund(refund.getProviderRefundOrderId());

        Instant now = Instant.now();
        if (queryResult.status() == PaymentAttemptStatus.SUCCEEDED) {
            refund.markSucceeded(queryResult.providerRefundTransactionId(), provider.getProviderName(), "SYSTEM_RECONCILER", now);
            publishOutboxEvent(refund, "PAYMENT_REFUNDED", now);
            refund = refundRepository.save(refund);
            log.info("Reconciliation resolved refund {} to SUCCEEDED", refundId);
        } else if (queryResult.status() == PaymentAttemptStatus.FAILED && !refund.getStatus().isTerminal()) {
            refund.markFailed("Provider query confirmed refund failure: " + queryResult.message(),
                    provider.getProviderName(), "SYSTEM_RECONCILER", now);
            refund = refundRepository.save(refund);
            log.info("Reconciliation resolved refund {} to FAILED", refundId);
        }

        return RefundResponse.from(refund);
    }

    @Transactional(readOnly = true)
    public RefundResponse getRefundById(UUID actorId, UUID refundId, boolean isStaff) {
        PaymentRefund refund = refundRepository.findById(refundId)
                .orElseThrow(() -> new RefundNotFoundException("Refund " + refundId + " not found"));

        if (!isStaff && !refund.getCustomerId().equals(actorId)) {
            throw new PaymentAccessDeniedException("Customer does not own refund " + refundId);
        }

        return RefundResponse.from(refund);
    }

    @Transactional(readOnly = true)
    public List<RefundResponse> getRefundsByPaymentId(UUID actorId, UUID paymentId, boolean isStaff) {
        PaymentTransaction payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException("Payment " + paymentId + " not found"));

        if (!isStaff && !payment.getCustomerId().equals(actorId)) {
            throw new PaymentAccessDeniedException("Customer does not own payment " + paymentId);
        }

        return refundRepository.findByPaymentIdOrderByCreatedAtDesc(paymentId).stream()
                .map(RefundResponse::from)
                .toList();
    }

    private void publishOutboxEvent(PaymentRefund refund, String eventType, Instant now) {
        try {
            RefundEventPayload payload = new RefundEventPayload(
                    refund.getId(),
                    refund.getPaymentId(),
                    refund.getTripId(),
                    refund.getCustomerId(),
                    refund.getAmount(),
                    refund.getCurrency(),
                    refund.getStatus().name(),
                    refund.getReason(),
                    refund.getProvider(),
                    refund.getProviderRefundTransactionId(),
                    now
            );

            PaymentOutboxEvent outboxEvent = new PaymentOutboxEvent(
                    UUID.randomUUID(),
                    eventType,
                    "REFUND",
                    refund.getId(),
                    refund.getProviderRefundOrderId(),
                    refund.getCustomerId(),
                    objectMapper.writeValueAsString(payload),
                    now
            );
            outboxRepository.save(outboxEvent);
        } catch (Exception ex) {
            log.error("Failed to serialize refund outbox event: {}", ex.getMessage());
        }
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
