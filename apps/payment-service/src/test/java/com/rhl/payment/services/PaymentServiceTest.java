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
import com.rhl.payment.exceptions.PaymentAccessDeniedException;
import com.rhl.payment.exceptions.PaymentSecurityException;
import com.rhl.payment.exceptions.TripNotPayableException;
import com.rhl.payment.providers.PaymentGatewayProvider;
import com.rhl.payment.providers.PaymentProviderResult;
import com.rhl.payment.providers.PaymentWebhookResult;
import com.rhl.payment.providers.ProviderQueryResult;
import com.rhl.payment.repositories.PaymentAttemptRepository;
import com.rhl.payment.repositories.PaymentTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentTransactionRepository paymentRepository;

    @Mock
    private PaymentAttemptRepository attemptRepository;

    @Mock
    private TripServiceClient tripServiceClient;

    @Mock
    private PaymentGatewayProvider paymentGatewayProvider;

    private PaymentService paymentService;

    private final UUID customerId = UUID.randomUUID();
    private final UUID driverId = UUID.randomUUID();
    private final UUID tripId = UUID.randomUUID();
    private final String idempotencyKey = "key-test-abc";

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(
                paymentRepository,
                attemptRepository,
                tripServiceClient,
                paymentGatewayProvider,
                List.of(paymentGatewayProvider)
        );
    }

    @Test
    @DisplayName("Idempotency replay: returns existing transaction when same customer, key, and payload")
    void initiatePaymentReplayExisting() {
        InitiatePaymentRequest request = new InitiatePaymentRequest(tripId, PaymentMethod.DIGITAL_WALLET);

        PaymentTransaction existing = PaymentTransaction.builder()
                .tripId(tripId)
                .customerId(customerId)
                .amount(new BigDecimal("45000.00"))
                .currency("VND")
                .paymentMethod(PaymentMethod.DIGITAL_WALLET)
                .idempotencyKey(idempotencyKey)
                .build();

        when(paymentRepository.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey))
                .thenReturn(Optional.of(existing));
        when(attemptRepository.findByPaymentIdOrderByAttemptNumberAsc(existing.getId()))
                .thenReturn(List.of());

        PaymentResponse response = paymentService.initiatePayment(customerId, idempotencyKey, request);

        assertThat(response).isNotNull();
        assertThat(response.tripId()).isEqualTo(tripId);
        assertThat(response.amount()).isEqualByComparingTo("45000.00");
        verify(paymentRepository, never()).save(any());
        verify(tripServiceClient, never()).getTrip(any());
    }

    @Test
    @DisplayName("Idempotency conflict: throws IdempotencyConflictException when same key but differing tripId or method")
    void initiatePaymentConflictOnDifferentPayload() {
        InitiatePaymentRequest request = new InitiatePaymentRequest(tripId, PaymentMethod.CASH);

        PaymentTransaction existingWithDifferentMethod = PaymentTransaction.builder()
                .tripId(tripId)
                .customerId(customerId)
                .amount(new BigDecimal("45000.00"))
                .currency("VND")
                .paymentMethod(PaymentMethod.DIGITAL_WALLET)
                .idempotencyKey(idempotencyKey)
                .build();

        when(paymentRepository.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey))
                .thenReturn(Optional.of(existingWithDifferentMethod));

        assertThatThrownBy(() -> paymentService.initiatePayment(customerId, idempotencyKey, request))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("different parameters");

        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("Zero Double Charging: rejects new payment when trip already has SUCCEEDED payment")
    void initiatePaymentRejectsAlreadyPaidTrip() {
        InitiatePaymentRequest request = new InitiatePaymentRequest(tripId, PaymentMethod.DIGITAL_WALLET);

        when(paymentRepository.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey))
                .thenReturn(Optional.empty());
        when(paymentRepository.existsByTripIdAndStatus(tripId, PaymentStatus.SUCCEEDED))
                .thenReturn(true);

        assertThatThrownBy(() -> paymentService.initiatePayment(customerId, idempotencyKey, request))
                .isInstanceOf(TripNotPayableException.class)
                .hasMessageContaining("already been paid");

        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("Defense-in-depth: rejects new payment when trip already has an active PENDING or PROCESSING payment")
    void initiatePaymentRejectsTripWithActivePendingOrProcessingPayment() {
        InitiatePaymentRequest request = new InitiatePaymentRequest(tripId, PaymentMethod.CASH);

        when(paymentRepository.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey))
                .thenReturn(Optional.empty());
        when(paymentRepository.existsByTripIdAndStatus(tripId, PaymentStatus.SUCCEEDED))
                .thenReturn(false);
        when(paymentRepository.existsByTripIdAndStatusIn(eq(tripId), any()))
                .thenReturn(true);

        assertThatThrownBy(() -> paymentService.initiatePayment(customerId, idempotencyKey, request))
                .isInstanceOf(TripNotPayableException.class)
                .hasMessageContaining("already has an active payment in progress");

        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("Security: rejects payment when trip belongs to another customer")
    void initiatePaymentRejectsMismatchedCustomerOwnership() {
        InitiatePaymentRequest request = new InitiatePaymentRequest(tripId, PaymentMethod.DIGITAL_WALLET);
        UUID otherCustomer = UUID.randomUUID();

        when(paymentRepository.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey))
                .thenReturn(Optional.empty());
        when(paymentRepository.existsByTripIdAndStatus(tripId, PaymentStatus.SUCCEEDED))
                .thenReturn(false);
        when(paymentRepository.existsByTripIdAndStatusIn(eq(tripId), any()))
                .thenReturn(false);

        TripInternalResponse trip = new TripInternalResponse(
                tripId, otherCustomer, UUID.randomUUID(), "COMPLETED",
                new BigDecimal("60000.00"), "VND", null
        );
        when(tripServiceClient.getTrip(tripId)).thenReturn(Optional.of(trip));

        assertThatThrownBy(() -> paymentService.initiatePayment(customerId, idempotencyKey, request))
                .isInstanceOf(PaymentAccessDeniedException.class)
                .hasMessageContaining("does not own trip");

        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("Rejects payment when trip is not in a payable state (e.g. IN_TRIP)")
    void initiatePaymentRejectsActiveTrip() {
        InitiatePaymentRequest request = new InitiatePaymentRequest(tripId, PaymentMethod.CASH);

        when(paymentRepository.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey))
                .thenReturn(Optional.empty());
        when(paymentRepository.existsByTripIdAndStatus(tripId, PaymentStatus.SUCCEEDED))
                .thenReturn(false);
        when(paymentRepository.existsByTripIdAndStatusIn(eq(tripId), any()))
                .thenReturn(false);

        TripInternalResponse trip = new TripInternalResponse(
                tripId, customerId, UUID.randomUUID(), "IN_TRIP",
                new BigDecimal("60000.00"), "VND", null
        );
        when(tripServiceClient.getTrip(tripId)).thenReturn(Optional.of(trip));

        assertThatThrownBy(() -> paymentService.initiatePayment(customerId, idempotencyKey, request))
                .isInstanceOf(TripNotPayableException.class)
                .hasMessageContaining("not eligible for payment");
    }

    @Test
    @DisplayName("Rejects payment when cancelled trip has fee policy pending (cancellationFee <= 0)")
    void initiatePaymentRejectsCancelledTripWithPendingFee() {
        InitiatePaymentRequest request = new InitiatePaymentRequest(tripId, PaymentMethod.DIGITAL_WALLET);

        when(paymentRepository.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey))
                .thenReturn(Optional.empty());
        when(paymentRepository.existsByTripIdAndStatus(tripId, PaymentStatus.SUCCEEDED))
                .thenReturn(false);
        when(paymentRepository.existsByTripIdAndStatusIn(eq(tripId), any()))
                .thenReturn(false);

        TripInternalResponse trip = new TripInternalResponse(
                tripId, customerId, null, "CANCELLED",
                new BigDecimal("60000.00"), "VND", BigDecimal.ZERO
        );
        when(tripServiceClient.getTrip(tripId)).thenReturn(Optional.of(trip));

        assertThatThrownBy(() -> paymentService.initiatePayment(customerId, idempotencyKey, request))
                .isInstanceOf(TripNotPayableException.class)
                .hasMessageContaining("Cancelled trip has no payable cancellation fee");
    }

    @Test
    @DisplayName("Successfully creates and dispatches payment using authoritative amount from completed trip")
    void initiatePaymentSuccessForCompletedTrip() {
        InitiatePaymentRequest request = new InitiatePaymentRequest(tripId, PaymentMethod.DIGITAL_WALLET);

        when(paymentRepository.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey))
                .thenReturn(Optional.empty());
        when(paymentRepository.existsByTripIdAndStatus(tripId, PaymentStatus.SUCCEEDED))
                .thenReturn(false);
        when(paymentRepository.existsByTripIdAndStatusIn(eq(tripId), any()))
                .thenReturn(false);

        BigDecimal authoritativeFare = new BigDecimal("75000.00");
        TripInternalResponse trip = new TripInternalResponse(
                tripId, customerId, UUID.randomUUID(), "COMPLETED",
                authoritativeFare, "VND", null
        );
        when(tripServiceClient.getTrip(tripId)).thenReturn(Optional.of(trip));

        when(paymentGatewayProvider.getProviderName()).thenReturn("SANDBOX");
        when(paymentGatewayProvider.processPayment(any()))
                .thenReturn(new PaymentProviderResult("REF-MOMO-12345", PaymentStatus.PROCESSING, "Intent created"));

        when(paymentRepository.save(any(PaymentTransaction.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(attemptRepository.save(any(PaymentAttempt.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        PaymentResponse response = paymentService.initiatePayment(customerId, idempotencyKey, request);

        assertThat(response).isNotNull();
        assertThat(response.tripId()).isEqualTo(tripId);
        assertThat(response.customerId()).isEqualTo(customerId);
        assertThat(response.amount()).isEqualByComparingTo("75000.00");
        assertThat(response.currency()).isEqualTo("VND");
        assertThat(response.paymentMethod()).isEqualTo(PaymentMethod.DIGITAL_WALLET);
        assertThat(response.status()).isEqualTo(PaymentStatus.PROCESSING);

        ArgumentCaptor<PaymentTransaction> captor = ArgumentCaptor.forClass(PaymentTransaction.class);
        verify(paymentRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        PaymentTransaction saved = captor.getValue();
        assertThat(saved.getAmount()).isEqualByComparingTo("75000.00");
    }

    // --- CASH COLLECTION TESTS ---

    @Test
    @DisplayName("CASH Flow: Assigned driver successfully confirms cash collection for completed trip")
    void confirmCashPaymentSuccess() {
        UUID paymentId = UUID.randomUUID();
        PaymentTransaction payment = PaymentTransaction.builder()
                .id(paymentId)
                .tripId(tripId)
                .customerId(customerId)
                .amount(new BigDecimal("50000.00"))
                .currency("VND")
                .paymentMethod(PaymentMethod.CASH)
                .idempotencyKey("cash-key-1")
                .build();
        payment.startProcessing(customerId.toString(), "CUSTOMER", "Processing started", Instant.now());

        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));

        TripInternalResponse trip = new TripInternalResponse(
                tripId, customerId, driverId, "COMPLETED",
                new BigDecimal("50000.00"), "VND", null
        );
        when(tripServiceClient.getTrip(tripId)).thenReturn(Optional.of(trip));
        when(paymentRepository.save(any(PaymentTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentResponse response = paymentService.confirmCashPayment(driverId, paymentId);

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(response.providerReference()).isEqualTo("CASH_SETTLED");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("CASH Flow: Rejects confirmation when wrong driver attempts to confirm (HTTP 403)")
    void confirmCashPaymentRejectsWrongDriver() {
        UUID paymentId = UUID.randomUUID();
        UUID wrongDriverId = UUID.randomUUID();

        PaymentTransaction payment = PaymentTransaction.builder()
                .id(paymentId)
                .tripId(tripId)
                .customerId(customerId)
                .amount(new BigDecimal("50000.00"))
                .paymentMethod(PaymentMethod.CASH)
                .idempotencyKey("cash-key-2")
                .build();
        payment.startProcessing(customerId.toString(), "CUSTOMER", "Processing", Instant.now());

        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));

        TripInternalResponse trip = new TripInternalResponse(
                tripId, customerId, driverId, "COMPLETED", // actual assigned driver is driverId
                new BigDecimal("50000.00"), "VND", null
        );
        when(tripServiceClient.getTrip(tripId)).thenReturn(Optional.of(trip));

        assertThatThrownBy(() -> paymentService.confirmCashPayment(wrongDriverId, paymentId))
                .isInstanceOf(PaymentAccessDeniedException.class)
                .hasMessageContaining("not assigned to trip");

        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("CASH Flow: Rejects confirmation when trip is not in COMPLETED status")
    void confirmCashPaymentRejectsNonCompletedTrip() {
        UUID paymentId = UUID.randomUUID();
        PaymentTransaction payment = PaymentTransaction.builder()
                .id(paymentId)
                .tripId(tripId)
                .customerId(customerId)
                .amount(new BigDecimal("50000.00"))
                .paymentMethod(PaymentMethod.CASH)
                .idempotencyKey("cash-key-3")
                .build();
        payment.startProcessing(customerId.toString(), "CUSTOMER", "Processing", Instant.now());

        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));

        TripInternalResponse trip = new TripInternalResponse(
                tripId, customerId, driverId, "ARRIVED",
                new BigDecimal("50000.00"), "VND", null
        );
        when(tripServiceClient.getTrip(tripId)).thenReturn(Optional.of(trip));

        assertThatThrownBy(() -> paymentService.confirmCashPayment(driverId, paymentId))
                .isInstanceOf(TripNotPayableException.class)
                .hasMessageContaining("not COMPLETED");
    }

    @Test
    @DisplayName("CASH Flow: Idempotent replay when payment is already SUCCEEDED")
    void confirmCashPaymentIdempotentReplay() {
        UUID paymentId = UUID.randomUUID();
        PaymentTransaction payment = PaymentTransaction.builder()
                .id(paymentId)
                .tripId(tripId)
                .customerId(customerId)
                .amount(new BigDecimal("50000.00"))
                .paymentMethod(PaymentMethod.CASH)
                .idempotencyKey("cash-key-4")
                .build();
        payment.startProcessing(customerId.toString(), "CUSTOMER", "Processing", Instant.now());
        payment.markSucceeded("CASH_SETTLED", driverId.toString(), "DRIVER", Instant.now());

        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));

        PaymentResponse response = paymentService.confirmCashPayment(driverId, paymentId);

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        verify(paymentRepository, never()).save(any());
        verify(tripServiceClient, never()).getTrip(any());
    }

    // --- WEBHOOK CALLBACK TESTS ---

    @Test
    @DisplayName("Webhook: Successfully processes valid signed SUCCESS callback")
    void processWebhookSuccess() {
        String orderId = "ORD-WEBHOOK-1";
        PaymentTransaction payment = PaymentTransaction.builder()
                .id(UUID.randomUUID())
                .tripId(tripId)
                .customerId(customerId)
                .amount(new BigDecimal("60000.00"))
                .currency("VND")
                .paymentMethod(PaymentMethod.DIGITAL_WALLET)
                .idempotencyKey("key-wh-1")
                .build();
        payment.startProcessing("cust", "CUSTOMER", "dispatched", Instant.now());

        PaymentAttempt attempt = PaymentAttempt.builder()
                .paymentId(payment.getId())
                .attemptNumber(1)
                .provider("MOMO")
                .providerOrderId(orderId)
                .amount(new BigDecimal("60000.00"))
                .currency("VND")
                .idempotencyReference("key-wh-1")
                .status(PaymentAttemptStatus.PROCESSING)
                .build();

        when(paymentGatewayProvider.getProviderName()).thenReturn("MOMO");
        when(paymentGatewayProvider.verifyAndParseWebhook(any(), any()))
                .thenReturn(new PaymentWebhookResult("MOMO", orderId, "trans-999", new BigDecimal("60000.00"),
                        "VND", true, "0", "Success", "raw"));

        when(attemptRepository.findByProviderOrderId(orderId)).thenReturn(Optional.of(attempt));
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));

        Map<String, Object> result = paymentService.processProviderWebhook("MOMO", "rawPayload", Map.of());

        assertThat(result.get("resultCode")).isEqualTo(0);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
        verify(paymentRepository).save(payment);
        verify(attemptRepository).save(attempt);
    }

    @Test
    @DisplayName("Webhook Security: Rejects callback when amount does not match attempt amount")
    void processWebhookRejectsAmountMismatch() {
        String orderId = "ORD-WEBHOOK-2";
        PaymentTransaction payment = PaymentTransaction.builder()
                .id(UUID.randomUUID())
                .tripId(tripId)
                .customerId(customerId)
                .amount(new BigDecimal("60000.00"))
                .currency("VND")
                .paymentMethod(PaymentMethod.DIGITAL_WALLET)
                .idempotencyKey("key-wh-2")
                .build();

        PaymentAttempt attempt = PaymentAttempt.builder()
                .paymentId(payment.getId())
                .attemptNumber(1)
                .provider("MOMO")
                .providerOrderId(orderId)
                .amount(new BigDecimal("60000.00"))
                .currency("VND")
                .idempotencyReference("key-wh-2")
                .status(PaymentAttemptStatus.PROCESSING)
                .build();

        when(paymentGatewayProvider.getProviderName()).thenReturn("MOMO");
        when(paymentGatewayProvider.verifyAndParseWebhook(any(), any()))
                .thenReturn(new PaymentWebhookResult("MOMO", orderId, "trans-999", new BigDecimal("10000.00"), // mismatch!
                        "VND", true, "0", "Success", "raw"));

        when(attemptRepository.findByProviderOrderId(orderId)).thenReturn(Optional.of(attempt));
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> paymentService.processProviderWebhook("MOMO", "rawPayload", Map.of()))
                .isInstanceOf(PaymentSecurityException.class)
                .hasMessageContaining("Amount mismatch");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("Webhook Idempotency: Duplicate callback returns acknowledgment without duplicate status mutation")
    void processWebhookDuplicateAcknowledgedIdempotently() {
        String orderId = "ORD-WEBHOOK-DUP";
        PaymentTransaction payment = PaymentTransaction.builder()
                .id(UUID.randomUUID())
                .tripId(tripId)
                .customerId(customerId)
                .amount(new BigDecimal("60000.00"))
                .currency("VND")
                .paymentMethod(PaymentMethod.DIGITAL_WALLET)
                .idempotencyKey("key-wh-dup")
                .build();
        payment.startProcessing("cust", "CUSTOMER", "dispatched", Instant.now());
        payment.markSucceeded("trans-888", "MOMO", "PROVIDER", Instant.now());

        PaymentAttempt attempt = PaymentAttempt.builder()
                .paymentId(payment.getId())
                .attemptNumber(1)
                .provider("MOMO")
                .providerOrderId(orderId)
                .amount(new BigDecimal("60000.00"))
                .currency("VND")
                .idempotencyReference("key-wh-dup")
                .status(PaymentAttemptStatus.SUCCEEDED)
                .build();

        when(paymentGatewayProvider.getProviderName()).thenReturn("MOMO");
        when(paymentGatewayProvider.verifyAndParseWebhook(any(), any()))
                .thenReturn(new PaymentWebhookResult("MOMO", orderId, "trans-888", new BigDecimal("60000.00"),
                        "VND", true, "0", "Success", "raw"));

        when(attemptRepository.findByProviderOrderId(orderId)).thenReturn(Optional.of(attempt));
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));

        Map<String, Object> result = paymentService.processProviderWebhook("MOMO", "rawPayload", Map.of());

        assertThat(result.get("resultCode")).isEqualTo(0);
        assertThat(result.get("message")).isEqualTo("Already acknowledged");
        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("Webhook Out-of-Order Safety: Late failure callback does not revert terminal SUCCEEDED payment")
    void processWebhookOutOfOrderCannotRevertSucceeded() {
        String orderId = "ORD-WEBHOOK-OUT-OF-ORDER";
        PaymentTransaction payment = PaymentTransaction.builder()
                .id(UUID.randomUUID())
                .tripId(tripId)
                .customerId(customerId)
                .amount(new BigDecimal("60000.00"))
                .currency("VND")
                .paymentMethod(PaymentMethod.DIGITAL_WALLET)
                .idempotencyKey("key-wh-ooo")
                .build();
        payment.startProcessing("cust", "CUSTOMER", "dispatched", Instant.now());
        payment.markSucceeded("trans-777", "MOMO", "PROVIDER", Instant.now());

        PaymentAttempt attempt = PaymentAttempt.builder()
                .paymentId(payment.getId())
                .attemptNumber(1)
                .provider("MOMO")
                .providerOrderId(orderId)
                .amount(new BigDecimal("60000.00"))
                .currency("VND")
                .idempotencyReference("key-wh-ooo")
                .status(PaymentAttemptStatus.PROCESSING)
                .build();

        when(paymentGatewayProvider.getProviderName()).thenReturn("MOMO");
        // Webhook arrives late with failure resultCode = 1006
        when(paymentGatewayProvider.verifyAndParseWebhook(any(), any()))
                .thenReturn(new PaymentWebhookResult("MOMO", orderId, null, new BigDecimal("60000.00"),
                        "VND", false, "1006", "User cancelled transaction", "raw"));

        when(attemptRepository.findByProviderOrderId(orderId)).thenReturn(Optional.of(attempt));
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));

        Map<String, Object> result = paymentService.processProviderWebhook("MOMO", "rawPayload", Map.of());

        assertThat(result.get("resultCode")).isEqualTo(0);
        assertThat(result.get("message")).isEqualTo("Ignored: Payment already succeeded");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED); // Still SUCCEEDED!
        verify(paymentRepository, never()).save(any());
    }

    // --- RECONCILIATION TESTS ---

    @Test
    @DisplayName("Reconciliation: Resolves PROCESSING attempt to SUCCEEDED when remote provider reports success")
    void reconcileAttemptSuccess() {
        UUID attemptId = UUID.randomUUID();
        String orderId = "ORD-RECON-1";
        PaymentTransaction payment = PaymentTransaction.builder()
                .id(UUID.randomUUID())
                .tripId(tripId)
                .customerId(customerId)
                .amount(new BigDecimal("50000.00"))
                .currency("VND")
                .paymentMethod(PaymentMethod.DIGITAL_WALLET)
                .idempotencyKey("key-recon-1")
                .build();
        payment.startProcessing("cust", "CUSTOMER", "dispatched", Instant.now());

        PaymentAttempt attempt = PaymentAttempt.builder()
                .id(attemptId)
                .paymentId(payment.getId())
                .attemptNumber(1)
                .provider("MOMO")
                .providerOrderId(orderId)
                .amount(new BigDecimal("50000.00"))
                .currency("VND")
                .idempotencyReference("key-recon-1")
                .status(PaymentAttemptStatus.PROCESSING)
                .build();

        when(attemptRepository.findById(attemptId)).thenReturn(Optional.of(attempt));
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(paymentGatewayProvider.getProviderName()).thenReturn("MOMO");
        when(paymentGatewayProvider.queryTransaction(orderId))
                .thenReturn(new ProviderQueryResult(orderId, "remote-trans-1", PaymentAttemptStatus.SUCCEEDED, "0", "Success"));

        PaymentResponse response = paymentService.reconcileAttempt(attemptId);

        assertThat(response.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
        verify(paymentRepository).save(payment);
        verify(attemptRepository).save(attempt);
    }

    @Test
    @DisplayName("Get payment by ID validates customer ownership")
    void getPaymentByIdValidatesOwnership() {
        UUID paymentId = UUID.randomUUID();
        PaymentTransaction payment = PaymentTransaction.builder()
                .tripId(tripId)
                .customerId(customerId)
                .amount(new BigDecimal("50000.00"))
                .paymentMethod(PaymentMethod.CASH)
                .idempotencyKey("key-1")
                .build();

        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
        when(attemptRepository.findByPaymentIdOrderByAttemptNumberAsc(paymentId)).thenReturn(List.of());

        PaymentResponse response = paymentService.getPaymentById(customerId, paymentId);
        assertThat(response).isNotNull();

        UUID alienCustomer = UUID.randomUUID();
        assertThatThrownBy(() -> paymentService.getPaymentById(alienCustomer, paymentId))
                .isInstanceOf(PaymentAccessDeniedException.class)
                .hasMessageContaining("does not own payment");
    }
}
