import React from 'react';
import type { RealtimeConnectionStatus } from '../../types';
import { Wifi, WifiOff, RefreshCw, AlertTriangle } from 'lucide-react';

interface ConnectionBadgeProps {
  status: RealtimeConnectionStatus;
  onReconnect?: () => void;
}

export const ConnectionBadge: React.FC<ConnectionBadgeProps> = ({ status, onReconnect }) => {
  let badgeColor = 'var(--text-muted)';
  let bgStyle = 'rgba(255, 255, 255, 0.05)';
  let label = 'Offline';
  let icon = <WifiOff size={13} />;

  if (status === 'CONNECTED') {
    badgeColor = 'var(--success)';
    bgStyle = 'rgba(16, 185, 129, 0.15)';
    label = 'Realtime Connected';
    icon = <Wifi size={13} color="var(--success)" />;
  } else if (status === 'CONNECTING' || status === 'RECONNECTING') {
    badgeColor = '#fbbf24';
    bgStyle = 'rgba(245, 158, 11, 0.15)';
    label = status === 'RECONNECTING' ? 'Reconnecting...' : 'Connecting...';
    icon = <RefreshCw size={13} className="animate-pulse" color="#fbbf24" />;
  } else if (status === 'AUTH_ERROR') {
    badgeColor = 'var(--danger)';
    bgStyle = 'rgba(239, 68, 68, 0.15)';
    label = 'Auth Error';
    icon = <AlertTriangle size={13} color="var(--danger)" />;
  }

  return (
    <div
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: '6px',
        padding: '3px 8px',
        borderRadius: 'var(--radius-full)',
        background: bgStyle,
        border: `1px solid ${badgeColor}`,
        color: badgeColor,
        fontSize: '11px',
        fontWeight: 600,
        fontFamily: 'var(--font-mono)',
      }}
    >
      {icon}
      <span>{label}</span>
      {(status === 'DISCONNECTED' || status === 'AUTH_ERROR') && onReconnect && (
        <button
          onClick={onReconnect}
          style={{
            background: 'none',
            border: 'none',
            cursor: 'pointer',
            padding: '0 2px',
            color: 'inherit',
            display: 'flex',
            alignItems: 'center',
          }}
          title="Retry Connection"
        >
          <RefreshCw size={11} />
        </button>
      )}
    </div>
  );
};
