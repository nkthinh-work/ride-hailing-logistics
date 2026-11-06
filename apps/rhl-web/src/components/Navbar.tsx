import React from 'react';
import { Link, useNavigate, useLocation } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import {
  Car,
  Terminal,
  LogOut,
  User as UserIcon,
  ShieldCheck,
  LayoutDashboard,
  Navigation,
  DollarSign,
} from 'lucide-react';
import { useRealtimeConnection } from '../hooks/useRealtimeConnection';
import { ConnectionBadge } from './realtime/ConnectionBadge';

interface NavbarProps {
  onToggleDevConsole: () => void;
  devConsoleOpen: boolean;
  logCount: number;
}

export const Navbar: React.FC<NavbarProps> = ({
  onToggleDevConsole,
  devConsoleOpen,
  logCount,
}) => {
  const { user, isAuthenticated, logout } = useAuth();
  const { status: realtimeStatus, reconnect } = useRealtimeConnection();
  const navigate = useNavigate();
  const location = useLocation();

  const handleLogout = async () => {
    await logout();
    navigate('/login');
  };

  const getRoleBadgeClass = () => {
    if (!user?.roles?.length) return 'badge-customer';
    if (user.roles.includes('ADMINISTRATOR')) return 'badge-admin';
    if (user.roles.includes('REVIEWER')) return 'badge-reviewer';
    if (user.roles.includes('DRIVER')) return 'badge-driver';
    return 'badge-customer';
  };

  const primaryRole = user?.roles?.[0] || 'CUSTOMER';

  return (
    <header
      style={{
        height: '64px',
        borderBottom: '1px solid var(--border-subtle)',
        background: 'rgba(11, 15, 25, 0.85)',
        backdropFilter: 'blur(16px)',
        position: 'sticky',
        top: 0,
        zIndex: 50,
      }}
    >
      <div
        style={{
          maxWidth: '1280px',
          margin: '0 auto',
          height: '100%',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          padding: '0 20px',
        }}
      >
        {/* Brand & Nav */}
        <div style={{ display: 'flex', alignItems: 'center', gap: '28px' }}>
          <Link
            to="/"
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: '10px',
              textDecoration: 'none',
              color: 'var(--text-primary)',
            }}
          >
            <div
              style={{
                width: '36px',
                height: '36px',
                borderRadius: '8px',
                background: 'linear-gradient(135deg, #3B82F6 0%, #1D4ED8 100%)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                boxShadow: '0 0 12px var(--primary-glow)',
              }}
            >
              <Car size={20} color="white" />
            </div>
            <div>
              <span style={{ fontWeight: 800, fontSize: '17px', letterSpacing: '-0.3px' }}>
                RHL
              </span>
              <span
                style={{
                  fontSize: '11px',
                  fontWeight: 600,
                  marginLeft: '6px',
                  color: 'var(--primary)',
                  background: 'rgba(59, 130, 246, 0.1)',
                  padding: '2px 6px',
                  borderRadius: '4px',
                  border: '1px solid rgba(59, 130, 246, 0.2)',
                }}
              >
                PLATFORM
              </span>
            </div>
          </Link>

          {isAuthenticated && (
            <nav style={{ display: 'flex', gap: '6px' }}>
              <Link
                to="/"
                style={{
                  color: location.pathname === '/' || location.pathname === '/dashboard' ? 'var(--text-primary)' : 'var(--text-secondary)',
                  background: location.pathname === '/' || location.pathname === '/dashboard' ? 'rgba(255, 255, 255, 0.08)' : 'transparent',
                  textDecoration: 'none',
                  fontSize: '13px',
                  fontWeight: 600,
                  padding: '6px 12px',
                  borderRadius: '6px',
                  display: 'flex',
                  alignItems: 'center',
                  gap: '6px',
                  transition: 'all 0.2s',
                }}
              >
                <LayoutDashboard size={14} />
                <span>API Dashboard</span>
              </Link>

              <Link
                to="/customer"
                style={{
                  color: location.pathname.startsWith('/customer') ? '#60a5fa' : 'var(--text-secondary)',
                  background: location.pathname.startsWith('/customer') ? 'rgba(59, 130, 246, 0.15)' : 'transparent',
                  textDecoration: 'none',
                  fontSize: '13px',
                  fontWeight: 600,
                  padding: '6px 12px',
                  borderRadius: '6px',
                  display: 'flex',
                  alignItems: 'center',
                  gap: '6px',
                  transition: 'all 0.2s',
                }}
              >
                <Navigation size={14} />
                <span>Customer App</span>
              </Link>

              <Link
                to="/driver"
                style={{
                  color: location.pathname.startsWith('/driver') ? '#34d399' : 'var(--text-secondary)',
                  background: location.pathname.startsWith('/driver') ? 'rgba(16, 185, 129, 0.15)' : 'transparent',
                  textDecoration: 'none',
                  fontSize: '13px',
                  fontWeight: 600,
                  padding: '6px 12px',
                  borderRadius: '6px',
                  display: 'flex',
                  alignItems: 'center',
                  gap: '6px',
                  transition: 'all 0.2s',
                }}
              >
                <Car size={14} />
                <span>Driver App</span>
              </Link>

              {(user?.roles?.includes('FINANCE_STAFF') || user?.roles?.includes('ADMINISTRATOR')) && (
                <Link
                  to="/finance"
                  style={{
                    color: location.pathname.startsWith('/finance') ? '#f59e0b' : 'var(--text-secondary)',
                    background: location.pathname.startsWith('/finance') ? 'rgba(245, 158, 11, 0.15)' : 'transparent',
                    textDecoration: 'none',
                    fontSize: '13px',
                    fontWeight: 600,
                    padding: '6px 12px',
                    borderRadius: '6px',
                    display: 'flex',
                    alignItems: 'center',
                    gap: '6px',
                    transition: 'all 0.2s',
                  }}
                >
                  <DollarSign size={14} />
                  <span>Finance Console</span>
                </Link>
              )}
            </nav>
          )}
        </div>

        {/* Right Action Bar */}
        <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
          {/* Developer Console Toggle (Dev only) */}
          {import.meta.env.DEV && (
            <button
              onClick={onToggleDevConsole}
              className="btn btn-secondary btn-sm"
              style={{
                borderColor: devConsoleOpen ? 'var(--primary)' : 'var(--border-subtle)',
                background: devConsoleOpen ? 'rgba(59, 130, 246, 0.15)' : 'var(--bg-surface-elevated)',
              }}
              title="Toggle Developer Console (API Traffic & JWT Inspector)"
            >
              <Terminal size={14} color={devConsoleOpen ? 'var(--primary)' : 'var(--text-secondary)'} />
              <span style={{ fontSize: '12px', fontFamily: 'var(--font-mono)' }}>Dev Console</span>
              {logCount > 0 && (
                <span
                  style={{
                    background: 'var(--primary)',
                    color: 'white',
                    borderRadius: '999px',
                    fontSize: '10px',
                    padding: '1px 6px',
                    fontWeight: 700,
                  }}
                >
                  {logCount}
                </span>
              )}
            </button>
          )}

          {isAuthenticated ? (
            <>
              {/* Realtime STOMP Connection Indicator */}
              <ConnectionBadge status={realtimeStatus} onReconnect={reconnect} />

              {/* Active Role Indicator */}
              <div
                className={`badge ${getRoleBadgeClass()}`}
                style={{ display: 'flex', alignItems: 'center', gap: '6px' }}
              >
                <ShieldCheck size={13} />
                <span>{primaryRole}</span>
              </div>

              {/* User Dropdown / Email */}
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: '8px',
                  padding: '4px 10px',
                  background: 'var(--bg-surface-elevated)',
                  borderRadius: 'var(--radius-full)',
                  border: '1px solid var(--border-subtle)',
                }}
              >
                <div
                  style={{
                    width: '24px',
                    height: '24px',
                    borderRadius: '50%',
                    background: '#243048',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                  }}
                >
                  <UserIcon size={13} color="var(--text-secondary)" />
                </div>
                <span style={{ fontSize: '13px', fontWeight: 500, color: 'var(--text-primary)' }}>
                  {user?.email}
                </span>
              </div>

              {/* Logout Button */}
              <button
                onClick={handleLogout}
                className="btn btn-secondary btn-sm"
                title="Sign out of current account"
                style={{ padding: '6px 10px' }}
              >
                <LogOut size={14} color="var(--danger)" />
              </button>
            </>
          ) : (
            <div style={{ display: 'flex', gap: '8px' }}>
              <Link to="/login" className="btn btn-secondary btn-sm">
                Sign In
              </Link>
              <Link to="/register" className="btn btn-primary btn-sm">
                Register
              </Link>
            </div>
          )}
        </div>
      </div>
    </header>
  );
};
