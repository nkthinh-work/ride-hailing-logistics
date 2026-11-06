import React, { useState } from 'react';
import type { TripResponse, TripStatus, TripLocationPayload } from '../../types';
import {
  Car,
  MapPin,
  Navigation,
  Compass,
  Gauge,
  AlertTriangle,
  RefreshCw,
  CheckCircle2,
  XCircle,
  Radio,
} from 'lucide-react';

interface TripLiveTrackerProps {
  trip: TripResponse | null;
  tripStatus: TripStatus | null;
  location: TripLocationPayload | null;
  isStreamable: boolean;
  isLoadingSnapshot: boolean;
  isStale: boolean;
  lastUpdated: Date | null;
  isDriver?: boolean;
  onRefresh: () => void;
  onCancelTrip?: (reason: string) => Promise<void>;
  onStartPickup?: () => Promise<void>;
  onArrive?: () => Promise<void>;
  onStartTrip?: () => Promise<void>;
  onCompleteTrip?: () => Promise<void>;
}

const LIFECYCLE_STEPS: TripStatus[] = [
  'MATCHING',
  'ACCEPTED',
  'PICKING_UP',
  'ARRIVED',
  'IN_TRIP',
  'COMPLETED',
];

export const TripLiveTracker: React.FC<TripLiveTrackerProps> = ({
  trip,
  tripStatus,
  location,
  isStreamable,
  isLoadingSnapshot,
  isStale,
  lastUpdated,
  isDriver,
  onRefresh,
  onCancelTrip,
  onStartPickup,
  onArrive,
  onStartTrip,
  onCompleteTrip,
}) => {
  const [cancelling, setCancelling] = useState(false);
  const [cancelReason, setCancelReason] = useState('Change of plans');
  const [actionLoading, setActionLoading] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  if (!trip && !tripStatus) {
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
        <Car size={24} style={{ margin: '0 auto 8px auto', opacity: 0.5 }} />
        <p style={{ fontWeight: 600, color: 'var(--text-secondary)' }}>No Active Trip</p>
        <p style={{ fontSize: '12px', marginTop: '4px' }}>
          When a ride is booked or assigned, live lifecycle updates and driver GPS telemetry will appear here.
        </p>
      </div>
    );
  }

  const currentStatus = tripStatus || trip?.status || 'MATCHING';
  const isTerminal = currentStatus === 'COMPLETED' || currentStatus === 'CANCELLED' || currentStatus === 'NO_DRIVER';

  const speedKmh = location?.speedMetersPerSecond != null
    ? (location.speedMetersPerSecond * 3.6).toFixed(1)
    : null;

  const currentStepIndex = LIFECYCLE_STEPS.indexOf(currentStatus);

  const handleAction = async (fn?: () => Promise<void>) => {
    if (!fn) return;
    setActionLoading(true);
    setActionError(null);
    try {
      await fn();
    } catch (err: unknown) {
      const errObj = err as { message?: string };
      setActionError(errObj?.message || 'Operation failed');
    } finally {
      setActionLoading(false);
    }
  };

  const handleConfirmCancel = async () => {
    if (!onCancelTrip) return;
    setActionLoading(true);
    setActionError(null);
    try {
      await onCancelTrip(cancelReason);
      setCancelling(false);
    } catch (err: unknown) {
      const errObj = err as { message?: string };
      setActionError(errObj?.message || 'Failed to cancel trip');
    } finally {
      setActionLoading(false);
    }
  };

  return (
    <div className="glass-panel" style={{ padding: '24px' }}>
      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '16px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
          <div
            style={{
              width: '36px',
              height: '36px',
              borderRadius: '8px',
              background: 'rgba(59, 130, 246, 0.2)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <Car size={20} color="var(--primary)" />
          </div>
          <div>
            <h3 style={{ fontSize: '16px', fontWeight: 700 }}>
              Live Trip #{trip?.tripId ? trip.tripId.slice(0, 8) : 'Tracking'}
            </h3>
            <span style={{ fontSize: '11px', color: 'var(--text-muted)' }}>
              {trip?.serviceType || 'Standard'} • {trip?.vehicleType || 'Vehicle'}
            </span>
          </div>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
          <span
            className={`badge ${
              currentStatus === 'COMPLETED'
                ? 'badge-success'
                : currentStatus === 'CANCELLED' || currentStatus === 'NO_DRIVER'
                ? 'badge-rejected'
                : 'badge-driver'
            }`}
          >
            {currentStatus}
          </span>
          <button
            onClick={onRefresh}
            disabled={isLoadingSnapshot}
            className="btn btn-secondary btn-sm"
            title="Refresh Authoritative State"
            style={{ padding: '6px' }}
          >
            <RefreshCw size={13} className={isLoadingSnapshot ? 'animate-pulse' : ''} />
          </button>
        </div>
      </div>

      {/* Progression Bar */}
      {!isTerminal && (
        <div style={{ marginBottom: '20px' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '8px', fontSize: '11px' }}>
            {LIFECYCLE_STEPS.map((step, idx) => {
              const isPastOrCurrent = currentStepIndex >= idx;
              return (
                <span
                  key={step}
                  style={{
                    color: isPastOrCurrent ? 'var(--text-primary)' : 'var(--text-muted)',
                    fontWeight: currentStatus === step ? 700 : 500,
                  }}
                >
                  {step}
                </span>
              );
            })}
          </div>
          <div
            style={{
              height: '4px',
              width: '100%',
              background: 'rgba(255, 255, 255, 0.1)',
              borderRadius: '2px',
              position: 'relative',
              overflow: 'hidden',
            }}
          >
            <div
              style={{
                height: '100%',
                width: `${Math.max(0, Math.min(100, (currentStepIndex / (LIFECYCLE_STEPS.length - 1)) * 100))}%`,
                background: 'linear-gradient(90deg, #3B82F6, #10B981)',
                transition: 'width 0.4s ease',
              }}
            />
          </div>
        </div>
      )}

      {/* Action Error Banner */}
      {actionError && (
        <div
          style={{
            padding: '8px 12px',
            borderRadius: 'var(--radius-sm)',
            fontSize: '12px',
            marginBottom: '12px',
            background: 'rgba(239, 68, 68, 0.15)',
            border: '1px solid rgba(239, 68, 68, 0.3)',
            color: '#f87171',
          }}
        >
          {actionError}
        </div>
      )}

      {/* Trip Addresses */}
      <div
        style={{
          background: 'var(--bg-surface-elevated)',
          padding: '12px',
          borderRadius: 'var(--radius-md)',
          border: '1px solid var(--border-subtle)',
          display: 'flex',
          flexDirection: 'column',
          gap: '8px',
          marginBottom: '16px',
          fontSize: '13px',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'flex-start', gap: '8px' }}>
          <MapPin size={14} color="var(--primary)" style={{ marginTop: '2px' }} />
          <div>
            <span style={{ fontSize: '11px', color: 'var(--text-muted)', textTransform: 'uppercase' }}>
              Pickup
            </span>
            <p style={{ fontWeight: 600 }}>{trip?.pickupAddress || 'Pickup Point'}</p>
          </div>
        </div>
        <div style={{ display: 'flex', alignItems: 'flex-start', gap: '8px' }}>
          <Navigation size={14} color="var(--success)" style={{ marginTop: '2px' }} />
          <div>
            <span style={{ fontSize: '11px', color: 'var(--text-muted)', textTransform: 'uppercase' }}>
              Destination
            </span>
            <p style={{ fontWeight: 600 }}>{trip?.dropoffAddress || 'Dropoff Point'}</p>
          </div>
        </div>
      </div>

      {/* Live GPS Telemetry Card (Streamable Room) */}
      {isStreamable ? (
        <div
          style={{
            background: 'var(--bg-surface-elevated)',
            padding: '14px',
            borderRadius: 'var(--radius-md)',
            border: isStale ? '1px solid #f59e0b' : '1px solid rgba(16, 185, 129, 0.3)',
            marginBottom: '16px',
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '10px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
              <Radio size={15} color={isStale ? '#f59e0b' : 'var(--success)'} className={isStale ? '' : 'animate-pulse'} />
              <span style={{ fontSize: '12px', fontWeight: 700 }}>
                {isStale ? 'GPS Telemetry (Stale >15s)' : 'Live Driver Telemetry'}
              </span>
            </div>
            {lastUpdated && (
              <span style={{ fontSize: '11px', color: 'var(--text-muted)', fontFamily: 'var(--font-mono)' }}>
                {lastUpdated.toLocaleTimeString()}
              </span>
            )}
          </div>

          {location ? (
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(130px, 1fr))', gap: '8px', fontSize: '12px' }}>
              <div style={{ background: 'rgba(255, 255, 255, 0.03)', padding: '8px', borderRadius: '4px' }}>
                <span style={{ fontSize: '10px', color: 'var(--text-muted)' }}>LAT / LNG</span>
                <p style={{ fontFamily: 'var(--font-mono)', fontWeight: 600 }}>
                  {location.latitude.toFixed(4)}, {location.longitude.toFixed(4)}
                </p>
              </div>

              <div style={{ background: 'rgba(255, 255, 255, 0.03)', padding: '8px', borderRadius: '4px' }}>
                <span style={{ fontSize: '10px', color: 'var(--text-muted)', display: 'flex', alignItems: 'center', gap: '4px' }}>
                  <Gauge size={11} /> SPEED
                </span>
                <p style={{ fontFamily: 'var(--font-mono)', fontWeight: 600 }}>
                  {speedKmh != null ? `${speedKmh} km/h` : '0.0 km/h'}
                </p>
              </div>

              <div style={{ background: 'rgba(255, 255, 255, 0.03)', padding: '8px', borderRadius: '4px' }}>
                <span style={{ fontSize: '10px', color: 'var(--text-muted)', display: 'flex', alignItems: 'center', gap: '4px' }}>
                  <Compass size={11} /> HEADING
                </span>
                <p style={{ fontFamily: 'var(--font-mono)', fontWeight: 600 }}>
                  {location.headingDegrees != null ? `${location.headingDegrees.toFixed(0)}°` : '—'}
                </p>
              </div>
            </div>
          ) : (
            <div style={{ textAlign: 'center', padding: '12px', color: 'var(--text-muted)', fontSize: '12px' }}>
              {isLoadingSnapshot ? 'Connecting to trip room & fetching snapshot...' : 'Awaiting driver GPS updates...'}
            </div>
          )}
        </div>
      ) : isTerminal ? (
        <div
          style={{
            padding: '12px',
            background: 'rgba(255, 255, 255, 0.03)',
            borderRadius: 'var(--radius-md)',
            textAlign: 'center',
            fontSize: '12px',
            color: 'var(--text-muted)',
            marginBottom: '16px',
          }}
        >
          {currentStatus === 'COMPLETED' ? (
            <span style={{ color: 'var(--success)', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '6px' }}>
              <CheckCircle2 size={14} /> Trip Completed. GPS room tracking closed.
            </span>
          ) : (
            <span style={{ color: 'var(--danger)', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '6px' }}>
              <XCircle size={14} /> Trip Terminated ({currentStatus}). GPS room closed.
            </span>
          )}
        </div>
      ) : null}

      {/* Driver Lifecycle Controls (if authenticated driver assigned to this trip) */}
      {isDriver && !isTerminal && (
        <div style={{ marginBottom: '14px', display: 'flex', gap: '8px' }}>
          {currentStatus === 'ACCEPTED' && onStartPickup && (
            <button
              onClick={() => handleAction(onStartPickup)}
              disabled={actionLoading}
              className="btn btn-primary btn-sm"
              style={{ flex: 1 }}
            >
              Start Pickup
            </button>
          )}
          {currentStatus === 'PICKING_UP' && onArrive && (
            <button
              onClick={() => handleAction(onArrive)}
              disabled={actionLoading}
              className="btn btn-primary btn-sm"
              style={{ flex: 1 }}
            >
              Confirm Arrived
            </button>
          )}
          {currentStatus === 'ARRIVED' && onStartTrip && (
            <button
              onClick={() => handleAction(onStartTrip)}
              disabled={actionLoading}
              className="btn btn-success btn-sm"
              style={{ flex: 1 }}
            >
              Start Trip
            </button>
          )}
          {currentStatus === 'IN_TRIP' && onCompleteTrip && (
            <button
              onClick={() => handleAction(onCompleteTrip)}
              disabled={actionLoading}
              className="btn btn-success btn-sm"
              style={{ flex: 1 }}
            >
              Complete Trip
            </button>
          )}
        </div>
      )}

      {/* Cancellation Section */}
      {!isTerminal && onCancelTrip && (
        <div>
          {cancelling ? (
            <div
              style={{
                background: 'rgba(239, 68, 68, 0.08)',
                border: '1px solid rgba(239, 68, 68, 0.25)',
                padding: '12px',
                borderRadius: 'var(--radius-md)',
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: '6px', color: '#f87171', fontSize: '12px', marginBottom: '8px' }}>
                <AlertTriangle size={14} />
                <strong>Cancel Trip Confirmation</strong>
              </div>
              <p style={{ fontSize: '11px', color: 'var(--text-muted)', marginBottom: '8px' }}>
                Note: Cancellation policy fee calculation is subject to backend settlement (FEE_POLICY_PENDING).
              </p>
              <input
                type="text"
                value={cancelReason}
                onChange={(e) => setCancelReason(e.target.value)}
                placeholder="Reason for cancellation..."
                className="input-field"
                style={{ fontSize: '12px', marginBottom: '8px' }}
              />
              <div style={{ display: 'flex', gap: '8px' }}>
                <button
                  onClick={handleConfirmCancel}
                  disabled={actionLoading}
                  className="btn btn-danger btn-sm"
                  style={{ flex: 1 }}
                >
                  {actionLoading ? 'Cancelling...' : 'Confirm Cancellation'}
                </button>
                <button
                  onClick={() => setCancelling(false)}
                  className="btn btn-secondary btn-sm"
                >
                  Keep Trip
                </button>
              </div>
            </div>
          ) : (
            <button
              onClick={() => setCancelling(true)}
              className="btn btn-secondary btn-sm"
              style={{ width: '100%', color: 'var(--danger)', fontSize: '12px' }}
            >
              Cancel Trip
            </button>
          )}
        </div>
      )}
    </div>
  );
};
