import { Client, type IMessage, type StompHeaders, type StompSubscription } from '@stomp/stompjs';
import type { EventEnvelope, RealtimeConnectionStatus } from '../../types/index.ts';
import { tokenStorage } from '../apiClient.ts';

export type MessageCallback<T> = (event: EventEnvelope<T>) => void;
export type StatusChangeListener = (status: RealtimeConnectionStatus) => void;

interface SubscriptionRegistryEntry {
  destination: string;
  callback: MessageCallback<unknown>;
  subscription?: StompSubscription;
}

class RealtimeStompClient {
  private client: Client | null = null;
  private status: RealtimeConnectionStatus = 'DISCONNECTED';
  private statusListeners: Set<StatusChangeListener> = new Set();
  private subscriptions: Map<string, SubscriptionRegistryEntry> = new Map();
  private processedEventIds: Set<string> = new Set();
  private maxDeduplicationCacheSize = 500;
  private reconnectAttempt = 0;
  private reconnectTimeoutId: ReturnType<typeof setTimeout> | null = null;
  private isExplicitDisconnect = false;
  private onAuthErrorCallback: (() => Promise<void>) | null = null;

  constructor() {
    this.status = 'DISCONNECTED';
  }

  public getStatus(): RealtimeConnectionStatus {
    return this.status;
  }

  public isConnected(): boolean {
    return this.status === 'CONNECTED';
  }

  public onStatusChange(listener: StatusChangeListener): () => void {
    this.statusListeners.add(listener);
    listener(this.status);
    return () => {
      this.statusListeners.delete(listener);
    };
  }

  public setAuthErrorHandler(callback: () => Promise<void>): void {
    this.onAuthErrorCallback = callback;
  }

  private setStatus(newStatus: RealtimeConnectionStatus): void {
    if (this.status !== newStatus) {
      this.status = newStatus;
      this.statusListeners.forEach((listener) => {
        try {
          listener(newStatus);
        } catch {
          // ignore listener errors
        }
      });
    }
  }

  /**
   * Resolves WebSocket URL local / remote.
   * Priority:
   * 1. import.meta.env.VITE_WS_URL (if provided)
   * 2. Browser origin: ws(s)://${window.location.host}/ws (proxied by Vite or direct Gateway)
   * 3. Fallback: ws://localhost:8080/ws
   */
  private getBrokerUrl(): string {
    const envUrl = (import.meta as unknown as { env?: Record<string, string> }).env?.VITE_WS_URL;
    if (envUrl) {
      return envUrl;
    }

    const globalObj = globalThis as unknown as {
      location?: { host?: string; protocol?: string };
    };
    if (globalObj.location && globalObj.location.host) {
      const protocol = globalObj.location.protocol === 'https:' ? 'wss:' : 'ws:';
      return `${protocol}//${globalObj.location.host}/ws`;
    }

    return 'ws://localhost:8080/ws';
  }

  /**
   * Connect to WebSocket / STOMP broker using Bearer token in native STOMP headers.
   */
  private maxReconnectAttempts = 5;

  public connect(force = false): void {
    if (!force && this.client && (this.status === 'CONNECTED' || this.status === 'CONNECTING')) {
      return;
    }

    const token = tokenStorage.getAccessToken();
    if (!token) {
      this.setStatus('DISCONNECTED');
      return;
    }

    this.isExplicitDisconnect = false;
    this.reconnectAttempt = 0;
    this.setStatus('CONNECTING');

    const brokerUrl = this.getBrokerUrl();

    const connectHeaders: StompHeaders = {
      Authorization: `Bearer ${token}`,
    };

    if (this.client) {
      try {
        this.client.deactivate();
      } catch {
        // ignore
      }
    }

    this.client = new Client({
      brokerURL: brokerUrl,
      connectHeaders,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      reconnectDelay: 0, // Managed manually with exponential backoff & jitter
      debug: () => {
        // Suppress debug output to protect against credential leaks
      },
      onConnect: () => {
        this.reconnectAttempt = 0;
        this.setStatus('CONNECTED');
        this.resubscribeAll();
      },
      onStompError: (frame) => {
        const errorMsg = frame.headers['message'] || '';
        if (
          errorMsg.toLowerCase().includes('badcredentials') ||
          errorMsg.toLowerCase().includes('expired') ||
          errorMsg.toLowerCase().includes('unauthorized') ||
          errorMsg.toLowerCase().includes('denied')
        ) {
          this.setStatus('AUTH_ERROR');
          this.handleAuthFailure();
          return;
        }
        this.scheduleReconnect();
      },
      onWebSocketClose: (event) => {
        if (this.isExplicitDisconnect) {
          this.setStatus('DISCONNECTED');
          return;
        }

        // 1008 = Policy Violation, 4401/4403 = Unauthorized
        if (event.code === 1008 || event.code === 4401 || event.code === 4403) {
          this.setStatus('AUTH_ERROR');
          this.handleAuthFailure();
          return;
        }

        this.scheduleReconnect();
      },
      onWebSocketError: () => {
        // onWebSocketClose handles reconnection cleanly without double-scheduling
      },
    });

    try {
      this.client.activate();
    } catch {
      this.scheduleReconnect();
    }
  }

