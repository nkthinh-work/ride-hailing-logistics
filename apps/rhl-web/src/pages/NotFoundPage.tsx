import React from 'react';
import { Link } from 'react-router-dom';
import { ArrowLeft, Compass } from 'lucide-react';

export const NotFoundPage: React.FC = () => {
  return (
    <div
      style={{
        minHeight: 'calc(100vh - 64px)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        padding: '24px',
        textAlign: 'center',
      }}
    >
      <div className="glass-panel" style={{ maxWidth: '440px', padding: '40px 32px' }}>
        <div
          style={{
            width: '64px',
            height: '64px',
            borderRadius: '50%',
            background: 'rgba(59, 130, 246, 0.1)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            margin: '0 auto 16px',
          }}
        >
          <Compass size={32} color="var(--primary)" />
        </div>
        <h1 style={{ fontSize: '36px', fontWeight: 800, marginBottom: '6px' }}>404</h1>
        <h2 style={{ fontSize: '18px', fontWeight: 600, marginBottom: '10px' }}>Page Not Found</h2>
        <p style={{ color: 'var(--text-secondary)', fontSize: '14px', marginBottom: '24px' }}>
          The requested route does not exist on the RHL platform.
        </p>
        <Link to="/" className="btn btn-primary" style={{ margin: '0 auto', display: 'inline-flex' }}>
          <ArrowLeft size={16} /> Return to Dashboard
        </Link>
      </div>
    </div>
  );
};
