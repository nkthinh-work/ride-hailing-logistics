import React, { createContext, useContext, useEffect, useState, useCallback } from 'react';
import type { AuthTokens, DecodedJwt, PermissionCode, RoleCode, UserResponse } from '../types';
import { decodeJwt, tokenStorage } from '../services/apiClient';
import { authService } from '../services/authService';

import { stompClient } from '../services/realtime/stompClient';

export interface DemoAccount {
  role: RoleCode;
  email: string;
  password: string;
  title: string;
  description: string;
}

export const DEMO_ACCOUNTS: Record<RoleCode, DemoAccount> = import.meta.env.DEV ? {
  CUSTOMER: {
    role: 'CUSTOMER',
    email: 'customer.test@example.com',
    password: 'Password123!',
    title: 'Customer User',
    description: 'Booking trips, applying for driver onboarding',
  },
  DRIVER: {
    role: 'DRIVER',
    email: 'driver@gmail.com',
    password: 'Password123!',
    title: 'Registered Driver',
    description: 'Turn Online/Offline, update live GPS location',
  },
  REVIEWER: {
    role: 'REVIEWER',
    email: 'reviewer.demo@example.com',
    password: 'Review12345!',
    title: 'Compliance Reviewer',
    description: 'Review, approve & reject driver applications',
  },
  ADMINISTRATOR: {
    role: 'ADMINISTRATOR',
    email: 'admin.location@example.com',
    password: 'Password123!',
    title: 'System Administrator',
    description: 'Radar search nearby active drivers via Redis GEO',
  },
  SUPPORT_STAFF: {
    role: 'SUPPORT_STAFF',
    email: 'customer.test@example.com',
    password: 'Password123!',
    title: 'Support Staff',
    description: 'Customer and driver support operations',
  },
  FINANCE_STAFF: {
    role: 'FINANCE_STAFF',
    email: 'customer.test@example.com',
    password: 'Password123!',
    title: 'Finance Staff',
    description: 'Ledger and refund management',
  },
} : ({} as Record<RoleCode, DemoAccount>);

interface AuthContextType {
  user: UserResponse | null;
  accessToken: string | null;
  decodedJwt: DecodedJwt | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  hasRole: (role: RoleCode) => boolean;
  hasPermission: (permission: PermissionCode) => boolean;
  login: (email: string, password: string) => Promise<AuthTokens>;
  register: (email: string, password: string) => Promise<UserResponse>;
  logout: () => Promise<void>;
  refreshSession: () => Promise<void>;
  quickSwitchRole: (role: RoleCode) => Promise<void>;
  reloadUser: () => Promise<void>;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [user, setUser] = useState<UserResponse | null>(null);
  const [accessToken, setAccessToken] = useState<string | null>(tokenStorage.getAccessToken());
  const [decodedJwt, setDecodedJwt] = useState<DecodedJwt | null>(() => {
    const t = tokenStorage.getAccessToken();
    return t ? decodeJwt(t) : null;
  });
  const [isLoading, setIsLoading] = useState<boolean>(true);

  const reloadUser = useCallback(async () => {
    try {
      const u = await authService.getMe();
      setUser(u);
    } catch (err) {
      setUser(null);
      throw err;
    }
  }, []);

  const initAuth = useCallback(async () => {
    const token = tokenStorage.getAccessToken();
    if (token) {
      const decoded = decodeJwt(token);
      const isExpired = decoded?.exp ? decoded.exp * 1000 <= Date.now() : true;

      if (isExpired) {
        const refreshToken = tokenStorage.getRefreshToken();
        if (refreshToken) {
          try {
            const tokens = await authService.refresh(refreshToken);
            tokenStorage.setAccessToken(tokens.accessToken);
            tokenStorage.setRefreshToken(tokens.refreshToken);
            setAccessToken(tokens.accessToken);
            setDecodedJwt(decodeJwt(tokens.accessToken));
            await reloadUser();
          } catch {
            tokenStorage.clear();
            setAccessToken(null);
            setDecodedJwt(null);
            setUser(null);
          }
        } else {
          tokenStorage.clear();
          setAccessToken(null);
          setDecodedJwt(null);
          setUser(null);
        }
      } else {
        setAccessToken(token);
        setDecodedJwt(decoded);
        try {
          await reloadUser();
        } catch {
          tokenStorage.clear();
          setAccessToken(null);
          setDecodedJwt(null);
          setUser(null);
        }
      }
    } else {
      setAccessToken(null);
      setDecodedJwt(null);
      setUser(null);
    }
    setIsLoading(false);
  }, [reloadUser]);

