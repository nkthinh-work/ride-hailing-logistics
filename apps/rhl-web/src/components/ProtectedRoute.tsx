import React from 'react';
import { Navigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import type { PermissionCode, RoleCode } from '../types';

import { ShieldAlert, ArrowLeft } from 'lucide-react';

interface ProtectedRouteProps {
  children: React.ReactNode;
  requiredRole?: RoleCode;
  requiredRoles?: RoleCode[];
  requiredPermission?: PermissionCode;
}

export const ProtectedRoute: React.FC<ProtectedRouteProps> = ({
  children,
  requiredRole,
  requiredRoles,
  requiredPermission,
}) => {
  const { isAuthenticated, isLoading, hasRole, hasPermission, user } = useAuth();

  if (isLoading) {
    return (
      <div
        style={{
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          minHeight: '60vh',
          gap: '16px',
        }}
      >
        <div
          style={{
            width: '40px',
            height: '40px',
            border: '3px solid rgba(59, 130, 246, 0.2)',
            borderTopColor: 'var(--primary)',
            borderRadius: '50%',
            animation: 'spin 0.8s linear infinite',
          }}
        />
        <style>{`@keyframes spin { to { transform: rotate(360deg); } }`}</style>
        <span style={{ color: 'var(--text-secondary)', fontSize: '14px' }}>
          Validating authorization context...
        </span>
      </div>
    );
  }

  if (!isAuthenticated) {
    return <Navigate to="/login" replace />;
  }

  if (requiredRole && !hasRole(requiredRole)) {
    return (
      <div
        style={{
          maxWidth: '600px',
          margin: '80px auto',
          padding: '32px',
          textAlign: 'center',
        }}
        className="glass-panel"
      >
        <div
          style={{
            width: '64px',
            height: '64px',
            borderRadius: '50%',
            background: 'rgba(239, 68, 68, 0.1)',
            border: '1px solid rgba(239, 68, 68, 0.2)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            margin: '0 auto 20px',
          }}
        >
          <ShieldAlert size={32} color="var(--danger)" />
        </div>
        <h2 style={{ fontSize: '20px', fontWeight: 700, marginBottom: '8px' }}>
          403 — Access Denied
        </h2>
        <p style={{ color: 'var(--text-secondary)', fontSize: '14px', marginBottom: '24px' }}>
          This section requires the <strong style={{ color: 'var(--primary)' }}>{requiredRole}</strong> role.
          Your current roles are: <code>{user?.roles?.join(', ') || 'NONE'}</code>.
        </p>
        <button
          onClick={() => window.history.back()}
          className="btn btn-secondary"
          style={{ margin: '0 auto' }}
        >
          <ArrowLeft size={16} /> Go Back
        </button>
      </div>
    );
  }

  if (requiredRoles && requiredRoles.length > 0 && !requiredRoles.some((r) => hasRole(r))) {
    return (
      <div
        style={{
          maxWidth: '600px',
          margin: '80px auto',
          padding: '32px',
          textAlign: 'center',
        }}
        className="glass-panel"
      >
        <div
          style={{
            width: '64px',
            height: '64px',
            borderRadius: '50%',
            background: 'rgba(239, 68, 68, 0.1)',
            border: '1px solid rgba(239, 68, 68, 0.2)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            margin: '0 auto 20px',
          }}
        >
          <ShieldAlert size={32} color="var(--danger)" />
        </div>
        <h2 style={{ fontSize: '20px', fontWeight: 700, marginBottom: '8px' }}>
          403 — Access Denied
        </h2>
        <p style={{ color: 'var(--text-secondary)', fontSize: '14px', marginBottom: '24px' }}>
          This section requires one of the following roles: <strong style={{ color: 'var(--primary)' }}>{requiredRoles.join(' or ')}</strong>.
          Your current roles are: <code>{user?.roles?.join(', ') || 'NONE'}</code>.
        </p>
        <button
          onClick={() => window.history.back()}
          className="btn btn-secondary"
          style={{ margin: '0 auto' }}
        >
          <ArrowLeft size={16} /> Go Back
        </button>
      </div>
    );
  }

  if (requiredPermission && !hasPermission(requiredPermission)) {
    return (
      <div
        style={{
          maxWidth: '600px',
          margin: '80px auto',
          padding: '32px',
          textAlign: 'center',
        }}
        className="glass-panel"
      >
        <div
          style={{
            width: '64px',
            height: '64px',
            borderRadius: '50%',
            background: 'rgba(239, 68, 68, 0.1)',
            border: '1px solid rgba(239, 68, 68, 0.2)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            margin: '0 auto 20px',
          }}
        >
          <ShieldAlert size={32} color="var(--danger)" />
        </div>
        <h2 style={{ fontSize: '20px', fontWeight: 700, marginBottom: '8px' }}>
          403 — Missing Permission
        </h2>
        <p style={{ color: 'var(--text-secondary)', fontSize: '14px', marginBottom: '24px' }}>
          This action requires the <strong style={{ color: 'var(--reviewer)' }}>{requiredPermission}</strong> authority.
        </p>
        <button
          onClick={() => window.history.back()}
          className="btn btn-secondary"
          style={{ margin: '0 auto' }}
        >
          <ArrowLeft size={16} /> Go Back
        </button>
      </div>
    );
  }

  return <>{children}</>;
};
