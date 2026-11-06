import type { ApiError, AuthTokens, DecodedJwt } from '../types/index.ts';
import { apiLogger } from './apiLogger.ts';

const TOKEN_KEY = 'rhl_access_token';
const REFRESH_KEY = 'rhl_refresh_token';

export const tokenStorage = {
  getAccessToken: (): string | null => localStorage.getItem(TOKEN_KEY),
  setAccessToken: (token: string) => localStorage.setItem(TOKEN_KEY, token),
  removeAccessToken: () => localStorage.removeItem(TOKEN_KEY),

  getRefreshToken: (): string | null => localStorage.getItem(REFRESH_KEY),
  setRefreshToken: (token: string) => localStorage.setItem(REFRESH_KEY, token),
  removeRefreshToken: () => localStorage.removeItem(REFRESH_KEY),

  clear: () => {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(REFRESH_KEY);
  },
};

export function decodeJwt(token: string): DecodedJwt | null {
  try {
    const parts = token.split('.');
    if (parts.length !== 3) return null;
    const payload = atob(parts[1].replace(/-/g, '+').replace(/_/g, '/'));
    return JSON.parse(payload) as DecodedJwt;
  } catch {
    return null;
  }
}

function generateCorrelationId(): string {
  return 'rhl-' + Math.random().toString(36).substring(2, 9) + '-' + Date.now().toString(36);
}

export function sanitizeForLogging(data: unknown): unknown {
  if (!data || typeof data !== 'object') return data;
  if (Array.isArray(data)) {
    return data.map(sanitizeForLogging);
  }
  const sanitized: Record<string, unknown> = {};
  for (const [key, val] of Object.entries(data as Record<string, unknown>)) {
    const lowerKey = key.toLowerCase();
    if (
      lowerKey.includes('password') ||
      lowerKey === 'token' ||
      lowerKey.endsWith('token') ||
      lowerKey.includes('accesstoken') ||
      lowerKey.includes('refreshtoken') ||
      lowerKey.includes('secret')
    ) {
      sanitized[key] = '***[PROTECTED]***';
    } else if (typeof val === 'object' && val !== null) {
      sanitized[key] = sanitizeForLogging(val);
    } else {
      sanitized[key] = val;
    }
  }
  return sanitized;
}

interface RequestOptions extends RequestInit {
  skipAuth?: boolean;
}

let refreshPromise: Promise<string | null> | null = null;

async function executeRefreshToken(): Promise<string | null> {
  const refreshToken = tokenStorage.getRefreshToken();
  if (!refreshToken) {
    tokenStorage.clear();
    return null;
  }
  try {
    const refreshRes = await fetch('/api/v1/auth/refresh', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken }),
    });

    if (refreshRes.ok) {
      const refreshData = (await refreshRes.json()) as AuthTokens;
      tokenStorage.setAccessToken(refreshData.accessToken);
      tokenStorage.setRefreshToken(refreshData.refreshToken);
      return refreshData.accessToken;
    } else {
      tokenStorage.clear();
      return null;
    }
  } catch {
    tokenStorage.clear();
    return null;
  }
}

export function refreshAccessTokenSingleFlight(): Promise<string | null> {
  if (!refreshPromise) {
    refreshPromise = executeRefreshToken().finally(() => {
      refreshPromise = null;
    });
  }
  return refreshPromise;
}

export async function request<T>(endpoint: string, options: RequestOptions = {}): Promise<T> {
  const correlationId = generateCorrelationId();
  const startTime = performance.now();
  const method = options.method || 'GET';
  const url = endpoint.startsWith('/') ? endpoint : `/${endpoint}`;

  const headers = new Headers(options.headers || {});
  headers.set('X-Correlation-ID', correlationId);

  if (!headers.has('Content-Type') && options.body && typeof options.body === 'string') {
    headers.set('Content-Type', 'application/json');
  }

  if (!options.skipAuth) {
    const token = tokenStorage.getAccessToken();
    if (token) {
      headers.set('Authorization', `Bearer ${token}`);
    }
  }

  let requestBodyParsed: unknown = undefined;
  if (options.body && typeof options.body === 'string') {
    try {
      requestBodyParsed = JSON.parse(options.body);
    } catch {
      requestBodyParsed = options.body;
    }
  }

  try {
    let response = await fetch(url, {
      ...options,
      headers,
    });

    // Auto-refresh token on 401 Unauthorized if refreshToken is present (single-flight)
    if (response.status === 401 && !options.skipAuth && !url.includes('/auth/login') && !url.includes('/auth/refresh')) {
      const newAccessToken = await refreshAccessTokenSingleFlight();
      if (newAccessToken) {
        headers.set('Authorization', `Bearer ${newAccessToken}`);
        response = await fetch(url, {
          ...options,
          headers,
        });
      }
    }

    const durationMs = Math.round(performance.now() - startTime);

    let responseData: unknown = null;
    const contentType = response.headers.get('content-type');
    if (contentType && contentType.includes('application/json')) {
      responseData = await response.json();
    } else if (response.status === 204) {
      responseData = null;
    } else {
      const text = await response.text();
      responseData = text ? { raw: text } : null;
    }
    apiLogger.log({
      id: correlationId,
      timestamp: new Date().toLocaleTimeString(),
      method,
      url,
      status: response.status,
      durationMs,
      requestBody: sanitizeForLogging(requestBodyParsed),
      responseBody: sanitizeForLogging(responseData),
      correlationId,
      success: response.ok,
    });

    if (!response.ok) {
      const err = responseData as ApiError;
      throw {
        status: response.status,
        code: err?.code || 'UNKNOWN_ERROR',
        message: err?.message || `Request failed with status ${response.status}`,
        path: err?.path || url,
        timestamp: err?.timestamp,
        errors: err?.errors,
      } as ApiError;
    }

    return responseData as T;
  } catch (error: unknown) {
    const durationMs = Math.round(performance.now() - startTime);
    if ((error as ApiError).status) {
      throw error;
    }

    const netErr: ApiError = {
      status: 0,
      code: 'NETWORK_ERROR',
      message: (error as Error).message || 'Unable to connect to API Gateway at http://localhost:8080',
      path: url,
    };

    apiLogger.log({
      id: correlationId,
      timestamp: new Date().toLocaleTimeString(),
      method,
      url,
      status: 0,
      durationMs,
      requestBody: sanitizeForLogging(requestBodyParsed),
      responseBody: { error: netErr.message },
      correlationId,
      success: false,
    });

    throw netErr;
  }
}
