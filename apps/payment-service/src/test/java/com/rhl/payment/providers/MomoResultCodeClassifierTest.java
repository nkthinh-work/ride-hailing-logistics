package com.rhl.payment.providers;

import com.rhl.payment.enums.PaymentAttemptStatus;
import com.rhl.payment.providers.momo.MomoResultCodeClassifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MoMo Result Code Classification Safety Tests (P0)")
class MomoResultCodeClassifierTest {

    @Test
    @DisplayName("Code 0 must be classified as SUCCEEDED")
    void codeZero_isSucceeded() {
        assertThat(MomoResultCodeClassifier.classify(0)).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
        assertThat(MomoResultCodeClassifier.classify("0")).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
        assertThat(MomoResultCodeClassifier.isRetryable(PaymentAttemptStatus.SUCCEEDED)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {1000, 7000, 7002, 9000})
    @DisplayName("In-flight, user action pending, or capture pending codes must be classified as PROCESSING")
    void inFlightCodes_areProcessing(int code) {
        assertThat(MomoResultCodeClassifier.classify(code)).isEqualTo(PaymentAttemptStatus.PROCESSING);
        assertThat(MomoResultCodeClassifier.classify(String.valueOf(code))).isEqualTo(PaymentAttemptStatus.PROCESSING);
        assertThat(MomoResultCodeClassifier.isRetryable(PaymentAttemptStatus.PROCESSING)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {99, 1080, 1081})
    @DisplayName("System busy or unknown transient codes must be classified as UNKNOWN")
    void transientUnknownCodes_areUnknown(int code) {
        assertThat(MomoResultCodeClassifier.classify(code)).isEqualTo(PaymentAttemptStatus.UNKNOWN);
        assertThat(MomoResultCodeClassifier.classify(String.valueOf(code))).isEqualTo(PaymentAttemptStatus.UNKNOWN);
        assertThat(MomoResultCodeClassifier.isRetryable(PaymentAttemptStatus.UNKNOWN)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {1001, 1002, 1003, 1004, 1005, 1006, 1007, 4101})
    @DisplayName("Terminal failure codes must be classified as FAILED")
    void terminalFailureCodes_areFailed(int code) {
        assertThat(MomoResultCodeClassifier.classify(code)).isEqualTo(PaymentAttemptStatus.FAILED);
        assertThat(MomoResultCodeClassifier.classify(String.valueOf(code))).isEqualTo(PaymentAttemptStatus.FAILED);
        assertThat(MomoResultCodeClassifier.isRetryable(PaymentAttemptStatus.FAILED)).isFalse();
    }

    @Test
    @DisplayName("Null or empty result code defaults to UNKNOWN")
    void nullOrEmpty_defaultsToUnknown() {
        assertThat(MomoResultCodeClassifier.classify((String) null)).isEqualTo(PaymentAttemptStatus.UNKNOWN);
        assertThat(MomoResultCodeClassifier.classify("")).isEqualTo(PaymentAttemptStatus.UNKNOWN);
    }

    @Test
    @DisplayName("Unrecognized code defaults to UNKNOWN for financial safety")
    void unrecognizedCode_defaultsToUnknown() {
        assertThat(MomoResultCodeClassifier.classify(99999)).isEqualTo(PaymentAttemptStatus.UNKNOWN);
    }
}
