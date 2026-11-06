import React, { useState, useEffect } from 'react';
import { useAuth, DEMO_ACCOUNTS } from '../context/AuthContext';
import { apiLogger } from '../services/apiLogger';
import type { ApiLogEntry, RoleCode } from '../types';
import {
  Terminal,
  X,
  Key,
  Users,
  Activity,
  Server,
  RefreshCw,
  Copy,
  Check,
  Trash2,
  ChevronRight,
  Clock,
} from 'lucide-react';

interface DevConsoleProps {
  isOpen: boolean;
  onClose: () => void;
}

export const DevConsole: React.FC<DevConsoleProps> = ({ isOpen, onClose }) => {
  const { user, accessToken, decodedJwt, quickSwitchRole, refreshSession, isLoading } = useAuth();
  const [activeTab, setActiveTab] = useState<'switcher' | 'jwt' | 'traffic' | 'topology'>('switcher');
  const [logs, setLogs] = useState<ApiLogEntry[]>(() => apiLogger.getLogs());
  const [selectedLog, setSelectedLog] = useState<ApiLogEntry | null>(null);
  const [copied, setCopied] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [actionMsg, setActionMsg] = useState<{ text: string; error?: boolean } | null>(null);

  // Expiry countdown timer calculation
  const [secondsLeft, setSecondsLeft] = useState<number | null>(null);

  useEffect(() => {
    const unsub = apiLogger.subscribe(() => {
      setLogs(apiLogger.getLogs());
    });
    return unsub;
  }, []);

  useEffect(() => {
    if (!decodedJwt?.exp) {
      setSecondsLeft(null);
      return;
    }
    const updateCountdown = () => {
      const now = Math.floor(Date.now() / 1000);
      const remaining = decodedJwt.exp - now;
      setSecondsLeft(remaining > 0 ? remaining : 0);
    };
    updateCountdown();
    const interval = setInterval(updateCountdown, 1000);
    return () => clearInterval(interval);
  }, [decodedJwt]);

  if (!isOpen) return null;

  const handleCopy = (text: string) => {
    navigator.clipboard.writeText(text);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  const handleRoleSwitch = async (role: RoleCode) => {
    try {
      setActionMsg(null);
      await quickSwitchRole(role);
      setActionMsg({ text: `Switched session to ${role} successfully!` });
    } catch (err: unknown) {
      setActionMsg({ text: (err as Error).message || 'Failed to switch role', error: true });
    }
  };

  const handleForceRefresh = async () => {
    try {
      setRefreshing(true);
      setActionMsg(null);
      await refreshSession();
      setActionMsg({ text: 'Access token refreshed successfully via /api/v1/auth/refresh!' });
    } catch (err: unknown) {
      setActionMsg({ text: (err as Error).message || 'Token refresh failed', error: true });
    } finally {
      setRefreshing(false);
    }
  };

  const getMethodBadgeClass = (m: string) => {
    switch (m.toUpperCase()) {
      case 'GET': return 'var(--primary)';
      case 'POST': return 'var(--success)';
      case 'PUT': return 'var(--warning)';
      case 'PATCH': return 'var(--reviewer)';
      case 'DELETE': return 'var(--danger)';
      default: return 'var(--text-muted)';
    }
  };

  const getStatusColor = (status: number) => {
    if (status >= 200 && status < 300) return 'var(--success)';
    if (status === 401 || status === 403) return 'var(--warning)';
    if (status >= 400) return 'var(--danger)';
    return 'var(--text-muted)';
  };

  return (
    <div
      style={{
        position: 'fixed',
        bottom: '0',
        right: '0',
        width: '100%',
        maxWidth: '780px',
        height: '560px',
        background: '#0d121f',
        borderTop: '2px solid var(--primary)',
        borderLeft: '1px solid var(--border-subtle)',
        boxShadow: '0 -10px 40px rgba(0, 0, 0, 0.8)',
        zIndex: 100,
        display: 'flex',
        flexDirection: 'column',
        fontFamily: 'var(--font-sans)',
        animation: 'fadeIn 0.2s ease',
      }}
    >
      {/* Console Top Bar */}
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          padding: '10px 16px',
          background: 'rgba(24, 32, 50, 0.95)',
          borderBottom: '1px solid var(--border-subtle)',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
          <Terminal size={16} color="var(--primary)" />
          <span style={{ fontWeight: 700, fontSize: '13px', letterSpacing: '0.5px' }}>
            DEVELOPER CONSOLE & ARCHITECTURE EXPLORER
          </span>
          <span
            style={{
              fontSize: '11px',
              fontFamily: 'var(--font-mono)',
              background: 'rgba(59, 130, 246, 0.15)',
              color: 'var(--primary)',
              padding: '1px 6px',
              borderRadius: '4px',
            }}
          >
            M01 SHELL
          </span>
        </div>

        <button
          onClick={onClose}
          style={{
            background: 'transparent',
            border: 'none',
            color: 'var(--text-secondary)',
            cursor: 'pointer',
            padding: '4px',
            borderRadius: '4px',
          }}
        >
          <X size={18} />
        </button>
      </div>

      {/* Tabs Bar */}
      <div
        style={{
          display: 'flex',
          background: 'rgba(15, 20, 32, 0.9)',
          borderBottom: '1px solid var(--border-subtle)',
          padding: '0 8px',
        }}
      >
        <button
          onClick={() => setActiveTab('switcher')}
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: '6px',
            padding: '10px 16px',
            fontSize: '13px',
            fontWeight: 600,
            background: 'transparent',
            border: 'none',
            borderBottom: activeTab === 'switcher' ? '2px solid var(--primary)' : '2px solid transparent',
            color: activeTab === 'switcher' ? 'var(--text-primary)' : 'var(--text-muted)',
            cursor: 'pointer',
          }}
        >
          <Users size={14} /> 1-Click Role Switcher (PREVIEW ONLY)
        </button>

        <button
          onClick={() => setActiveTab('jwt')}
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: '6px',
            padding: '10px 16px',
            fontSize: '13px',
            fontWeight: 600,
            background: 'transparent',
            border: 'none',
            borderBottom: activeTab === 'jwt' ? '2px solid var(--primary)' : '2px solid transparent',
            color: activeTab === 'jwt' ? 'var(--text-primary)' : 'var(--text-muted)',
            cursor: 'pointer',
          }}
        >
          <Key size={14} /> Token Inspector
          {secondsLeft !== null && (
            <span
              style={{
                fontSize: '10px',
                fontFamily: 'var(--font-mono)',
                color: secondsLeft < 60 ? 'var(--danger)' : 'var(--success)',
                background: 'rgba(0,0,0,0.3)',
                padding: '1px 5px',
                borderRadius: '4px',
              }}
            >
              {secondsLeft}s
            </span>
          )}
        </button>

        <button
          onClick={() => setActiveTab('traffic')}
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: '6px',
            padding: '10px 16px',
            fontSize: '13px',
            fontWeight: 600,
            background: 'transparent',
            border: 'none',
            borderBottom: activeTab === 'traffic' ? '2px solid var(--primary)' : '2px solid transparent',
            color: activeTab === 'traffic' ? 'var(--text-primary)' : 'var(--text-muted)',
            cursor: 'pointer',
          }}
        >
          <Activity size={14} /> Live Traffic ({logs.length})
        </button>

        <button
          onClick={() => setActiveTab('topology')}
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: '6px',
            padding: '10px 16px',
            fontSize: '13px',
            fontWeight: 600,
            background: 'transparent',
            border: 'none',
            borderBottom: activeTab === 'topology' ? '2px solid var(--primary)' : '2px solid transparent',
            color: activeTab === 'topology' ? 'var(--text-primary)' : 'var(--text-muted)',
            cursor: 'pointer',
          }}
        >
          <Server size={14} /> Architecture & Health
        </button>
      </div>

      {/* Action Notification Banner */}
      {actionMsg && (
        <div
          style={{
            padding: '8px 16px',
            fontSize: '12px',
            fontWeight: 500,
            background: actionMsg.error ? 'rgba(239, 68, 68, 0.15)' : 'rgba(16, 185, 129, 0.15)',
            color: actionMsg.error ? '#f87171' : '#34d399',
            borderBottom: '1px solid var(--border-subtle)',
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
          }}
        >
          <span>{actionMsg.text}</span>
          <button
            onClick={() => setActionMsg(null)}
            style={{ background: 'transparent', border: 'none', color: 'inherit', cursor: 'pointer' }}
          >
            ×
          </button>
        </div>
      )}

      {/* Tab Content Body */}
      <div style={{ flex: 1, overflowY: 'auto', padding: '16px' }}>
        {/* TAB 1: ROLE SWITCHER */}
        {activeTab === 'switcher' && (
          <div>
            <div style={{ marginBottom: '14px' }}>
              <h4 style={{ fontSize: '14px', fontWeight: 700, marginBottom: '4px' }}>
                Instant Persona Switching (PREVIEW ONLY — Seeded Test Accounts)
              </h4>
              <p style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
                Click any role below to authenticate directly against backend PostgreSQL seed users through Gateway :8080.
              </p>
            </div>

            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '12px' }}>
              {(['CUSTOMER', 'DRIVER', 'REVIEWER', 'ADMINISTRATOR'] as RoleCode[]).map((r) => {
                const demo = DEMO_ACCOUNTS[r];
                const isCurrent = user?.roles?.includes(r);
                return (
                  <div
                    key={r}
                    className="glass-panel"
                    style={{
                      padding: '14px',
                      borderColor: isCurrent ? 'var(--primary)' : 'var(--border-subtle)',
                      background: isCurrent ? 'rgba(59, 130, 246, 0.1)' : 'var(--bg-surface)',
                      display: 'flex',
                      flexDirection: 'column',
                      justifyContent: 'space-between',
                      gap: '10px',
                    }}
                  >
                    <div>
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                        <span style={{ fontWeight: 700, fontSize: '13px' }}>{demo.title}</span>
                        {isCurrent ? (
                          <span className="badge badge-approved" style={{ fontSize: '9px' }}>
                            ACTIVE
                          </span>
                        ) : (
                          <span
                            className={`badge ${
                              r === 'DRIVER' ? 'badge-driver' : r === 'REVIEWER' ? 'badge-reviewer' : r === 'ADMINISTRATOR' ? 'badge-admin' : 'badge-customer'
                            }`}
                            style={{ fontSize: '9px' }}
                          >
                            {r}
                          </span>
                        )}
                      </div>
                      <p style={{ fontSize: '12px', color: 'var(--text-secondary)', marginTop: '4px' }}>
                        {demo.description}
                      </p>
                      <div
                        style={{
                          marginTop: '8px',
                          fontSize: '11px',
                          fontFamily: 'var(--font-mono)',
                          color: 'var(--text-muted)',
                        }}
                      >
                        {demo.email}
                      </div>
                    </div>

                    <button
                      onClick={() => handleRoleSwitch(r)}
                      disabled={isLoading || isCurrent}
                      className={`btn btn-sm ${isCurrent ? 'btn-secondary' : 'btn-primary'}`}
                      style={{ width: '100%' }}
                    >
                      {isCurrent ? 'Current Session' : `Switch to ${r}`}
                    </button>
                  </div>
                );
              })}
            </div>
          </div>
        )}

        {/* TAB 2: JWT INSPECTOR */}
        {activeTab === 'jwt' && (
          <div>
            <div
              style={{
                display: 'flex',
                justifyContent: 'space-between',
                alignItems: 'center',
                marginBottom: '14px',
              }}
            >
              <div>
                <h4 style={{ fontSize: '14px', fontWeight: 700 }}>Decoded RS256 JWT Payload</h4>
                <p style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
                  Asymmetric RSA Token signed by Identity Service private.pem, verified by Gateway with public.pem.
                </p>
              </div>

              <button
                onClick={handleForceRefresh}
                disabled={refreshing || !accessToken}
                className="btn btn-secondary btn-sm"
              >
                <RefreshCw size={13} className={refreshing ? 'animate-pulse' : ''} />
                Force Refresh
              </button>
            </div>

            {decodedJwt ? (
              <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
                {/* Expiry Bar */}
                <div
                  style={{
                    background: 'var(--bg-surface-elevated)',
                    padding: '10px 14px',
                    borderRadius: 'var(--radius-md)',
                    border: '1px solid var(--border-subtle)',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                  }}
                >
                  <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                    <Clock size={16} color="var(--primary)" />
                    <span style={{ fontSize: '13px', fontWeight: 600 }}>Token Lifespan Remaining:</span>
                  </div>
                  <span
                    style={{
                      fontFamily: 'var(--font-mono)',
                      fontSize: '14px',
                      fontWeight: 700,
                      color: secondsLeft && secondsLeft < 60 ? 'var(--danger)' : 'var(--success)',
                    }}
                  >
                    {secondsLeft !== null ? `${secondsLeft} seconds (of 600s TTL)` : 'Expired'}
                  </span>
                </div>

                {/* Claims View */}
                <div
                  style={{
                    display: 'grid',
                    gridTemplateColumns: '1fr 1fr',
                    gap: '10px',
                  }}
                >
                  <div
                    style={{
                      background: 'var(--bg-surface-elevated)',
                      padding: '12px',
                      borderRadius: 'var(--radius-md)',
                      border: '1px solid var(--border-subtle)',
                    }}
                  >
                    <span style={{ fontSize: '11px', color: 'var(--text-muted)', textTransform: 'uppercase' }}>
                      Subject (User ID)
                    </span>
                    <p style={{ fontFamily: 'var(--font-mono)', fontSize: '12px', wordBreak: 'break-all', marginTop: '4px' }}>
                      {decodedJwt.sub}
                    </p>
                  </div>

                  <div
                    style={{
                      background: 'var(--bg-surface-elevated)',
                      padding: '12px',
                      borderRadius: 'var(--radius-md)',
                      border: '1px solid var(--border-subtle)',
                    }}
                  >
                    <span style={{ fontSize: '11px', color: 'var(--text-muted)', textTransform: 'uppercase' }}>
                      Issuer (iss)
                    </span>
                    <p style={{ fontFamily: 'var(--font-mono)', fontSize: '12px', marginTop: '4px' }}>
                      {decodedJwt.iss}
                    </p>
                  </div>
                </div>

                {/* Roles & Permissions */}
                <div
                  style={{
                    background: 'var(--bg-surface-elevated)',
                    padding: '12px',
                    borderRadius: 'var(--radius-md)',
                    border: '1px solid var(--border-subtle)',
                  }}
                >
                  <span style={{ fontSize: '11px', color: 'var(--text-muted)', textTransform: 'uppercase' }}>
                    Roles Claim (roles)
                  </span>
                  <div style={{ display: 'flex', gap: '6px', flexWrap: 'wrap', marginTop: '6px' }}>
                    {decodedJwt.roles?.map((r) => (
                      <span key={r} className="badge badge-customer" style={{ fontSize: '10px' }}>
                        {r}
                      </span>
                    ))}
                  </div>

                  <span
                    style={{
                      display: 'block',
                      fontSize: '11px',
                      color: 'var(--text-muted)',
                      textTransform: 'uppercase',
                      marginTop: '12px',
                    }}
                  >
                    Permissions Claim (permissions)
                  </span>
                  <div style={{ display: 'flex', gap: '6px', flexWrap: 'wrap', marginTop: '6px' }}>
                    {decodedJwt.permissions?.map((p) => (
                      <span
                        key={p}
                        style={{
                          background: 'rgba(139, 92, 246, 0.15)',
                          color: '#c4b5fd',
                          border: '1px solid rgba(139, 92, 246, 0.3)',
                          padding: '2px 8px',
                          borderRadius: '4px',
                          fontSize: '11px',
                          fontFamily: 'var(--font-mono)',
                        }}
                      >
                        {p}
                      </span>
                    ))}
                  </div>
                </div>

                {/* Raw Token Copy */}
                <div>
                  <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '6px' }}>
                    <span style={{ fontSize: '11px', color: 'var(--text-muted)' }}>RAW BEARER TOKEN:</span>
                    <button
                      onClick={() => accessToken && handleCopy(accessToken)}
                      style={{
                        background: 'transparent',
                        border: 'none',
                        color: 'var(--primary)',
                        fontSize: '11px',
                        cursor: 'pointer',
                        display: 'flex',
                        alignItems: 'center',
                        gap: '4px',
                      }}
                    >
                      {copied ? <Check size={12} /> : <Copy size={12} />}
                      {copied ? 'Copied' : 'Copy Token'}
                    </button>
                  </div>
                  <textarea
                    readOnly
                    value={accessToken || ''}
                    rows={2}
                    className="input-field font-mono"
                    style={{ fontSize: '11px', resize: 'none' }}
                  />
                </div>
              </div>
            ) : (
              <div style={{ textAlign: 'center', padding: '40px', color: 'var(--text-muted)' }}>
                No active session token. Please sign in or switch a role.
              </div>
            )}
          </div>
        )}

        {/* TAB 3: LIVE API TRAFFIC */}
        {activeTab === 'traffic' && (
          <div>
            <div
              style={{
                display: 'flex',
                justifyContent: 'space-between',
                alignItems: 'center',
                marginBottom: '10px',
              }}
            >
              <div>
                <h4 style={{ fontSize: '14px', fontWeight: 700 }}>Gateway Intercepted Traffic</h4>
                <p style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
                  All client requests dispatched via API Gateway (:8080) with correlation IDs.
                </p>
              </div>
              <button
                onClick={() => apiLogger.clearLogs()}
                className="btn btn-secondary btn-sm"
                title="Clear Traffic Logs"
              >
                <Trash2 size={13} /> Clear
              </button>
            </div>

            {selectedLog ? (
              <div
                style={{
                  background: 'var(--bg-surface-elevated)',
                  borderRadius: 'var(--radius-md)',
                  padding: '14px',
                  border: '1px solid var(--border-subtle)',
                }}
              >
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '12px' }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                    <span
                      style={{
                        color: getMethodBadgeClass(selectedLog.method),
                        fontWeight: 700,
                        fontFamily: 'var(--font-mono)',
                        fontSize: '12px',
                      }}
                    >
                      {selectedLog.method}
                    </span>
                    <span style={{ fontFamily: 'var(--font-mono)', fontSize: '13px' }}>{selectedLog.url}</span>
                    <span
                      style={{
                        color: getStatusColor(selectedLog.status),
                        fontWeight: 700,
                        fontSize: '12px',
                        fontFamily: 'var(--font-mono)',
                      }}
                    >
                      [{selectedLog.status}]
                    </span>
                  </div>
                  <button
                    onClick={() => setSelectedLog(null)}
                    className="btn btn-secondary btn-sm"
                  >
                    Back to List
                  </button>
                </div>

                <div style={{ fontSize: '11px', color: 'var(--text-muted)', marginBottom: '10px' }}>
                  Correlation ID: <code>{selectedLog.correlationId}</code> • Latency: {selectedLog.durationMs}ms • Time: {selectedLog.timestamp}
                </div>

                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '10px' }}>
                  <div>
                    <span style={{ fontSize: '11px', color: 'var(--text-muted)' }}>REQUEST BODY:</span>
                    <pre
                      style={{
                        background: '#070a12',
                        padding: '10px',
                        borderRadius: '6px',
                        fontSize: '11px',
                        maxHeight: '180px',
                        overflow: 'auto',
                        marginTop: '4px',
                      }}
                    >
                      {selectedLog.requestBody ? JSON.stringify(selectedLog.requestBody, null, 2) : '(none)'}
                    </pre>
                  </div>

                  <div>
                    <span style={{ fontSize: '11px', color: 'var(--text-muted)' }}>RESPONSE BODY:</span>
                    <pre
                      style={{
                        background: '#070a12',
                        padding: '10px',
                        borderRadius: '6px',
                        fontSize: '11px',
                        maxHeight: '180px',
                        overflow: 'auto',
                        marginTop: '4px',
                      }}
                    >
                      {selectedLog.responseBody ? JSON.stringify(selectedLog.responseBody, null, 2) : '(empty)'}
                    </pre>
                  </div>
                </div>
              </div>
            ) : (
              <div
                style={{
                  border: '1px solid var(--border-subtle)',
                  borderRadius: 'var(--radius-md)',
                  overflow: 'hidden',
                }}
              >
                {logs.length === 0 ? (
                  <div style={{ padding: '30px', textAlign: 'center', color: 'var(--text-muted)', fontSize: '13px' }}>
                    No network activity recorded yet.
                  </div>
                ) : (
                  <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '12px' }}>
                    <thead>
                      <tr style={{ background: 'var(--bg-surface-elevated)', textAlign: 'left', color: 'var(--text-muted)' }}>
                        <th style={{ padding: '8px 12px' }}>Time</th>
                        <th style={{ padding: '8px 12px' }}>Method</th>
                        <th style={{ padding: '8px 12px' }}>Endpoint</th>
                        <th style={{ padding: '8px 12px' }}>Status</th>
                        <th style={{ padding: '8px 12px' }}>Latency</th>
                        <th style={{ padding: '8px 12px' }}></th>
                      </tr>
                    </thead>
                    <tbody>
                      {logs.map((l) => (
                        <tr
                          key={l.id + l.timestamp}
                          onClick={() => setSelectedLog(l)}
                          style={{
                            borderTop: '1px solid var(--border-subtle)',
                            cursor: 'pointer',
                            transition: 'background 0.15s',
                          }}
                          onMouseOver={(e) => (e.currentTarget.style.background = 'var(--bg-surface-hover)')}
                          onMouseOut={(e) => (e.currentTarget.style.background = 'transparent')}
                        >
                          <td style={{ padding: '8px 12px', fontFamily: 'var(--font-mono)', color: 'var(--text-muted)' }}>
                            {l.timestamp}
                          </td>
                          <td style={{ padding: '8px 12px', fontWeight: 700, color: getMethodBadgeClass(l.method) }}>
                            {l.method}
                          </td>
                          <td style={{ padding: '8px 12px', fontFamily: 'var(--font-mono)' }}>{l.url}</td>
                          <td style={{ padding: '8px 12px', fontWeight: 700, color: getStatusColor(l.status) }}>
                            {l.status}
                          </td>
                          <td style={{ padding: '8px 12px', color: 'var(--text-muted)' }}>{l.durationMs}ms</td>
                          <td style={{ padding: '8px 12px', textAlign: 'right' }}>
                            <ChevronRight size={14} color="var(--text-muted)" />
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </div>
            )}
          </div>
        )}

        {/* TAB 4: ARCHITECTURE & HEALTH */}
        {activeTab === 'topology' && (
          <div>
            <div style={{ marginBottom: '14px' }}>
              <h4 style={{ fontSize: '14px', fontWeight: 700, marginBottom: '4px' }}>
                System Topology & Microservices Status
              </h4>
              <p style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
                Real backend architecture with Spring Cloud Gateway single ingress and downstream services.
              </p>
            </div>

            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '10px', marginBottom: '14px' }}>
              <div className="glass-panel" style={{ padding: '12px' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '6px' }}>
                  <div style={{ width: '8px', height: '8px', borderRadius: '50%', background: 'var(--success)' }} />
                  <span style={{ fontWeight: 700, fontSize: '13px' }}>API Gateway (WebFlux)</span>
                </div>
                <p style={{ fontSize: '11px', color: 'var(--text-muted)' }}>
                  Host Port: <code>:8080</code> • Reverse Proxy & OAuth2 RS256 Verification
                </p>
              </div>

              <div className="glass-panel" style={{ padding: '12px' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '6px' }}>
                  <div style={{ width: '8px', height: '8px', borderRadius: '50%', background: 'var(--success)' }} />
                  <span style={{ fontWeight: 700, fontSize: '13px' }}>Identity & Driver Service</span>
                </div>
                <p style={{ fontSize: '11px', color: 'var(--text-muted)' }}>
                  Internal Port: <code>:8081</code> • PostgreSQL 15 (:5436) • Flyway V1..V4
                </p>
              </div>

              <div className="glass-panel" style={{ padding: '12px' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '6px' }}>
                  <div style={{ width: '8px', height: '8px', borderRadius: '50%', background: 'var(--success)' }} />
                  <span style={{ fontWeight: 700, fontSize: '13px' }}>Location Service</span>
                </div>
                <p style={{ fontSize: '11px', color: 'var(--text-muted)' }}>
                  Internal Port: <code>:8082</code> • Redis 7 (:6380) GEO & TTL 90s
                </p>
              </div>

              <div className="glass-panel" style={{ padding: '12px' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '6px' }}>
                  <div style={{ width: '8px', height: '8px', borderRadius: '50%', background: 'var(--reviewer)' }} />
                  <span style={{ fontWeight: 700, fontSize: '13px' }}>Frontend Dev Server</span>
                </div>
                <p style={{ fontSize: '11px', color: 'var(--text-muted)' }}>
                  Vite Port: <code>:3000</code> • Proxy <code>/api</code> $\rightarrow$ <code>:8080</code>
                </p>
              </div>
            </div>

            <div
              style={{
                background: 'var(--bg-surface-elevated)',
                padding: '12px',
                borderRadius: 'var(--radius-md)',
                fontSize: '12px',
                color: 'var(--text-secondary)',
                lineHeight: 1.6,
              }}
            >
              <strong style={{ color: 'var(--text-primary)' }}>Key Microservices Design Principles:</strong>
              <ul style={{ paddingLeft: '18px', marginTop: '6px' }}>
                <li>Web application only calls <code>http://localhost:8080/api/v1/...</code> (never direct to :8081 or :8082).</li>
                <li>JWT contains <code>roles</code> and <code>permissions</code>, avoiding database roundtrips on downstream calls.</li>
                <li>Driver location updates store ephemeral coordinates into Redis GEO with a 90-second heartbeat.</li>
              </ul>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};
