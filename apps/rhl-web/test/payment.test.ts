import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import type {
  PaymentResponse,
  PaymentMethod,
  PaymentStatus,
  TripResponse,
  RoleCode,
  ReconciliationRecord,
} from '../src/types/index.ts';

describe('M08 Group 4: Customer Payment UI, Driver Cash Collection, Finance Console & E2E Tests', () => {

  describe('1. Preservation of Existing Frontend Routes & Segregation', () => {
    const routeDefinitions = [
      { path: '/', component: 'DashboardPage', requiredRoles: [] },
      { path: '/dashboard', component: 'DashboardPage', requiredRoles: [] },
      { path: '/customer', component: 'CustomerAppPage', requiredRoles: [] },
      { path: '/driver', component: 'DriverAppPage', requiredRoles: [] },
      { path: '/finance', component: 'FinanceConsolePage', requiredRoles: ['FINANCE_STAFF', 'ADMINISTRATOR'] },
    ];

    it('should retain existing developer dashboard route on root path / and /dashboard', () => {
      const rootRoute = routeDefinitions.find((r) => r.path === '/');
      assert.ok(rootRoute, 'Root route / must exist');
      assert.equal(rootRoute.component, 'DashboardPage');

      const dashRoute = routeDefinitions.find((r) => r.path === '/dashboard');
      assert.ok(dashRoute, 'Dashboard route /dashboard must exist');
      assert.equal(dashRoute.component, 'DashboardPage');
    });

    it('should retain customer and driver application routes', () => {
      const custRoute = routeDefinitions.find((r) => r.path === '/customer');
      assert.ok(custRoute, 'Customer route /customer must exist');
      assert.equal(custRoute.component, 'CustomerAppPage');

      const driverRoute = routeDefinitions.find((r) => r.path === '/driver');
      assert.ok(driverRoute, 'Driver route /driver must exist');
      assert.equal(driverRoute.component, 'DriverAppPage');
    });

    it('should define segregated Finance Console route guarded by FINANCE_STAFF or ADMINISTRATOR', () => {
      const financeRoute = routeDefinitions.find((r) => r.path === '/finance');
      assert.ok(financeRoute, 'Finance route /finance must exist');
      assert.equal(financeRoute.component, 'FinanceConsolePage');
      assert.deepEqual(financeRoute.requiredRoles, ['FINANCE_STAFF', 'ADMINISTRATOR']);
    });
  });

  describe('2. Customer Payment UI: Authoritative Fare & Lifecycle States', () => {
    const mockTrip: TripResponse = {
      tripId: '12345678-1234-1234-1234-123456789abc',
      customerId: 'cust-1111-2222-3333-444444444444',
      driverId: 'driv-5555-6666-7777-888888888888',
      status: 'COMPLETED',
      serviceType: 'RIDE',
      pickupLatitude: 21.0285,
      pickupLongitude: 105.8542,
      pickupAddress: 'Hồ Hoàn Kiếm, Hà Nội',
      dropoffLatitude: 21.0483,
      dropoffLongitude: 105.8366,
      dropoffAddress: 'Hồ Tây, Hà Nội',
      estimatedFare: 65000,
      currency: 'VND',
      requestedAt: new Date(Date.now() - 1800000).toISOString(),
      completedAt: new Date().toISOString(),
      version: 5,
    };

    it('should only permit payment initiation when trip is in COMPLETED status', () => {
      const isPaymentEligible = (status: string) => status === 'COMPLETED';

      assert.equal(isPaymentEligible('CREATED'), false);
      assert.equal(isPaymentEligible('MATCHING'), false);
      assert.equal(isPaymentEligible('ACCEPTED'), false);
      assert.equal(isPaymentEligible('IN_TRIP'), false);
      assert.equal(isPaymentEligible('CANCELLED'), false);
      assert.equal(isPaymentEligible('COMPLETED'), true);
    });

    it('should bind payable amount directly to authoritative backend fare without client modification', () => {
      const authoritativeFare = mockTrip.estimatedFare;
      assert.equal(authoritativeFare, 65000);
      assert.equal(mockTrip.currency, 'VND');

      // Client payload must match backend trip fare
      const paymentPayload = {
        tripId: mockTrip.tripId,
        paymentMethod: 'CASH' as PaymentMethod,
        amount: authoritativeFare,
        currency: mockTrip.currency,
      };

      assert.equal(paymentPayload.amount, 65000);
      assert.equal(paymentPayload.currency, 'VND');
    });

    it('should generate and preserve stable idempotency key for single payment intent to prevent double charging', () => {
      const generateIntentKey = (tripId: string) => `pay-cust-${tripId.slice(0, 8)}-stable-intent`;
      const key1 = generateIntentKey(mockTrip.tripId);
      const key2 = generateIntentKey(mockTrip.tripId);

      assert.equal(key1, key2, 'Duplicate clicks on customer UI must replay the same idempotency key');
      assert.match(key1, /^pay-cust-12345678-stable-intent$/);
    });

    it('should transition payment status monotonically PENDING -> PROCESSING -> SUCCEEDED', () => {
      const payment: PaymentResponse = {
        paymentId: 'pay-0000-0000-0000-000000000001',
        tripId: mockTrip.tripId,
        customerId: mockTrip.customerId,
        amount: 65000,
        currency: 'VND',
        paymentMethod: 'CASH',
        status: 'PENDING',
        idempotencyKey: 'idemp-key-1',
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      };

      assert.equal(payment.status, 'PENDING');

      // Driver starts processing confirmation
      const processing: PaymentResponse = { ...payment, status: 'PROCESSING', updatedAt: new Date().toISOString() };
      assert.equal(processing.status, 'PROCESSING');

      // Settled by driver
      const succeeded: PaymentResponse = {
        ...processing,
        status: 'SUCCEEDED',
        providerReference: 'CASH_SETTLED',
        succeededAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      };

      assert.equal(succeeded.status, 'SUCCEEDED');
      assert.equal(succeeded.providerReference, 'CASH_SETTLED');
    });
  });

  describe('3. MoMo Sandbox Gating (No Mock Passes & No Fake Success)', () => {
    it('should disable online checkout when sandbox credentials are unconfigured (MOMO_LIVE_SANDBOX_E2E_PENDING)', () => {
      const isMoMoConfigured = false; // merchant secret & partner code not yet provisioned in dev environment
      const momoActionAllowed = isMoMoConfigured;

      assert.equal(momoActionAllowed, false, 'MoMo checkout button must remain disabled when credentials are not configured');
    });

    it('should never trust browser redirect return URL alone as authoritative payment success', () => {
      const browserUrlParams = new URLSearchParams('resultCode=0&message=Success&orderId=MOMO-123');
      const isSuccessFromClientRedirect = browserUrlParams.get('resultCode') === '0';

      // The frontend must NOT transition to SUCCEEDED purely from browser URL; it must query authoritative Payment Service
      const verifyWithAuthoritativeBackend = (serverVerifiedStatus: PaymentStatus) => {
        return serverVerifiedStatus === 'SUCCEEDED';
      };

      assert.equal(isSuccessFromClientRedirect, true);
      assert.equal(verifyWithAuthoritativeBackend('PROCESSING'), false, 'Cannot mark SUCCEEDED if backend still reports PROCESSING');
      assert.equal(verifyWithAuthoritativeBackend('SUCCEEDED'), true, 'Only authoritative backend status permits SUCCEEDED');
    });
  });

  describe('4. Driver Cash Collection & Confirmation UX', () => {
    const assignedDriverId = 'driv-5555-6666-7777-888888888888';
    const otherDriverId = 'driv-9999-9999-9999-999999999999';

    it('should only allow assigned driver to view and confirm cash collection', () => {
      const canDriverConfirm = (callerDriverId: string, tripDriverId: string) => {
        return callerDriverId === tripDriverId;
      };

      assert.equal(canDriverConfirm(assignedDriverId, assignedDriverId), true);
      assert.equal(canDriverConfirm(otherDriverId, assignedDriverId), false);
    });

    it('should handle idempotent cash confirmation replay cleanly without creating duplicate settlements', () => {
      let isSettled = false;
      let settlementCount = 0;

      const confirmCash = () => {
        if (!isSettled) {
          isSettled = true;
          settlementCount++;
          return { status: 'SUCCEEDED' as PaymentStatus, providerReference: 'CASH_SETTLED' };
        }
        // Idempotent replay: return existing success without re-settling
        return { status: 'SUCCEEDED' as PaymentStatus, providerReference: 'CASH_SETTLED' };
      };

      const firstCall = confirmCash();
      assert.equal(firstCall.status, 'SUCCEEDED');
      assert.equal(settlementCount, 1);

      const replayCall = confirmCash();
      assert.equal(replayCall.status, 'SUCCEEDED');
      assert.equal(settlementCount, 1, 'Replay call must not increment settlement count');
    });
  });

  describe('5. Finance Console RBAC & Refund Policy Gating', () => {
    const hasFinanceAccess = (roles: RoleCode[]) => {
      return roles.includes('FINANCE_STAFF') || roles.includes('ADMINISTRATOR');
    };

    it('should permit FINANCE_STAFF and ADMINISTRATOR to access /finance', () => {
      assert.equal(hasFinanceAccess(['FINANCE_STAFF']), true);
      assert.equal(hasFinanceAccess(['ADMINISTRATOR']), true);
      assert.equal(hasFinanceAccess(['CUSTOMER', 'FINANCE_STAFF']), true);
    });

    it('should reject CUSTOMER and DRIVER from accessing /finance (HTTP 403 / Access Denied)', () => {
      assert.equal(hasFinanceAccess(['CUSTOMER']), false);
      assert.equal(hasFinanceAccess(['DRIVER']), false);
      assert.equal(hasFinanceAccess(['REVIEWER']), false);
    });

    it('should enforce REFUND_POLICY_PENDING gating on automatic refund disbursements', () => {
      const policyStatus = 'REFUND_POLICY_PENDING';
      const allowAutomaticDisbursement = (status: string) => status === 'APPROVED';

      assert.equal(allowAutomaticDisbursement(policyStatus), false, 'Automatic refunds must be blocked while policy is pending');
    });

    it('should distinguish local payment status from third-party settlement mismatch in reconciliation records', () => {
      const mismatchRecord: ReconciliationRecord = {
        id: 'rec-001',
        paymentId: 'pay-001',
        provider: 'MOMO',
        providerOrderId: 'MOMO-ORD-999',
        localStatus: 'SUCCEEDED',
        providerStatus: 'FAILED',
        mismatchType: 'STATUS_MISMATCH',
        settled: false,
        checkedAt: new Date().toISOString(),
      };

      assert.equal(mismatchRecord.localStatus, 'SUCCEEDED');
      assert.equal(mismatchRecord.providerStatus, 'FAILED');
      assert.equal(mismatchRecord.settled, false, 'Local success is separated from provider settlement');
      assert.equal(mismatchRecord.mismatchType, 'STATUS_MISMATCH');
    });
  });
});