  private handleAuthFailure(): void {
    if (this.onAuthErrorCallback) {
      this.onAuthErrorCallback()
        .then(() => {
          // Token refreshed successfully; attempt reconnection
          this.connect(true);
        })
        .catch(() => {
          this.disconnect();
          this.setStatus('AUTH_ERROR');
        });
    } else {
      this.disconnect();
      this.setStatus('AUTH_ERROR');
    }
  }

  private scheduleReconnect(): void {
    if (this.isExplicitDisconnect || this.status === 'AUTH_ERROR') {
      return;
    }

    if (this.reconnectAttempt >= this.maxReconnectAttempts) {
      // Bounded retry exhausted: truthfully indicate disconnected state
      this.setStatus('DISCONNECTED');
      return;
    }

    this.setStatus('RECONNECTING');

    if (this.reconnectTimeoutId) {
      clearTimeout(this.reconnectTimeoutId);
    }

    this.reconnectAttempt++;
    // Exponential backoff: 1s, 2s, 4s, 8s, capped at 15s + jitter
    const baseDelay = Math.min(1000 * Math.pow(2, this.reconnectAttempt - 1), 15000);
    const jitter = Math.floor(Math.random() * 500);
    const delay = baseDelay + jitter;

    this.reconnectTimeoutId = setTimeout(() => {
      // Connect without resetting attempt counter during auto-retry
      const token = tokenStorage.getAccessToken();
      if (!token) {
        this.setStatus('DISCONNECTED');
        return;
      }
      this.setStatus('CONNECTING');
      try {
        if (this.client) {
          this.client.activate();
        } else {
          this.connect();
        }
      } catch {
        this.scheduleReconnect();
      }
    }, delay);
  }

  /**
   * Subscribe to a destination. Safe across reconnects and React StrictMode remounts.
   * Returns an unsubscribe function.
   */
  public subscribe<T>(destination: string, callback: MessageCallback<T>): () => void {
    const key = destination;
    const entry: SubscriptionRegistryEntry = {
      destination,
      callback: callback as MessageCallback<unknown>,
    };

    this.subscriptions.set(key, entry);

    if (this.client && this.client.connected) {
      this.bindSubscription(entry);
    }

    return () => {
      this.unsubscribe(destination);
    };
  }

  public unsubscribe(destination: string): void {
    const entry = this.subscriptions.get(destination);
    if (entry) {
      if (entry.subscription) {
        try {
          entry.subscription.unsubscribe();
        } catch {
          // ignore
        }
      }
      this.subscriptions.delete(destination);
    }
  }

  private bindSubscription(entry: SubscriptionRegistryEntry): void {
    if (!this.client || !this.client.connected) {
      return;
    }

    try {
      entry.subscription = this.client.subscribe(entry.destination, (message: IMessage) => {
        this.handleIncomingMessage(message, entry.callback);
      });
    } catch {
      // Failed to subscribe, will be retried on reconnect
    }
  }

  private resubscribeAll(): void {
    this.subscriptions.forEach((entry) => {
      this.bindSubscription(entry);
    });
  }

  private handleIncomingMessage(message: IMessage, callback: MessageCallback<unknown>): void {
    try {
      const parsed = JSON.parse(message.body) as EventEnvelope<unknown>;
      if (!parsed || !parsed.eventId) {
        return;
      }

      // Deduplication check: discard duplicate eventIds
      if (this.processedEventIds.has(parsed.eventId)) {
        return;
      }

      this.processedEventIds.add(parsed.eventId);
      if (this.processedEventIds.size > this.maxDeduplicationCacheSize) {
        // Drop oldest entries
        const iterator = this.processedEventIds.values();
        const oldest = iterator.next().value;
        if (oldest) {
          this.processedEventIds.delete(oldest);
        }
      }

      callback(parsed);
    } catch {
      // Malformed JSON frame received; drop safely without crashing
    }
  }

  /**
   * Explicit disconnect (e.g., on logout or leaving realtime screens).
   */
  public disconnect(): void {
    this.isExplicitDisconnect = true;

    if (this.reconnectTimeoutId) {
      clearTimeout(this.reconnectTimeoutId);
      this.reconnectTimeoutId = null;
    }

    this.subscriptions.forEach((entry) => {
      if (entry.subscription) {
        try {
          entry.subscription.unsubscribe();
        } catch {
          // ignore
        }
      }
    });
    this.subscriptions.clear();
    this.processedEventIds.clear();

    if (this.client) {
      try {
        this.client.deactivate();
      } catch {
        // ignore
      }
      this.client = null;
    }

    this.setStatus('DISCONNECTED');
  }
}

// Global Singleton Instance
export const stompClient = new RealtimeStompClient();
