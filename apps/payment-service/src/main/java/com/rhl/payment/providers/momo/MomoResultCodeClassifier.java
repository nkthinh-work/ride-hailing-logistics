package com.rhl.payment.providers.momo;

import com.rhl.payment.enums.PaymentAttemptStatus;

/**
 * Authoritative MoMo ResultCode Classifier (P0 Safety Remediation).
 * Evaluates MoMo API v2/v3 result codes per official developer specifications:
 * - 0: Successful transaction (SUCCEEDED)
 * - 1000: Initialized, pending customer action (PROCESSING)
 * - 7000, 7002: In-progress processing by provider / partner (PROCESSING)
 * - 9000: Authorized but capture required (PROCESSING)
 * - 99, 1080, 1081: Temporary system error or lock (UNKNOWN - requires query reconciliation)
 * - Terminal failure codes (1001-1007, 1017, 2001, 4100): Definite failure (FAILED)
 *
 * References:
 * - https://developers.momo.vn/v3/vi/docs/payment/api/result-handling/resultcode/
 * - https://developers.momo.vn/v3/vi/docs/payment/api/result-handling/notification/
 * - https://developers.momo.vn/v3/vi/docs/payment/api/payment-api/query/
 */
public final class MomoResultCodeClassifier {

    private MomoResultCodeClassifier() {
        // Utility class
    }

    public static PaymentAttemptStatus classify(int resultCode) {
        if (resultCode == 0) {
            return PaymentAttemptStatus.SUCCEEDED;
        }

        return switch (resultCode) {
            case 1000, 7000, 7002, 9000 -> PaymentAttemptStatus.PROCESSING;
            case 1001, 1002, 1003, 1004, 1005, 1006, 1007, 1017, 1026, 2001, 2007, 4100, 4101 -> PaymentAttemptStatus.FAILED;
            default -> PaymentAttemptStatus.UNKNOWN;
        };
    }

    public static PaymentAttemptStatus classify(String resultCodeStr) {
        if (resultCodeStr == null || resultCodeStr.isBlank()) {
            return PaymentAttemptStatus.UNKNOWN;
        }
        try {
            return classify(Integer.parseInt(resultCodeStr.trim()));
        } catch (NumberFormatException e) {
            return PaymentAttemptStatus.UNKNOWN;
        }
    }

    public static boolean isRetryable(PaymentAttemptStatus status) {
        return status == PaymentAttemptStatus.PROCESSING || status == PaymentAttemptStatus.UNKNOWN;
    }

    public static boolean isSuccessful(int resultCode) {
        return resultCode == 0;
    }

    public static boolean isProcessing(int resultCode) {
        return resultCode == 1000 || resultCode == 7000 || resultCode == 7002 || resultCode == 9000;
    }

    public static boolean isRetryableOrUnknown(int resultCode) {
        return resultCode == 99 || resultCode == 1080 || resultCode == 1081;
    }

    public static boolean isTerminalFailure(int resultCode) {
        return !isSuccessful(resultCode) && !isProcessing(resultCode) && !isRetryableOrUnknown(resultCode);
    }
}
