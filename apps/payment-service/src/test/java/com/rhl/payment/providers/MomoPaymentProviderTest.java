package com.rhl.payment.providers;

import com.rhl.payment.entities.PaymentAttempt;
import com.rhl.payment.entities.PaymentTransaction;
import com.rhl.payment.enums.PaymentMethod;
import com.rhl.payment.exceptions.PaymentSecurityException;
import com.rhl.payment.providers.momo.MomoPaymentProvider;
import com.rhl.payment.providers.momo.MomoProperties;
import com.rhl.payment.utils.CryptoUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MomoPaymentProviderTest {

    private MomoProperties properties;
    private ObjectMapper objectMapper;
    private MomoPaymentProvider provider;

    private final String secretKey = "MOMO_TEST_SECRET_KEY_123456";
    private final String partnerCode = "MOMO_PARTNER_ABC";
    private final String accessKey = "MOMO_ACCESS_KEY_XYZ";

    @BeforeEach
    void setUp() {
        properties = new MomoProperties();
        properties.setPartnerCode(partnerCode);
        properties.setAccessKey(accessKey);
        properties.setSecretKey(secretKey);
        properties.setEndpoint("https://test-payment.momo.vn");
        properties.setIpnUrl("http://localhost:8080/api/v1/payments/webhook/momo");
        properties.setRedirectUrl("http://localhost:3000/customer/payment-result");

        objectMapper = new ObjectMapper();
        provider = new MomoPaymentProvider(properties, objectMapper, null);
    }

    @Test
    @DisplayName("HMAC-SHA256 signature utility generates expected hex hash")
    void cryptoUtilsComputesDeterministicHmacSha256() {
        String data = "accessKey=XYZ&amount=50000&partnerCode=ABC";
        String key = "secret-123";
        String sig1 = CryptoUtils.hmacSha256(data, key);
        String sig2 = CryptoUtils.hmacSha256(data, key);

        assertThat(sig1).isNotNull().isEqualTo(sig2);
        assertThat(sig1).matches("^[a-f0-9]{64}$");
        assertThat(CryptoUtils.constantTimeEquals(sig1, sig2)).isTrue();
        assertThat(CryptoUtils.constantTimeEquals(sig1, "tampered")).isFalse();
    }

    @Test
    @DisplayName("Creates PaymentIntent with valid deterministic fallback checkoutUrl")
    void createsPaymentIntentSuccessfully() {
        UUID tripId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        PaymentTransaction tx = PaymentTransaction.builder()
                .tripId(tripId)
                .customerId(customerId)
                .amount(new BigDecimal("65000.00"))
                .currency("VND")
                .paymentMethod(PaymentMethod.DIGITAL_WALLET)
                .idempotencyKey("idemp-momo-test")
                .build();

        PaymentAttempt attempt = PaymentAttempt.builder()
                .paymentId(tx.getId())
                .attemptNumber(1)
                .provider("MOMO")
                .providerOrderId("ORD-MOMO-TEST-001")
                .amount(tx.getAmount())
                .currency("VND")
                .idempotencyReference("idemp-momo-test")
                .build();

        PaymentIntentResult result = provider.createPaymentIntent(tx, attempt);

        assertThat(result).isNotNull();
        assertThat(result.providerOrderId()).isEqualTo("ORD-MOMO-TEST-001");
        assertThat(result.checkoutUrl()).contains("https://test-payment.momo.vn/v2/gateway/pay?s=ORD-MOMO-TEST-001");
    }

    @Test
    @DisplayName("Webhook Verification: Successfully verifies validly signed MoMo callback payload")
    void verifiesValidSignedWebhookPayload() {
        String orderId = "ORD-MOMO-100";
        String requestId = "REQ-100";
        long amount = 50000;
        String orderInfo = "Payment for trip";
        String orderType = "momo_wallet";
        long transId = 2309182391L;
        int resultCode = 0;
        String message = "Success";
        String payType = "qr";
        long responseTime = 1690000000000L;
        String extraData = "extra-data-123";

        String rawSignature = "accessKey=" + accessKey +
                "&amount=" + amount +
                "&extraData=" + extraData +
                "&message=" + message +
                "&orderId=" + orderId +
                "&orderInfo=" + orderInfo +
                "&orderType=" + orderType +
                "&partnerCode=" + partnerCode +
                "&payType=" + payType +
                "&requestId=" + requestId +
                "&responseTime=" + responseTime +
                "&resultCode=" + resultCode +
                "&transId=" + transId;

        String signature = CryptoUtils.hmacSha256(rawSignature, secretKey);

        String jsonPayload = """
                {
                  "partnerCode": "%s",
                  "orderId": "%s",
                  "requestId": "%s",
                  "amount": %d,
                  "orderInfo": "%s",
                  "orderType": "%s",
                  "transId": %d,
                  "resultCode": %d,
                  "message": "%s",
                  "payType": "%s",
                  "responseTime": %d,
                  "extraData": "%s",
                  "signature": "%s"
                }
                """.formatted(
                partnerCode, orderId, requestId, amount, orderInfo,
                orderType, transId, resultCode, message, payType, responseTime, extraData, signature
        );

        PaymentWebhookResult result = provider.verifyAndParseWebhook(jsonPayload, Map.of());

        assertThat(result).isNotNull();
        assertThat(result.provider()).isEqualTo("MOMO");
        assertThat(result.providerOrderId()).isEqualTo(orderId);
        assertThat(result.providerTransactionId()).isEqualTo(String.valueOf(transId));
        assertThat(result.amount()).isEqualByComparingTo(new BigDecimal("50000.00"));
        assertThat(result.successful()).isTrue();
    }

    @Test
    @DisplayName("Webhook Security: Rejects callback with tampered amount")
    void rejectsCallbackWithTamperedAmount() {
        String orderId = "ORD-MOMO-200";
        String requestId = "REQ-200";
        long originalAmount = 50000;
        long tamperedAmount = 1000; // attacker tries to underpay
        String orderInfo = "Payment for trip";
        String orderType = "momo_wallet";
        long transId = 2309182391L;
        int resultCode = 0;
        String message = "Success";
        String payType = "qr";
        long responseTime = 1690000000000L;
        String extraData = "";

        // Sign with original amount
        String rawSignature = "accessKey=" + accessKey +
                "&amount=" + originalAmount +
                "&extraData=" + extraData +
                "&message=" + message +
                "&orderId=" + orderId +
                "&orderInfo=" + orderInfo +
                "&orderType=" + orderType +
                "&partnerCode=" + partnerCode +
                "&payType=" + payType +
                "&requestId=" + requestId +
                "&responseTime=" + responseTime +
                "&resultCode=" + resultCode +
                "&transId=" + transId;

        String signature = CryptoUtils.hmacSha256(rawSignature, secretKey);

        // Body sends tampered amount but signature made with original amount
        String jsonPayload = """
                {
                  "partnerCode": "%s",
                  "orderId": "%s",
                  "requestId": "%s",
                  "amount": %d,
                  "orderInfo": "%s",
                  "orderType": "%s",
                  "transId": %d,
                  "resultCode": %d,
                  "message": "%s",
                  "payType": "%s",
                  "responseTime": %d,
                  "extraData": "%s",
                  "signature": "%s"
                }
                """.formatted(
                partnerCode, orderId, requestId, tamperedAmount, orderInfo,
                orderType, transId, resultCode, message, payType, responseTime, extraData, signature
        );

        assertThatThrownBy(() -> provider.verifyAndParseWebhook(jsonPayload, Map.of()))
                .isInstanceOf(PaymentSecurityException.class)
                .hasMessageContaining("Invalid HMAC signature");
    }

    @Test
    @DisplayName("Webhook Security: Rejects callback with missing signature")
    void rejectsCallbackWithMissingSignature() {
        String jsonPayload = """
                {
                  "partnerCode": "%s",
                  "orderId": "ORD-1",
                  "amount": 50000,
                  "resultCode": 0
                }
                """.formatted(partnerCode);

        assertThatThrownBy(() -> provider.verifyAndParseWebhook(jsonPayload, Map.of()))
                .isInstanceOf(PaymentSecurityException.class)
                .hasMessageContaining("Missing signature");
    }

    @Test
    @DisplayName("Webhook Security: Rejects callback with wrong partnerCode")
    void rejectsCallbackWithWrongPartnerCode() {
        String wrongPartner = "UNKNOWN_PARTNER";
        String rawSignature = "accessKey=" + accessKey +
                "&amount=50000&extraData=&message=Success&orderId=ORD-1&orderInfo=Info&orderType=momo_wallet&partnerCode=" +
                wrongPartner + "&payType=qr&requestId=REQ-1&responseTime=1000&resultCode=0&transId=123";
        String signature = CryptoUtils.hmacSha256(rawSignature, secretKey);

        String jsonPayload = """
                {
                  "partnerCode": "%s",
                  "orderId": "ORD-1",
                  "requestId": "REQ-1",
                  "amount": 50000,
                  "orderInfo": "Info",
                  "orderType": "momo_wallet",
                  "transId": 123,
                  "resultCode": 0,
                  "message": "Success",
                  "payType": "qr",
                  "responseTime": 1000,
                  "extraData": "",
                  "signature": "%s"
                }
                """.formatted(wrongPartner, signature);

        assertThatThrownBy(() -> provider.verifyAndParseWebhook(jsonPayload, Map.of()))
                .isInstanceOf(PaymentSecurityException.class)
                .hasMessageContaining("Partner code mismatch");
    }
}
