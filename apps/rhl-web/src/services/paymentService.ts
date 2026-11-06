import type {
  PaymentResponse,
  InitiatePaymentRequest,
  RefundEligibilityResponse,
  RefundResponse,
  ReconciliationRecord,
} from '../types/index.ts';
import { request } from './apiClient.ts';

export const paymentService = {
  /**
   * Initiates payment for a completed trip (FR-PAY-001..005).
   * Calls POST /api/v1/payments with authoritative Idempotency-Key header.
   */
  initiatePayment: async (
    body: InitiatePaymentRequest,
    idempotencyKey: string
  ): Promise<PaymentResponse> => {
    return request<PaymentResponse>('/api/v1/payments', {
      method: 'POST',
      headers: {
        'Idempotency-Key': idempotencyKey,
      },
      body: JSON.stringify(body),
    });
  },

  /**
   * Retrieves payment transaction by ID.
   * Calls GET /api/v1/payments/{paymentId}
   */
  getPaymentById: async (paymentId: string): Promise<PaymentResponse> => {
    return request<PaymentResponse>(`/api/v1/payments/${paymentId}`);
  },

  /**
   * Retrieves payments for a trip by customer.
   * Calls GET /api/v1/payments/trips/{tripId}
   */
  getPaymentsByTripId: async (tripId: string): Promise<PaymentResponse[]> => {
    return request<PaymentResponse[]>(`/api/v1/payments/trips/${tripId}`);
  },

  /**
   * Retrieves customer's payment history.
   * Calls GET /api/v1/payments
   */
  getMyPayments: async (): Promise<PaymentResponse[]> => {
    return request<PaymentResponse[]>('/api/v1/payments');
  },

  /**
   * Assigned driver retrieves payment for a trip to collect cash.
   * Calls GET /api/v1/payments/driver/trips/{tripId}
   */
  getDriverTripPayments: async (tripId: string): Promise<PaymentResponse[]> => {
    return request<PaymentResponse[]>(`/api/v1/payments/driver/trips/${tripId}`);
  },

  /**
   * Driver confirms cash collection upon trip completion (FR-PAY-004).
   * Calls POST /api/v1/payments/{paymentId}/cash/confirm
   */
  confirmCashPayment: async (paymentId: string): Promise<PaymentResponse> => {
    return request<PaymentResponse>(`/api/v1/payments/${paymentId}/cash/confirm`, {
      method: 'POST',
    });
  },

  /**
   * Checks refund eligibility for a payment.
   * Calls GET /api/v1/payments/{paymentId}/refunds/eligibility
   */
  checkRefundEligibility: async (paymentId: string): Promise<RefundEligibilityResponse> => {
    return request<RefundEligibilityResponse>(
      `/api/v1/payments/${paymentId}/refunds/eligibility`
    );
  },

  /**
   * Retrieves refund history for a payment.
   * Calls GET /api/v1/payments/{paymentId}/refunds
   */
  getPaymentRefunds: async (paymentId: string): Promise<RefundResponse[]> => {
    return request<RefundResponse[]>(`/api/v1/payments/${paymentId}/refunds`);
  },

  /**
   * Finance Staff / Admin: Retrieves reconciliation mismatch records.
   * Calls GET /api/v1/admin/payments/reconciliation/mismatches
   */
  getReconciliationMismatches: async (): Promise<ReconciliationRecord[]> => {
    return request<ReconciliationRecord[]>('/api/v1/admin/payments/reconciliation/mismatches');
  },

  /**
   * Finance Staff / Admin: Triggers a reconciliation run.
   * Calls POST /api/v1/admin/payments/reconciliation/run
   */
  runReconciliation: async (): Promise<{
    processed: number;
    mismatches: number;
    runAt: string;
  }> => {
    return request<{ processed: number; mismatches: number; runAt: string }>(
      '/api/v1/admin/payments/reconciliation/run',
      {
        method: 'POST',
      }
    );
  },

  /**
   * Finance Staff / Admin: Approves a refund request (governed by policy gating).
   * Calls POST /api/v1/finance/refunds/{refundId}/approve
   */
  approveRefund: async (refundId: string): Promise<RefundResponse> => {
    return request<RefundResponse>(`/api/v1/finance/refunds/${refundId}/approve`, {
      method: 'POST',
    });
  },

  /**
   * Finance Staff / Admin: Rejects a refund request.
   * Calls POST /api/v1/finance/refunds/{refundId}/reject
   */
  rejectRefund: async (refundId: string, reason: string): Promise<RefundResponse> => {
    return request<RefundResponse>(`/api/v1/finance/refunds/${refundId}/reject`, {
      method: 'POST',
      body: JSON.stringify({ reason }),
    });
  },
};
