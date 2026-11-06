// Payment & Financial Reconciliation Types (M08)

export type PaymentMethod = 'CASH' | 'DIGITAL_WALLET' | 'CREDIT_CARD';

export type PaymentStatus =
  | 'PENDING'
  | 'PROCESSING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELLED';

export type RefundStatus =
  | 'PENDING'
  | 'APPROVED'
  | 'PROCESSING'
  | 'SUCCEEDED'
  | 'REJECTED'
  | 'FAILED';

export interface PaymentStatusHistoryEntry {
  fromStatus?: PaymentStatus | null;
  toStatus: PaymentStatus;
  actor?: string | null;
  actorRole?: string | null;
  reason?: string | null;
  createdAt: string;
}

export interface PaymentResponse {
  paymentId: string;
  tripId: string;
  customerId: string;
  amount: number;
  currency: string;
  paymentMethod: PaymentMethod;
  status: PaymentStatus;
  idempotencyKey: string;
  providerReference?: string | null;
  checkoutUrl?: string | null;
  createdAt: string;
  updatedAt: string;
  succeededAt?: string | null;
  history?: PaymentStatusHistoryEntry[];
}

export interface InitiatePaymentRequest {
  tripId: string;
  paymentMethod: PaymentMethod;
  currency?: string;
  amount?: number | null;
}

export interface RefundEligibilityResponse {
  paymentId: string;
  eligible: boolean;
  reason?: string;
  availableAmount?: number;
  currency?: string;
  policyStatus: string;
}

export interface RefundResponse {
  refundId: string;
  paymentId: string;
  customerId: string;
  tripId: string;
  amount: number;
  currency: string;
  reason: string;
  status: RefundStatus;
  idempotencyKey: string;
  providerRefundId?: string | null;
  requestedBy: string;
  reviewedBy?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface ReconciliationRecord {
  id: string;
  paymentId: string;
  provider: string;
  providerOrderId: string;
  localStatus: PaymentStatus;
  providerStatus: string;
  mismatchType?: string;
  settled: boolean;
  checkedAt: string;
  resolvedAt?: string | null;
}
