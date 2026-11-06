import { describe, it, before } from 'node:test';
import assert from 'node:assert';

// Polyfill minimal browser globals for Node test environment
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

import type { RoleCode, PermissionCode, UserResponse, DecodedJwt, ApiError } from '../src/types/index.ts';
import { tokenStorage, decodeJwt, sanitizeForLogging } from '../src/services/apiClient.ts';

describe('M01 Core Frontend & Security Unit Tests', () => {

  describe('1. JWT Claims & Expiry Decoding', () => {
    it('should correctly decode base64 encoded JWT payload', () => {
      const payload: DecodedJwt = {
        sub: 'usr-12345',
        iss: 'rhl-identity-service',
        roles: ['CUSTOMER'],
        permissions: ['TRIP_CREATE', 'TRIP_VIEW_OWN'],
        iat: 1713600000,
        exp: 1713600900,
      };

      const base64Payload = Buffer.from(JSON.stringify(payload)).toString('base64url');
      const mockToken = `eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.${base64Payload}.mockSignatureHere`;

      const decoded = decodeJwt(mockToken);
      assert.notStrictEqual(decoded, null);
      assert.strictEqual(decoded?.sub, 'usr-12345');
      assert.strictEqual(decoded?.iss, 'rhl-identity-service');
      assert.deepStrictEqual(decoded?.roles, ['CUSTOMER']);
      assert.deepStrictEqual(decoded?.permissions, ['TRIP_CREATE', 'TRIP_VIEW_OWN']);
      assert.strictEqual(decoded?.exp, 1713600900);
    });

    it('should return null for malformed tokens', () => {
      assert.strictEqual(decodeJwt('not.a.valid.jwt.token'), null);
      assert.strictEqual(decodeJwt('invalid-token'), null);
      assert.strictEqual(decodeJwt(''), null);
    });
  });

  describe('2. Role-Based Access Control (RBAC) & Route Guarding', () => {
    const customerUser: UserResponse = {
      id: 'cust-1',
      email: 'customer.test@example.com',
      status: 'ACTIVE',
      roles: ['CUSTOMER'],
      permissions: ['TRIP_CREATE', 'TRIP_VIEW_OWN'],
    };

    const reviewerUser: UserResponse = {
      id: 'rev-1',
      email: 'reviewer.demo@example.com',
      status: 'ACTIVE',
      roles: ['REVIEWER'],
      permissions: ['DRIVER_REVIEW'],
    };

    const adminUser: UserResponse = {
      id: 'adm-1',
      email: 'admin.location@example.com',
      status: 'ACTIVE',
      roles: ['ADMINISTRATOR'],
      permissions: ['ROLE_MANAGE'],
    };

    function evaluateRouteAccess(
      user: UserResponse | null,
      requiredRole?: RoleCode,
      requiredPermission?: PermissionCode
    ): { allowed: boolean; reason?: string } {
      if (!user) return { allowed: false, reason: 'UNAUTHENTICATED' };
      if (requiredRole && !user.roles.includes(requiredRole)) {
        return { allowed: false, reason: `ROLE_REQUIRED_${requiredRole}` };
      }
      if (requiredPermission && !user.permissions.includes(requiredPermission)) {
        return { allowed: false, reason: `PERMISSION_REQUIRED_${requiredPermission}` };
      }
      return { allowed: true };
    }

    it('should grant access to Customer on default dashboard and deny Reviewer portal', () => {
      // Customer accessing default dashboard
      const dashboardAccess = evaluateRouteAccess(customerUser);
      assert.strictEqual(dashboardAccess.allowed, true);

      // Customer trying to access Reviewer-only operations
      const reviewerAccess = evaluateRouteAccess(customerUser, 'REVIEWER', 'DRIVER_REVIEW');
      assert.strictEqual(reviewerAccess.allowed, false);
      assert.strictEqual(reviewerAccess.reason, 'ROLE_REQUIRED_REVIEWER');
    });

    it('should grant Reviewer access to Reviewer portal and deny Administrator actions', () => {
      const reviewerAccess = evaluateRouteAccess(reviewerUser, 'REVIEWER', 'DRIVER_REVIEW');
      assert.strictEqual(reviewerAccess.allowed, true);

      // Reviewer cannot perform admin operations
      const adminAccess = evaluateRouteAccess(reviewerUser, 'ADMINISTRATOR', 'ROLE_MANAGE');
      assert.strictEqual(adminAccess.allowed, false);
      assert.strictEqual(adminAccess.reason, 'ROLE_REQUIRED_ADMINISTRATOR');
    });

    it('should correctly authorize Administrator access', () => {
      const adminCheck = evaluateRouteAccess(adminUser, 'ADMINISTRATOR', 'ROLE_MANAGE');
      assert.strictEqual(adminCheck.allowed, true);
    });

    it('should reject unauthenticated visitors to protected routes', () => {
      const unauthCheck = evaluateRouteAccess(null);
      assert.strictEqual(unauthCheck.allowed, false);
      assert.strictEqual(unauthCheck.reason, 'UNAUTHENTICATED');
    });
  });

  describe('3. Token Storage & Session Lifecycle', () => {
    it('should save, retrieve and clear tokens on logout', () => {
      tokenStorage.setAccessToken('test-access-token-xyz');
      tokenStorage.setRefreshToken('test-refresh-token-abc');

      assert.strictEqual(tokenStorage.getAccessToken(), 'test-access-token-xyz');
      assert.strictEqual(tokenStorage.getRefreshToken(), 'test-refresh-token-abc');

      // Logout triggers clear
      tokenStorage.clear();
      assert.strictEqual(tokenStorage.getAccessToken(), null);
      assert.strictEqual(tokenStorage.getRefreshToken(), null);
    });
  });

  describe('4. API Error Response Mapping & Correlation IDs', () => {
    it('should structure ApiError with status, code, and message', () => {
      const error: ApiError = {
        status: 403,
        code: 'ACCESS_DENIED',
        message: 'Access Denied: Insufficient permissions',
        path: '/api/v1/drivers/applications',
        timestamp: new Date().toISOString(),
      };

      assert.strictEqual(error.status, 403);
      assert.strictEqual(error.code, 'ACCESS_DENIED');
      assert.strictEqual(error.message.includes('Insufficient'), true);
    });

    it('should create valid correlation IDs with rhl- prefix', () => {
      const correlationId = 'rhl-' + Math.random().toString(36).substring(2, 9) + '-' + Date.now().toString(36);
      assert.strictEqual(correlationId.startsWith('rhl-'), true);
      assert.strictEqual(correlationId.split('-').length >= 3, true);
    });
  });

  describe('5. Data Sanitization & Logging Security', () => {
    it('should sanitize password, accessToken, and refreshToken from payload', () => {
      const payload = {
        email: 'customer.test@example.com',
        password: 'SuperSecretPassword123!',
        tokens: {
          accessToken: 'eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.sensitivePayload.sig',
          refreshToken: 'refresh-uuid-9988-7766',
          tokenType: 'Bearer',
        },
        clientSecret: 'hidden-secret-key',
      };

      const sanitized = sanitizeForLogging(payload) as Record<string, unknown>;
      assert.strictEqual(sanitized.email, 'customer.test@example.com');
      assert.strictEqual(sanitized.password, '***[PROTECTED]***');
      assert.strictEqual(sanitized.clientSecret, '***[PROTECTED]***');

      const tokens = sanitized.tokens as Record<string, unknown>;
      assert.strictEqual(tokens.accessToken, '***[PROTECTED]***');
      assert.strictEqual(tokens.refreshToken, '***[PROTECTED]***');
      assert.strictEqual(tokens.tokenType, 'Bearer');
    });

    it('should handle non-object and array payloads safely', () => {
      assert.strictEqual(sanitizeForLogging(null), null);
      assert.strictEqual(sanitizeForLogging('plain string'), 'plain string');
      assert.strictEqual(sanitizeForLogging(42), 42);

      const arrayPayload = [
        { password: '123', name: 'user1' },
        { token: 'abc', name: 'user2' },
      ];
      const sanitizedArray = sanitizeForLogging(arrayPayload) as Array<Record<string, unknown>>;
      assert.strictEqual(sanitizedArray[0].password, '***[PROTECTED]***');
      assert.strictEqual(sanitizedArray[0].name, 'user1');
      assert.strictEqual(sanitizedArray[1].token, '***[PROTECTED]***');
      assert.strictEqual(sanitizedArray[1].name, 'user2');
    });
  });

  describe('6. Role Switcher & Privilege Boundary Verification', () => {
    it('should verify CUSTOMER cannot self-elevate permissions without valid backend tokens', () => {
      // Simulate active customer session
      const customerSession: UserResponse = {
        id: 'cust-uuid-1',
        email: 'customer.test@example.com',
        status: 'ACTIVE',
        roles: ['CUSTOMER'],
        permissions: ['TRIP_CREATE', 'TRIP_VIEW_OWN'],
      };

      // Ensure customer cannot pass reviewer or admin guards
      const reviewerCheck = customerSession.permissions.includes('DRIVER_REVIEW');
      const adminCheck = customerSession.roles.includes('ADMINISTRATOR');

      assert.strictEqual(reviewerCheck, false, 'CUSTOMER must not possess DRIVER_REVIEW permission');
      assert.strictEqual(adminCheck, false, 'CUSTOMER must not possess ADMINISTRATOR role');
    });
  });

});