  useEffect(() => {
    initAuth();
  }, [initAuth]);

  const login = async (email: string, password: string): Promise<AuthTokens> => {
    setIsLoading(true);
    try {
      const tokens = await authService.login(email, password);
      tokenStorage.setAccessToken(tokens.accessToken);
      tokenStorage.setRefreshToken(tokens.refreshToken);
      setAccessToken(tokens.accessToken);
      setDecodedJwt(decodeJwt(tokens.accessToken));
      await reloadUser();
      return tokens;
    } finally {
      setIsLoading(false);
    }
  };

  const register = async (email: string, password: string): Promise<UserResponse> => {
    return authService.register(email, password);
  };

  const logout = async (): Promise<void> => {
    const refreshToken = tokenStorage.getRefreshToken();
    if (refreshToken) {
      try {
        await authService.logout(refreshToken);
      } catch {
        // ignore logout network errors
      }
    }
    stompClient.disconnect();
    tokenStorage.clear();
    setAccessToken(null);
    setDecodedJwt(null);
    setUser(null);
  };

  const refreshSession = async (): Promise<void> => {
    const refreshToken = tokenStorage.getRefreshToken();
    if (!refreshToken) throw new Error('No refresh token available');
    const tokens = await authService.refresh(refreshToken);
    tokenStorage.setAccessToken(tokens.accessToken);
    tokenStorage.setRefreshToken(tokens.refreshToken);
    setAccessToken(tokens.accessToken);
    setDecodedJwt(decodeJwt(tokens.accessToken));
    await reloadUser();
  };

  const quickSwitchRole = async (role: RoleCode): Promise<void> => {
    if (!import.meta.env.DEV) {
      throw new Error('Role switching is disabled in production environment.');
    }
    const demo = DEMO_ACCOUNTS[role];
    if (!demo) throw new Error(`Unknown role: ${role}`);

    // Clean previous session state completely before loading the new account
    stompClient.disconnect();
    tokenStorage.clear();
    setAccessToken(null);
    setDecodedJwt(null);
    setUser(null);

    await login(demo.email, demo.password);
  };

  const isTokenExpired = Boolean(decodedJwt && decodedJwt.exp * 1000 <= Date.now());

  const hasRole = (role: RoleCode): boolean => {
    if (isTokenExpired) return false;
    if (!user && !decodedJwt) return false;
    return (
      (user?.roles?.includes(role) ?? false) ||
      (decodedJwt?.roles?.includes(role) ?? false)
    );
  };

  const hasPermission = (permission: PermissionCode): boolean => {
    if (isTokenExpired) return false;
    if (!user && !decodedJwt) return false;
    return (
      (user?.permissions?.includes(permission) ?? false) ||
      (decodedJwt?.permissions?.includes(permission) ?? false)
    );
  };

  return (
    <AuthContext.Provider
      value={{
        user,
        accessToken,
        decodedJwt,
        isAuthenticated: Boolean(accessToken && !isTokenExpired && user),
        isLoading,
        hasRole,
        hasPermission,
        login,
        register,
        logout,
        refreshSession,
        quickSwitchRole,
        reloadUser,
      }}
    >
      {children}
    </AuthContext.Provider>
  );
};

export const useAuth = (): AuthContextType => {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
};
