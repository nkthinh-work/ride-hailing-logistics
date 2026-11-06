import { useEffect, useState, useCallback } from 'react';
import { useAuth } from '../context/AuthContext.tsx';
import { stompClient } from '../services/realtime/stompClient.ts';
import type { RealtimeConnectionStatus } from '../types/index.ts';

export function useRealtimeConnection() {
  const { isAuthenticated, accessToken, user, refreshSession, logout } = useAuth();
  const [status, setStatus] = useState<RealtimeConnectionStatus>(stompClient.getStatus());

  useEffect(() => {
    // Register status change listener
    const unsubscribe = stompClient.onStatusChange((newStatus) => {
      setStatus(newStatus);
    });

    // Configure token refresh on auth failure
    stompClient.setAuthErrorHandler(async () => {
      try {
        await refreshSession();
      } catch (err) {
        // If refresh fails, log out cleanly
        await logout();
        throw err;
      }
    });

    if (isAuthenticated && accessToken) {
      stompClient.connect(true);
    } else {
      stompClient.disconnect();
    }

    return () => {
      unsubscribe();
    };
  }, [isAuthenticated, accessToken, user?.id, refreshSession, logout]);

  const reconnect = useCallback(() => {
    stompClient.connect(true);
  }, []);

  const disconnect = useCallback(() => {
    stompClient.disconnect();
  }, []);

  return {
    status,
    isConnected: status === 'CONNECTED',
    isConnecting: status === 'CONNECTING' || status === 'RECONNECTING',
    isAuthError: status === 'AUTH_ERROR',
    reconnect,
    disconnect,
  };
}
