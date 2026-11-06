import type { AuthTokens, UserResponse } from '../types';

import { request } from './apiClient';

export const authService = {
  login: async (email: string, password: string): Promise<AuthTokens> => {
    return request<AuthTokens>('/api/v1/auth/login', {
      method: 'POST',
      body: JSON.stringify({ email, password }),
      skipAuth: true,
    });
  },

  register: async (email: string, password: string): Promise<UserResponse> => {
    return request<UserResponse>('/api/v1/auth/register', {
      method: 'POST',
      body: JSON.stringify({ email, password }),
      skipAuth: true,
    });
  },

  getMe: async (): Promise<UserResponse> => {
    const res = await request<UserResponse & { userId?: string }>('/api/v1/auth/me');
    const resolvedId = res.id || res.userId || '';
    return {
      ...res,
      id: resolvedId,
      userId: res.userId || resolvedId,
      email: res.email || '',
      roles: res.roles || [],
      permissions: res.permissions || [],
      status: res.status || 'ACTIVE',
    };
  },

  refresh: async (refreshToken: string): Promise<AuthTokens> => {
    return request<AuthTokens>('/api/v1/auth/refresh', {
      method: 'POST',
      body: JSON.stringify({ refreshToken }),
      skipAuth: true,
    });
  },

  logout: async (refreshToken: string): Promise<void> => {
    return request<void>('/api/v1/auth/logout', {
      method: 'POST',
      body: JSON.stringify({ refreshToken }),
    });
  },
};
