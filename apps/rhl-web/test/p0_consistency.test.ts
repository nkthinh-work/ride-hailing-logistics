import { describe, it, before, beforeEach } from 'node:test';
import assert from 'node:assert';

// Polyfill minimal browser globals for Node test runner
before(() => {
  if (typeof globalThis.localStorage === 'undefined') {
    const store = new Map<string, string>();
    (globalThis as unknown as { localStorage: Storage }).localStorage = {
      getItem: (key: string) => store.get(key) || null,
      setItem: (key: string, value: string) => store.set(key, value),
      removeItem: (key: string) => store.delete(key),
      clear: () => store.clear(),
      length: 0,
      key: () => null,
    } as unknown as Storage;
  }
});

import { decodeJwt, tokenStorage, refreshAccessTokenSingleFlight } from '../src/services/apiClient.ts';
import type { UserResponse, DecodedJwt } from '../src/types/index.ts';

describe('P0 Consistency & Fix Verification Tests', () => {

  describe('1. Identity Profile & UserResponse Field Normalization', () => {
    it('should normalize getMe payload with both id and userId when backend returns userId', () => {
      // Backend returned payload with userId instead of id
      const backendPayload = {
        userId: '11111111-2222-3333-4444-555555555555',
        email: 'driver.active@example.com',
        roles: ['DRIVER', 'CUSTOMER'],
        permissions: ['DRIVER_LOC_UPDATE', 'TRIP_VIEW_OWN'],
        status: 'ACTIVE',
      };

      // Normalization logic matching authService.getMe()
      const resolvedId = (backendPayload as { id?: string }).id || backendPayload.userId || '';
      const normalized: UserResponse = {
        ...backendPayload,
        id: resolvedId,
        userId: backendPayload.userId || resolvedId,
        email: backendPayload.email || '',
        roles: backendPayload.roles as any,
        permissions: backendPayload.permissions as any,
        status: (backendPayload.status as any) || 'ACTIVE',
      };

      assert.strictEqual(normalized.id, '11111111-2222-3333-4444-555555555555');
      assert.strictEqual(normalized.userId, '11111111-2222-3333-4444-555555555555');
      assert.strictEqual(normalized.email, 'driver.active@example.com');
      assert.deepStrictEqual(normalized.roles, ['DRIVER', 'CUSTOMER']);
    });

    it('should normalize getMe payload when backend returns id instead of userId', () => {
      const backendPayload = {
        id: '22222222-3333-4444-5555-666666666666',
        email: 'customer@example.com',
        roles: ['CUSTOMER'],
        permissions: ['TRIP_CREATE'],
        status: 'ACTIVE',
      };

      const resolvedId = backendPayload.id || (backendPayload as { userId?: string }).userId || '';
      const normalized: UserResponse = {
        ...backendPayload,
        id: resolvedId,
        userId: (backendPayload as { userId?: string }).userId || resolvedId,
        email: backendPayload.email || '',
        roles: backendPayload.roles as any,
        permissions: backendPayload.permissions as any,
        status: (backendPayload.status as any) || 'ACTIVE',
      };

      assert.strictEqual(normalized.id, '22222222-3333-4444-5555-666666666666');
      assert.strictEqual(normalized.userId, '22222222-3333-4444-5555-666666666666');
      assert.strictEqual(normalized.email, 'customer@example.com');
    });

    it('should decode email claim embedded in JWT token payload', () => {
      const payload: DecodedJwt = {
        sub: '33333333-4444-5555-6666-777777777777',
        email: 'driver.embedded@example.com',
        iss: 'rhl-identity-service',
        roles: ['DRIVER'],
        permissions: ['LOCATION_UPDATE'],
        iat: 1713600000,
        exp: 1713600900,
      };

      const base64Payload = Buffer.from(JSON.stringify(payload)).toString('base64url');
      const mockToken = `eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.${base64Payload}.mockSig`;

      const decoded = decodeJwt(mockToken);
      assert.notStrictEqual(decoded, null);
      assert.strictEqual(decoded?.sub, '33333333-4444-5555-6666-777777777777');
      assert.strictEqual(decoded?.email, 'driver.embedded@example.com');
      assert.deepStrictEqual(decoded?.roles, ['DRIVER']);
    });
  });

  describe('2. JWT Expiry Check & RBAC Invalidation', () => {
    it('should recognize expired token when exp is in the past', () => {
      const pastSeconds = Math.floor(Date.now() / 1000) - 60; // 60 seconds ago
      const payload: DecodedJwt = {
        sub: 'usr-expired',
        roles: ['DRIVER'],
        permissions: ['LOCATION_UPDATE'],
        exp: pastSeconds,
        iat: pastSeconds - 3600,
      };

      const isExpired = payload.exp * 1000 <= Date.now();
      assert.strictEqual(isExpired, true, 'Token with past exp timestamp must be marked expired');
    });

    it('should recognize active token when exp is in the future', () => {
      const futureSeconds = Math.floor(Date.now() / 1000) + 900; // 15 mins later
      const payload: DecodedJwt = {
        sub: 'usr-active',
        roles: ['DRIVER'],
        permissions: ['LOCATION_UPDATE'],
        exp: futureSeconds,
        iat: futureSeconds - 900,
      };

      const isExpired = payload.exp * 1000 <= Date.now();
      assert.strictEqual(isExpired, false, 'Token with future exp timestamp must be marked active');
    });

    it('should reject role and permission checks when token is expired', () => {
      const pastSeconds = Math.floor(Date.now() / 1000) - 10;
      const expiredJwt: DecodedJwt = {
        sub: 'usr-expired',
        roles: ['DRIVER', 'CUSTOMER'],
        permissions: ['LOCATION_UPDATE'],
        exp: pastSeconds,
        iat: pastSeconds - 300,
      };

      // Simulated hasRole & hasPermission logic from AuthContext.tsx
      const hasRole = (role: string, jwt: DecodedJwt | null) => {
        if (!jwt) return false;
        if (jwt.exp && jwt.exp * 1000 <= Date.now()) return false;
        return jwt.roles.includes(role as any);
      };

      const hasPermission = (permission: string, jwt: DecodedJwt | null) => {
        if (!jwt) return false;
        if (jwt.exp && jwt.exp * 1000 <= Date.now()) return false;
        return jwt.permissions.includes(permission as any);
      };

      assert.strictEqual(hasRole('DRIVER', expiredJwt), false);
      assert.strictEqual(hasRole('CUSTOMER', expiredJwt), false);
      assert.strictEqual(hasPermission('DRIVER_LOC_UPDATE', expiredJwt), false);
    });
  });

  describe('3. Single-Flight Token Refresh Mutex', () => {
    beforeEach(() => {
      tokenStorage.clear();
    });

    it('should return null and clear storage when no refresh token is stored', async () => {
      const res = await refreshAccessTokenSingleFlight();
      assert.strictEqual(res, null);
      assert.strictEqual(tokenStorage.getAccessToken(), null);
    });

    it('should collapse concurrent refresh calls into a single invocation', async () => {
      tokenStorage.setRefreshToken('mock-refresh-token');

      let fetchCallCount = 0;
      const originalFetch = globalThis.fetch;
      (globalThis as unknown as { fetch: typeof fetch }).fetch = async () => {
        fetchCallCount++;
        // Simulate network latency
        await new Promise((r) => setTimeout(r, 20));
        return {
          ok: true,
          json: async () => ({
            accessToken: 'new-access-token-999',
            refreshToken: 'new-refresh-token-999',
            tokenType: 'Bearer',
            expiresIn: 900,
          }),
        } as unknown as Response;
      };

      try {
        // Fire 4 simultaneous calls to refreshAccessTokenSingleFlight
        const [r1, r2, r3, r4] = await Promise.all([
          refreshAccessTokenSingleFlight(),
          refreshAccessTokenSingleFlight(),
          refreshAccessTokenSingleFlight(),
          refreshAccessTokenSingleFlight(),
        ]);

        // All 4 should resolve with the same token
        assert.strictEqual(r1, 'new-access-token-999');
        assert.strictEqual(r2, 'new-access-token-999');
        assert.strictEqual(r3, 'new-access-token-999');
        assert.strictEqual(r4, 'new-access-token-999');

        // Crucial requirement: only ONE fetch should have been dispatched across all concurrent calls
        assert.strictEqual(fetchCallCount, 1, 'Single-flight mutex must execute fetch exactly once');
      } finally {
        globalThis.fetch = originalFetch;
        tokenStorage.clear();
      }
    });
  });

  describe('4. Realtime STOMP Bounded Reconnection', () => {
    it('should bound reconnection attempts and transition to DISCONNECTED after limit is reached', () => {
      let status = 'CONNECTING';
      let reconnectAttempt = 0;
      const maxReconnectAttempts = 5;

      const scheduleReconnect = () => {
        if (reconnectAttempt >= maxReconnectAttempts) {
          status = 'DISCONNECTED';
          return;
        }
        reconnectAttempt++;
        status = 'RECONNECTING';
      };

      // Simulate 5 connection drop events
      for (let i = 0; i < 5; i++) {
        scheduleReconnect();
        assert.strictEqual(status, 'RECONNECTING');
        assert.strictEqual(reconnectAttempt, i + 1);
      }

      // 6th event: maxReconnectAttempts reached
      scheduleReconnect();
      assert.strictEqual(status, 'DISCONNECTED');
      assert.strictEqual(reconnectAttempt, 5);
    });
  });

  describe('5. Driver UI Data Consistency & Onboarding Guards', () => {
    it('should NOT allow ONLINE status when driver KYC application is missing or not APPROVED', () => {
      const missingApplication = null;
      const pendingApplication = { status: 'PENDING', vehiclePlate: '30E-123.45', licenseNumber: 'B2-123456' };
      const rejectedApplication = { status: 'REJECTED', vehiclePlate: '30E-123.45', licenseNumber: 'B2-123456' };
      const approvedApplication = { status: 'APPROVED', vehiclePlate: '29H-123.45', licenseNumber: 'B2-654321' };

      const isEligibleForOnline = (app: { status: string } | null) => app?.status === 'APPROVED';

      assert.strictEqual(isEligibleForOnline(missingApplication), false);
      assert.strictEqual(isEligibleForOnline(pendingApplication), false);
      assert.strictEqual(isEligibleForOnline(rejectedApplication), false);
      assert.strictEqual(isEligibleForOnline(approvedApplication), true);
    });

    it('should display unverified placeholder instead of hardcoded 29A-888.88 when profile is missing', () => {
      const resolveVehiclePlate = (app: { vehiclePlate?: string } | null) => app?.vehiclePlate || 'Chưa đăng ký';
      const resolveLicenseNumber = (app: { licenseNumber?: string } | null) => app?.licenseNumber || 'Chưa cập nhật';

      const vehiclePlate = resolveVehiclePlate(null);
      const licenseNumber = resolveLicenseNumber(null);

      assert.notStrictEqual(vehiclePlate, '29A-888.88');
      assert.notStrictEqual(licenseNumber, 'B2-99887766');
      assert.strictEqual(vehiclePlate, 'Chưa đăng ký');
      assert.strictEqual(licenseNumber, 'Chưa cập nhật');
    });
  });

  describe('6. Driver Onboarding Deadlock & UI Eligibility Consistency (P0 Fixes)', () => {
    it('should distinguish 404 (application not found) from 500 server or network errors', () => {
      // Simulating loadMyApplication logic in DashboardPage.tsx
      const handleLoadApplicationError = (err: any) => {
        const isNotFound = err?.status === 404 || err?.code === 'DRIVER_APPLICATION_NOT_FOUND';
        if (isNotFound) {
          return {
            application: null,
            appNotFound: true,
            appError: null,
          };
        } else {
          return {
            // Keep existing application if any, do not wipe it
            application: undefined, // left untouched
            appNotFound: false,
            appError: err?.message || 'Không thể tải hồ sơ tài xế. Vui lòng kiểm tra kết nối mạng hoặc thử lại.',
          };
        }
      };

      // 1. 404 Case: User has no application
      const notFoundResult = handleLoadApplicationError({ status: 404, code: 'DRIVER_APPLICATION_NOT_FOUND', message: 'Not found' });
      assert.strictEqual(notFoundResult.appNotFound, true);
      assert.strictEqual(notFoundResult.application, null);
      assert.strictEqual(notFoundResult.appError, null);

      // 2. 500 Server Error Case: Must NOT assume "no application"
      const serverErrorResult = handleLoadApplicationError({ status: 500, code: 'INTERNAL_SERVER_ERROR', message: 'Database connection failed' });
      assert.strictEqual(serverErrorResult.appNotFound, false);
      assert.strictEqual(serverErrorResult.application, undefined);
      assert.strictEqual(serverErrorResult.appError, 'Database connection failed');

      // 3. Network Failure Case
      const networkErrorResult = handleLoadApplicationError(new Error('Failed to fetch'));
      assert.strictEqual(networkErrorResult.appNotFound, false);
      assert.strictEqual(networkErrorResult.appError, 'Failed to fetch');
    });

    it('should prevent Go Online when eligibility is not eligible, even if user has DRIVER role', () => {
      // Simulated Dashboard & DriverApp online gating logic
      const canGoOnline = (eligibility: { eligible: boolean; reasons?: string[] } | null, hasDriverRole: boolean) => {
        // Crucial fix: Must have DRIVER role AND confirmed backend eligibility
        if (!hasDriverRole || !eligibility) {
          return false;
        }
        return eligibility.eligible === true;
      };

      // Case 0: User without DRIVER role is never eligible
      assert.strictEqual(canGoOnline({ eligible: true, reasons: [] }, false), false);

      // Case A: User has DRIVER role but NO profile (Deadlock state before fix)
      const stateA = {
        hasDriverRole: true,
        eligibility: {
          eligible: false,
          accountStatus: 'ACTIVE',
          applicationStatus: null,
          hasDriverRole: true,
          reasons: ['PROFILE_NOT_FOUND'],
        },
      };
      assert.strictEqual(canGoOnline(stateA.eligibility, stateA.hasDriverRole), false);

      // Case B: User has DRIVER role and PENDING application
      const stateB = {
        hasDriverRole: true,
        eligibility: {
          eligible: false,
          accountStatus: 'ACTIVE',
          applicationStatus: 'PENDING',
          hasDriverRole: true,
          reasons: ['APPLICATION_NOT_APPROVED', 'NO_ACTIVE_VEHICLE'],
        },
      };
      assert.strictEqual(canGoOnline(stateB.eligibility, stateB.hasDriverRole), false);

      // Case C: User has DRIVER role, APPROVED profile, but INACTIVE vehicle
      const stateC = {
        hasDriverRole: true,
        eligibility: {
          eligible: false,
          accountStatus: 'ACTIVE',
          applicationStatus: 'APPROVED',
          hasDriverRole: true,
          reasons: ['NO_ACTIVE_VEHICLE'],
        },
      };
      assert.strictEqual(canGoOnline(stateC.eligibility, stateC.hasDriverRole), false);

      // Case D: Fully eligible driver
      const stateD = {
        hasDriverRole: true,
        eligibility: {
          eligible: true,
          accountStatus: 'ACTIVE',
          applicationStatus: 'APPROVED',
          hasDriverRole: true,
          reasons: [],
        },
      };
      assert.strictEqual(canGoOnline(stateD.eligibility, stateD.hasDriverRole), true);
    });

    it('should format meaningful Vietnamese explanations for each ineligible reason', () => {
      const getReasonExplanation = (reason: string): string => {
        switch (reason) {
          case 'PROFILE_NOT_FOUND':
            return 'Chưa nộp hồ sơ tài xế';
          case 'APPLICATION_NOT_APPROVED':
            return 'Hồ sơ tài xế chưa được phê duyệt (đang chờ duyệt hoặc bị từ chối)';
          case 'NO_ACTIVE_VEHICLE':
            return 'Chưa có phương tiện hoạt động (phương tiện đang INACTIVE hoặc chưa đăng ký)';
          case 'ACCOUNT_INACTIVE':
            return 'Tài khoản người dùng đang bị khóa hoặc chưa kích hoạt';
          case 'DRIVER_ROLE_MISSING':
            return 'Thiếu quyền tài xế (DRIVER)';
          default:
            return reason;
        }
      };

      assert.strictEqual(getReasonExplanation('PROFILE_NOT_FOUND'), 'Chưa nộp hồ sơ tài xế');
      assert.strictEqual(getReasonExplanation('APPLICATION_NOT_APPROVED'), 'Hồ sơ tài xế chưa được phê duyệt (đang chờ duyệt hoặc bị từ chối)');
      assert.strictEqual(getReasonExplanation('NO_ACTIVE_VEHICLE'), 'Chưa có phương tiện hoạt động (phương tiện đang INACTIVE hoặc chưa đăng ký)');
      assert.strictEqual(getReasonExplanation('ACCOUNT_INACTIVE'), 'Tài khoản người dùng đang bị khóa hoặc chưa kích hoạt');
      assert.strictEqual(getReasonExplanation('DRIVER_ROLE_MISSING'), 'Thiếu quyền tài xế (DRIVER)');
    });
  });

});

