import React, { useState } from 'react';
import type { DriverOfferPayload, AssignmentStatus } from '../../types';
import {
  Bell,
  MapPin,
  Clock,
  DollarSign,
  Navigation,
  CheckCircle,
  XCircle,
  AlertCircle,
  RefreshCw,
} from 'lucide-react';

interface DriverOfferCardProps {
  offer: DriverOfferPayload | null;
  assignmentStatus: AssignmentStatus | null;
  secondsRemaining: number;
  isSubmitting: boolean;
  errorMessage: string | null;
  onAccept: (offerId: string) => void;
  onReject: (offerId: string, reason?: string) => void;
  onDismiss: () => void;
  onRefresh?: () => void;
}

export const DriverOfferCard: React.FC<DriverOfferCardProps> = ({
  offer,
  assignmentStatus,
  secondsRemaining,
  isSubmitting,
  errorMessage,
  onAccept,
  onReject,
  onDismiss,
  onRefresh,
}) => {
  const [rejecting, setRejecting] = useState(false);
  const [rejectReason, setRejectReason] = useState('Driver is currently resting');

  if (!offer) {
    return (
      <div
        className="glass-panel"
        style={{
          padding: '24px',
          textAlign: 'center',
          color: 'var(--text-muted)',
          fontSize: '13px',
        }}
      >
        <div
          style={{
            width: '40px',
            height: '40px',
            borderRadius: '50%',
            background: 'rgba(255, 255, 255, 0.05)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            margin: '0 auto 12px auto',
          }}
        >
          <Bell size={20} color="var(--text-muted)" />
        </div>
        <p style={{ fontWeight: 600, color: 'var(--text-secondary)' }}>No Active Driver Offers</p>
        <p style={{ fontSize: '12px', marginTop: '4px' }}>
          When Dispatch finds a ride matching your vehicle and location, it will pop up here in real time.
        </p>
        {onRefresh && (
          <button
            onClick={onRefresh}
            className="btn btn-secondary btn-sm"
            style={{ marginTop: '12px', fontSize: '11px' }}
          >
            <RefreshCw size={12} /> Sync Offers
          </button>
        )}
      </div>
    );
  }

  const isExpired = assignmentStatus === 'EXPIRED' || secondsRemaining <= 0;
  const isAccepted = assignmentStatus === 'OFFER_ACCEPTED';
  const isAssignmentConfirmed = assignmentStatus === 'ASSIGNMENT_CONFIRMED';
  const isAcceptRequestSent = assignmentStatus === 'ACCEPT_REQUEST_SENT';
  const isRevoked = assignmentStatus === 'REVOKED';

  // Calculate countdown percentage (assume 30s max offer window if not known)
  const percentRemaining = Math.min(100, Math.max(0, (secondsRemaining / 30) * 100));

  return (
    <div
      className="glass-panel"
      style={{
        padding: '24px',
        border: isAssignmentConfirmed
          ? '2px solid var(--success)'
          : isExpired || isRevoked
          ? '1px solid var(--danger)'
          : '2px solid var(--primary)',
        boxShadow: isAssignmentConfirmed
          ? '0 0 20px rgba(16, 185, 129, 0.25)'
          : '0 0 20px var(--primary-glow)',
        transition: 'all 0.3s ease',
      }}
    >
      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '16px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
          <div
            style={{
              width: '36px',
              height: '36px',
              borderRadius: '8px',
              background: isAssignmentConfirmed
                ? 'rgba(16, 185, 129, 0.2)'
                : 'rgba(59, 130, 246, 0.2)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <Bell size={20} color={isAssignmentConfirmed ? 'var(--success)' : 'var(--primary)'} />
          </div>
          <div>
            <h3 style={{ fontSize: '16px', fontWeight: 700 }}>
              {isAssignmentConfirmed ? 'Trip Confirmed!' : 'Incoming Driver Offer'}
            </h3>
            <span
              style={{
                fontSize: '11px',
                fontFamily: 'var(--font-mono)',
                color: 'var(--text-muted)',
              }}
            >
              {offer.serviceType} • Offer #{offer.offerId.slice(0, 8)}
            </span>
          </div>
        </div>

        {/* Status Badge */}
        <div>
          {isAssignmentConfirmed && (
            <span className="badge badge-success" style={{ display: 'flex', alignItems: 'center', gap: '4px' }}>
              <CheckCircle size={12} /> CONFIRMED
            </span>
          )}
          {isAccepted && !isAssignmentConfirmed && (
            <span className="badge badge-pending" style={{ display: 'flex', alignItems: 'center', gap: '4px' }}>
              <RefreshCw size={12} className="animate-pulse" /> ASSIGNING...
            </span>
          )}
          {isAcceptRequestSent && !isAccepted && (
            <span className="badge badge-pending">ACCEPTING...</span>
          )}
          {isExpired && (
            <span className="badge badge-rejected" style={{ display: 'flex', alignItems: 'center', gap: '4px' }}>
              <XCircle size={12} /> EXPIRED
            </span>
          )}
          {isRevoked && (
            <span className="badge badge-rejected" style={{ display: 'flex', alignItems: 'center', gap: '4px' }}>
              <AlertCircle size={12} /> REVOKED
            </span>
          )}
          {!isAssignmentConfirmed && !isAccepted && !isAcceptRequestSent && !isExpired && !isRevoked && (
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: '6px',
                color: secondsRemaining <= 5 ? 'var(--danger)' : '#fbbf24',
                fontFamily: 'var(--font-mono)',
                fontWeight: 700,
                fontSize: '13px',
              }}
            >
              <Clock size={14} />
              <span>{secondsRemaining}s</span>
            </div>
          )}
        </div>
      </div>

      {/* Expiration Progress Bar */}
      {!isAssignmentConfirmed && !isExpired && !isRevoked && (
        <div
          style={{
            height: '4px',
            width: '100%',
            background: 'rgba(255, 255, 255, 0.1)',
            borderRadius: '2px',
            marginBottom: '16px',
            overflow: 'hidden',
          }}
        >
          <div
            style={{
              height: '100%',
              width: `${percentRemaining}%`,
              background: secondsRemaining <= 5 ? 'var(--danger)' : 'var(--primary)',
              transition: 'width 1s linear',
            }}
          />
        </div>
      )}

      {/* Error / Conflict Banner */}
      {errorMessage && (
        <div
          style={{
            padding: '10px 14px',
            borderRadius: 'var(--radius-sm)',
            fontSize: '12px',
            marginBottom: '14px',
            background: 'rgba(239, 68, 68, 0.15)',
            border: '1px solid rgba(239, 68, 68, 0.3)',
            color: '#f87171',
            display: 'flex',
            alignItems: 'center',
            gap: '8px',
          }}
        >
          <AlertCircle size={15} />
          <span>{errorMessage}</span>
        </div>
      )}

      {/* Offer Details */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: '12px', fontSize: '13px' }}>
        {/* Addresses */}
        <div
          style={{
            background: 'var(--bg-surface-elevated)',
            padding: '12px',
            borderRadius: 'var(--radius-md)',
            border: '1px solid var(--border-subtle)',
            display: 'flex',
            flexDirection: 'column',
            gap: '8px',
          }}
        >
          <div style={{ display: 'flex', alignItems: 'flex-start', gap: '8px' }}>
            <MapPin size={15} color="var(--primary)" style={{ marginTop: '2px' }} />
            <div>
              <span style={{ fontSize: '11px', color: 'var(--text-muted)', textTransform: 'uppercase' }}>
                Pickup Address
              </span>
              <p style={{ fontWeight: 600 }}>{offer.pickupAddress || 'Customer pickup coordinates'}</p>
            </div>
          </div>

          <div style={{ display: 'flex', alignItems: 'flex-start', gap: '8px' }}>
            <Navigation size={15} color="var(--success)" style={{ marginTop: '2px' }} />
            <div>
              <span style={{ fontSize: '11px', color: 'var(--text-muted)', textTransform: 'uppercase' }}>
                Dropoff Address
              </span>
              <p style={{ fontWeight: 600 }}>{offer.dropoffAddress || 'Customer destination'}</p>
            </div>
          </div>
        </div>

        {/* Metrics: Fare & Distance */}
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '10px' }}>
          <div
            style={{
              background: 'var(--bg-surface-elevated)',
              padding: '10px 12px',
              borderRadius: 'var(--radius-sm)',
              border: '1px solid var(--border-subtle)',
            }}
          >
            <span style={{ fontSize: '11px', color: 'var(--text-muted)', textTransform: 'uppercase' }}>
              Estimated Distance
            </span>
            <p style={{ fontWeight: 700, fontSize: '15px', marginTop: '2px' }}>
              {offer.estimatedDistanceKm != null ? `${offer.estimatedDistanceKm.toFixed(1)} km` : '—'}
            </p>
          </div>

          <div
            style={{
              background: 'var(--bg-surface-elevated)',
              padding: '10px 12px',
              borderRadius: 'var(--radius-sm)',
              border: '1px solid var(--border-subtle)',
            }}
          >
            <span style={{ fontSize: '11px', color: 'var(--text-muted)', textTransform: 'uppercase' }}>
              Estimated Fare
            </span>
            <p
              style={{
                fontWeight: 700,
                fontSize: '15px',
                marginTop: '2px',
                color: 'var(--success)',
                display: 'flex',
                alignItems: 'center',
                gap: '2px',
              }}
            >
              <DollarSign size={15} />
              {offer.estimatedFare != null
                ? `${new Intl.NumberFormat('vi-VN').format(offer.estimatedFare)} VND`
                : '—'}
            </p>
          </div>
        </div>

        {/* Actions */}
        {isAssignmentConfirmed ? (
          <div style={{ display: 'flex', gap: '8px', marginTop: '4px' }}>
            <div
              style={{
                flex: 1,
                padding: '10px',
                background: 'rgba(16, 185, 129, 0.15)',
                border: '1px solid var(--success)',
                borderRadius: 'var(--radius-md)',
                color: 'var(--success)',
                fontWeight: 600,
                textAlign: 'center',
                fontSize: '13px',
              }}
            >
              Trip Assigned! Proceed to Customer Pickup.
            </div>
          </div>
        ) : isExpired || isRevoked ? (
          <div style={{ display: 'flex', gap: '8px', marginTop: '4px' }}>
            <button
              onClick={onDismiss}
              className="btn btn-secondary btn-sm"
              style={{ flex: 1 }}
            >
              Dismiss
            </button>
            {onRefresh && (
              <button
                onClick={onRefresh}
                className="btn btn-primary btn-sm"
                style={{ flex: 1 }}
              >
                <RefreshCw size={13} /> Check Pending
              </button>
            )}
          </div>
        ) : rejecting ? (
          <div style={{ marginTop: '8px' }}>
            <input
              type="text"
              value={rejectReason}
              onChange={(e) => setRejectReason(e.target.value)}
              placeholder="Rejection reason..."
              className="input-field"
              style={{ fontSize: '12px', marginBottom: '8px' }}
            />
            <div style={{ display: 'flex', gap: '8px' }}>
              <button
                onClick={() => onReject(offer.offerId, rejectReason)}
                disabled={isSubmitting}
                className="btn btn-danger btn-sm"
                style={{ flex: 1 }}
              >
                Confirm Reject
              </button>
              <button
                onClick={() => setRejecting(false)}
                className="btn btn-secondary btn-sm"
              >
                Back
              </button>
            </div>
          </div>
        ) : (
          <div style={{ display: 'flex', gap: '10px', marginTop: '6px' }}>
            <button
              onClick={() => onAccept(offer.offerId)}
              disabled={isSubmitting || isAccepted || isAcceptRequestSent}
              className="btn btn-success btn-sm"
              style={{
                flex: 2,
                padding: '10px',
                fontSize: '14px',
                fontWeight: 700,
              }}
            >
              {isSubmitting || isAcceptRequestSent ? (
                <>
                  <RefreshCw size={14} className="animate-pulse" />
                  Accepting...
                </>
              ) : isAccepted ? (
                'Awaiting Assignment...'
              ) : (
                'Accept Offer'
              )}
            </button>
            <button
              onClick={() => setRejecting(true)}
              disabled={isSubmitting || isAccepted || isAcceptRequestSent}
              className="btn btn-danger btn-sm"
              style={{ flex: 1 }}
            >
              Reject
            </button>
          </div>
        )}
      </div>
    </div>
  );
};
