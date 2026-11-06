import React, { useState, useEffect, useCallback } from 'react';
import { useAuth } from '../context/AuthContext';
import { driverService } from '../services/driverService';
import { locationService } from '../services/locationService';
import type {
  DriverApplicationResponse,
  DriverEligibilityResponse,
  DriverLocationResponse,
  NearbyDriver,
  ApiError,
} from '../types';
import {
  User,
  Shield,
  Navigation,
  Send,
  Radio,
  FileCheck,
  RefreshCw,
  MapPin,
  Search,
  Car,
  AlertTriangle,
} from 'lucide-react';
import { useDriverOffers } from '../hooks/useDriverOffers';
import { useTripRealtime } from '../hooks/useTripRealtime';
import { useTripLocation } from '../hooks/useTripLocation';
import { tripService } from '../services/tripService';
import { DriverOfferCard } from '../components/realtime/DriverOfferCard';
import { TripLiveTracker } from '../components/realtime/TripLiveTracker';

export const DashboardPage: React.FC = () => {
  const { user, hasRole, hasPermission, reloadUser } = useAuth();

  // Identity Profile UI States
  const [profileLoading, setProfileLoading] = useState(false);
  const [profileError, setProfileError] = useState<string | null>(null);

  const handleRefreshProfile = async () => {
    setProfileLoading(true);
    setProfileError(null);
    try {
      await reloadUser();
    } catch (err: unknown) {
      const apiErr = err as ApiError;
      setProfileError(apiErr.message || 'Không thể tải thông tin định danh từ /api/v1/auth/me');
    } finally {
      setProfileLoading(false);
    }
  };

  // Driver Realtime Offers (M07)
  const {
    activeOffer,
    assignmentStatus,
    secondsRemaining,
    isSubmitting: offerSubmitting,
    errorMessage: offerError,
    acceptOffer,
    rejectOffer,
    clearOffer,
    refreshOffers,
  } = useDriverOffers();

  // Active Trip State & Realtime Tracker (M07)
  const [trackedTripId, setTrackedTripId] = useState<string>('');
  const [inputTripId, setInputTripId] = useState<string>('');

  // Automatically track trip when driver assignment is confirmed
  useEffect(() => {
    if (activeOffer && assignmentStatus === 'ASSIGNMENT_CONFIRMED' && activeOffer.tripId) {
      setTrackedTripId(activeOffer.tripId);
    }
  }, [activeOffer, assignmentStatus]);

  const {
    trip,
    tripStatus,
    syncTrip,
    cancelTrip,
  } = useTripRealtime(trackedTripId || null);

  const {
    location: liveLocation,
    isStreamable,
    isLoadingSnapshot,
    isStale,
    lastUpdated,
    refreshSnapshot,
  } = useTripLocation(trackedTripId || null, tripStatus);

  // Driver Application State
  const [application, setApplication] = useState<DriverApplicationResponse | null>(null);
  const [appLoading, setAppLoading] = useState(false);
  const [appNotFound, setAppNotFound] = useState(false);
  const [appError, setAppError] = useState<string | null>(null);
  const [appForm, setAppForm] = useState({
    fullName: '',
    phone: '',
    driverLicenseNumber: '',
    vehiclePlate: '',
  });
  const [appSubmitting, setAppSubmitting] = useState(false);
  const [appMsg, setAppMsg] = useState<{ text: string; error?: boolean } | null>(null);

  // Driver Eligibility State
  const [eligibility, setEligibility] = useState<DriverEligibilityResponse | null>(null);
  const [eligibilityLoading, setEligibilityLoading] = useState(false);

  // Driver Telemetry State
  const [driverLoc, setDriverLoc] = useState<DriverLocationResponse | null>(null);
  const [locLoading, setLocLoading] = useState(false);
  const [coords, setCoords] = useState({ longitude: 105.8342, latitude: 21.0278 });
  const [locMsg, setLocMsg] = useState<{ text: string; error?: boolean } | null>(null);

  // Reviewer State
  const [pendingApps, setPendingApps] = useState<DriverApplicationResponse[]>([]);
  const [reviewerLoading, setReviewerLoading] = useState(false);
  const [rejectingId, setRejectingId] = useState<string | null>(null);
  const [rejectReason, setRejectReason] = useState('Invalid vehicle registration document');
  const [reviewerMsg, setReviewerMsg] = useState<{ text: string; error?: boolean } | null>(null);

  // Admin Radar State
  const [nearbyDrivers, setNearbyDrivers] = useState<NearbyDriver[]>([]);
  const [radarLoading, setRadarLoading] = useState(false);
  const [radarParams, setRadarParams] = useState({ longitude: 105.8342, latitude: 21.0278, radiusKm: 5 });

  // Load Driver Application
  const loadMyApplication = useCallback(async () => {
    setAppLoading(true);
    setAppError(null);
    try {
      const app = await driverService.getMyApplication();
      setApplication(app);
      setAppNotFound(false);
      setAppError(null);
    } catch (err: unknown) {
      const apiErr = err as ApiError;
      if (apiErr.status === 404 || apiErr.code === 'DRIVER_APPLICATION_NOT_FOUND') {
        // Confirmed application not found in backend
        setApplication(null);
        setAppNotFound(true);
        setAppError(null);
      } else {
        // Auth/Server/Network error: do not assume "no application", keep existing application if any
        setAppNotFound(false);
        setAppError(apiErr.message || `Lỗi tải hồ sơ tài xế (${apiErr.status || 'Network'})`);
      }
    } finally {
      setAppLoading(false);
    }
  }, []);

  // Load Driver Eligibility
  const loadMyEligibility = useCallback(async () => {
    setEligibilityLoading(true);
    try {
      const elig = await driverService.getMyEligibility();
      setEligibility(elig);
    } catch {
      setEligibility(null);
    } finally {
      setEligibilityLoading(false);
    }
  }, []);

  // Load Driver Location (if Driver)
  const loadMyLocation = useCallback(async () => {
    if (!hasRole('DRIVER')) return;
    setLocLoading(true);
    try {
      const loc = await locationService.getMyLocation();
      setDriverLoc(loc);
      if (loc.longitude && loc.latitude) {
        setCoords({ longitude: loc.longitude, latitude: loc.latitude });
      }
    } catch {
      setDriverLoc(null);
    } finally {
      setLocLoading(false);
    }
  }, [hasRole]);

  // Load Pending Applications (if Reviewer)
  const loadPendingApplications = useCallback(async () => {
    if (!hasPermission('DRIVER_REVIEW')) return;
    setReviewerLoading(true);
    try {
      const apps = await driverService.listApplications('PENDING');
      setPendingApps(apps);
    } catch {
      setPendingApps([]);
    } finally {
      setReviewerLoading(false);
    }
  }, [hasPermission]);

  // Radar Scan (if Admin)
  const handleRadarScan = useCallback(async () => {
    if (!hasRole('ADMINISTRATOR')) return;
    setRadarLoading(true);
    try {
      const drivers = await locationService.getNearbyDrivers(
        radarParams.longitude,
        radarParams.latitude,
        radarParams.radiusKm,
        15
      );
      setNearbyDrivers(drivers);
    } catch {
      setNearbyDrivers([]);
    } finally {
      setRadarLoading(false);
    }
  }, [hasRole, radarParams]);

  useEffect(() => {
    loadMyApplication();
    loadMyEligibility();
    loadMyLocation();
    loadPendingApplications();
    if (hasRole('ADMINISTRATOR')) {
      handleRadarScan();
    }
  }, [loadMyApplication, loadMyEligibility, loadMyLocation, loadPendingApplications, handleRadarScan, hasRole]);

  // Handle Driver Application Submit
  const handleApply = async (e: React.FormEvent) => {
    e.preventDefault();
    setAppSubmitting(true);
    setAppMsg(null);
    try {
      const res = await driverService.apply(appForm);
      setApplication(res);
      setAppNotFound(false);
      setAppError(null);
      setAppMsg({ text: 'Hồ sơ đã được gửi thành công! Trạng thái: PENDING (chờ Reviewer xét duyệt).' });
      loadMyApplication();
      loadMyEligibility();
    } catch (err: unknown) {
      setAppMsg({ text: (err as ApiError).message || 'Failed to apply', error: true });
    } finally {
      setAppSubmitting(false);
    }
  };

  // Handle Driver Location Online
  const handleGoOnline = async () => {
    setLocMsg(null);
    try {
      const res = await locationService.turnOnline(coords.longitude, coords.latitude);
      setDriverLoc(res);
      setLocMsg({ text: 'You are now ONLINE. Redis GEO position indexed.' });
    } catch (err: unknown) {
      setLocMsg({ text: (err as ApiError).message || 'Failed to go online', error: true });
    }
  };

  // Handle Driver Location Offline
  const handleGoOffline = async () => {
    setLocMsg(null);
    try {
      const res = await locationService.turnOffline();
      setDriverLoc(res);
      setLocMsg({ text: 'You are now OFFLINE. Live heartbeat key removed.' });
    } catch (err: unknown) {
      setLocMsg({ text: (err as ApiError).message || 'Failed to go offline', error: true });
    }
  };

  // Handle Driver Location Update
  const handleUpdateLocation = async () => {
    setLocMsg(null);
    try {
      const res = await locationService.updateLocation(coords.longitude, coords.latitude, driverLoc?.sessionId);
      setDriverLoc(res);
      setLocMsg({ text: `Updated GPS to [${coords.longitude}, ${coords.latitude}]` });
    } catch (err: unknown) {
      setLocMsg({ text: (err as ApiError).message || 'Failed to update GPS', error: true });
    }
  };

  // Reviewer Approve
  const handleApprove = async (id: string) => {
    setReviewerMsg(null);
    try {
      await driverService.approve(id);
      setReviewerMsg({ text: `Approved driver application ${id.slice(0, 8)}...` });
      loadPendingApplications();
      loadMyApplication();
      loadMyEligibility();
      reloadUser();
    } catch (err: unknown) {
      setReviewerMsg({ text: (err as ApiError).message || 'Failed to approve', error: true });
    }
  };

  // Reviewer Reject
  const handleReject = async (id: string) => {
    setReviewerMsg(null);
    try {
      await driverService.reject(id, rejectReason);
      setReviewerMsg({ text: `Rejected application ${id.slice(0, 8)}...` });
      setRejectingId(null);
      loadPendingApplications();
      loadMyApplication();
      loadMyEligibility();
    } catch (err: unknown) {
      setReviewerMsg({ text: (err as ApiError).message || 'Failed to reject', error: true });
    }
  };

  return (
    <div style={{ maxWidth: '1280px', margin: '0 auto', padding: '32px 20px' }}>
      {/* Welcome Banner */}
      <div style={{ marginBottom: '28px' }}>
        <h1 style={{ fontSize: '24px', fontWeight: 800, letterSpacing: '-0.5px' }}>
          Platform Dashboard
        </h1>
        <p style={{ color: 'var(--text-secondary)', fontSize: '14px', marginTop: '4px' }}>
          Real-time interface directly integrated with API Gateway (:8080) and microservices backend.
        </p>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(360px, 1fr))', gap: '20px' }}>
        {/* CARD 1: Identity & RBAC Profile */}
        <div className="glass-panel" style={{ padding: '24px' }}>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '16px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
              <div
                style={{
                  width: '36px',
                  height: '36px',
                  borderRadius: '8px',
                  background: 'rgba(59, 130, 246, 0.15)',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                }}
              >
                <User size={20} color="var(--primary)" />
              </div>
              <h3 style={{ fontSize: '16px', fontWeight: 700 }}>Identity Profile</h3>
            </div>
            <button
              onClick={handleRefreshProfile}
              disabled={profileLoading}
              className="btn btn-secondary btn-sm"
              title="Refresh /api/v1/auth/me"
              style={{ padding: '6px' }}
            >
              <RefreshCw size={13} className={profileLoading ? 'animate-spin' : ''} />
            </button>
          </div>

          {profileError && (
            <div
              style={{
                padding: '8px 12px',
                borderRadius: '6px',
                background: 'rgba(239, 68, 68, 0.15)',
                border: '1px solid rgba(239, 68, 68, 0.25)',
                color: '#f87171',
                fontSize: '12px',
                marginBottom: '12px',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
              }}
            >
              <span>{profileError}</span>
              <button
                onClick={handleRefreshProfile}
                className="btn btn-secondary btn-sm"
                style={{ padding: '2px 8px', fontSize: '11px' }}
              >
                Thử lại
              </button>
            </div>
          )}

          {!user && !profileLoading && !profileError ? (
            <div style={{ color: 'var(--text-muted)', fontSize: '13px', padding: '12px 0' }}>
              Chưa có dữ liệu hồ sơ người dùng. Nhấn nút làm mới để tải từ backend.
            </div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '12px', fontSize: '13px' }}>
              <div>
                <span style={{ color: 'var(--text-muted)', fontSize: '11px', textTransform: 'uppercase' }}>
                  Email Account
                </span>
                <p style={{ fontWeight: 600, color: 'var(--text-primary)' }}>{user?.email || 'Chưa cập nhật'}</p>
              </div>

              <div>
                <span style={{ color: 'var(--text-muted)', fontSize: '11px', textTransform: 'uppercase' }}>
                  User ID (UUID)
                </span>
                <p style={{ fontFamily: 'var(--font-mono)', fontSize: '12px', color: 'var(--text-secondary)' }}>
                  {user?.id || user?.userId || 'Chưa cập nhật'}
                </p>
              </div>

              <div>
                <span style={{ color: 'var(--text-muted)', fontSize: '11px', textTransform: 'uppercase' }}>
                  Assigned Roles
                </span>
                <div style={{ display: 'flex', gap: '6px', flexWrap: 'wrap', marginTop: '4px' }}>
                  {user?.roles?.length ? (
                    user.roles.map((r) => (
                      <span
                        key={r}
                        className={`badge ${
                          r === 'DRIVER' ? 'badge-driver' : r === 'REVIEWER' ? 'badge-reviewer' : r === 'ADMINISTRATOR' ? 'badge-admin' : 'badge-customer'
                        }`}
                      >
                        {r}
                      </span>
                    ))
                  ) : (
                    <span style={{ color: 'var(--text-muted)', fontSize: '12px' }}>Không có role</span>
                  )}
                </div>
              </div>

              <div>
                <span style={{ color: 'var(--text-muted)', fontSize: '11px', textTransform: 'uppercase' }}>
                  Security Permissions (RBAC)
                </span>
                <div style={{ display: 'flex', gap: '4px', flexWrap: 'wrap', marginTop: '6px' }}>
                  {user?.permissions?.length ? (
                    user.permissions.map((p) => (
                      <span
                        key={p}
                        style={{
                          background: 'rgba(255, 255, 255, 0.05)',
                          padding: '2px 6px',
                          borderRadius: '4px',
                          fontSize: '11px',
                          fontFamily: 'var(--font-mono)',
                          color: 'var(--text-secondary)',
                        }}
                      >
                        {p}
                      </span>
                    ))
                  ) : (
                    <span style={{ color: 'var(--text-muted)', fontSize: '12px' }}>Không có permissions</span>
                  )}
                </div>
              </div>
            </div>
          )}
        </div>

        {/* REALTIME CARD A: Driver Offer UX (M07) */}
        {hasRole('DRIVER') && (
          <DriverOfferCard
            offer={activeOffer}
            assignmentStatus={assignmentStatus}
            secondsRemaining={secondsRemaining}
            isSubmitting={offerSubmitting}
            errorMessage={offerError}
            onAccept={acceptOffer}
            onReject={rejectOffer}
            onDismiss={clearOffer}
            onRefresh={refreshOffers}
          />
        )}

        {/* REALTIME CARD B: Customer & Driver Active Trip Tracker (M07) */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
          {/* Trip ID Selector Bar */}
          <div
            className="glass-panel"
            style={{
              padding: '14px 18px',
              display: 'flex',
              alignItems: 'center',
              gap: '10px',
            }}
          >
            <Car size={16} color="var(--primary)" />
            <input
              type="text"
              placeholder="Track Trip UUID (e.g. 11111111-1111-1111-1111-111111111111)..."
              value={inputTripId}
              onChange={(e) => setInputTripId(e.target.value.trim())}
              className="input-field font-mono"
              style={{ fontSize: '12px', flex: 1 }}
            />
            <button
              onClick={() => {
                if (inputTripId) {
                  setTrackedTripId(inputTripId);
                }
              }}
              className="btn btn-primary btn-sm"
              style={{ fontSize: '12px' }}
            >
              Track Trip
            </button>
            {trackedTripId && (
              <button
                onClick={() => {
                  setTrackedTripId('');
                  setInputTripId('');
                }}
                className="btn btn-secondary btn-sm"
                style={{ fontSize: '11px' }}
              >
                Clear
              </button>
            )}
          </div>

          <TripLiveTracker
            trip={trip}
            tripStatus={tripStatus}
            location={liveLocation}
            isStreamable={isStreamable}
            isLoadingSnapshot={isLoadingSnapshot}
            isStale={isStale}
            lastUpdated={lastUpdated}
            isDriver={hasRole('DRIVER')}
            onRefresh={() => {
              syncTrip();
              refreshSnapshot();
            }}
            onCancelTrip={(reason) => cancelTrip(reason)}
            onStartPickup={trackedTripId ? async () => {
              await tripService.startPickingUp(trackedTripId);
              syncTrip();
            } : undefined}
            onArrive={trackedTripId ? async () => {
              await tripService.confirmArrived(trackedTripId);
              syncTrip();
            } : undefined}
            onStartTrip={trackedTripId ? async () => {
              await tripService.startTrip(trackedTripId);
              syncTrip();
            } : undefined}
            onCompleteTrip={trackedTripId ? async () => {
              await tripService.completeTrip(trackedTripId);
              syncTrip();
            } : undefined}
          />
        </div>

        {/* CARD 2: Driver Onboarding & KYC */}
        <div className="glass-panel" style={{ padding: '24px' }}>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '16px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
              <div
                style={{
                  width: '36px',
                  height: '36px',
                  borderRadius: '8px',
                  background: 'rgba(16, 185, 129, 0.15)',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                }}
              >
                <FileCheck size={20} color="var(--success)" />
              </div>
              <h3 style={{ fontSize: '16px', fontWeight: 700 }}>Driver Application (KYC)</h3>
            </div>
            <button
              onClick={loadMyApplication}
              disabled={appLoading}
              className="btn btn-secondary btn-sm"
              title="Refresh /api/v1/drivers/applications/me"
              style={{ padding: '6px' }}
            >
              <RefreshCw size={13} className={appLoading ? 'animate-pulse' : ''} />
            </button>
          </div>

          {appMsg && (
            <div
              style={{
                padding: '8px 12px',
                borderRadius: '6px',
                fontSize: '12px',
                marginBottom: '12px',
                background: appMsg.error ? 'rgba(239, 68, 68, 0.15)' : 'rgba(16, 185, 129, 0.15)',
                color: appMsg.error ? '#f87171' : '#34d399',
              }}
            >
              {appMsg.text}
            </div>
          )}

          {appLoading ? (
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '30px', color: 'var(--text-muted)', gap: '8px' }}>
              <RefreshCw size={18} className="animate-pulse" />
              <span>Đang tải thông tin hồ sơ tài xế...</span>
            </div>
          ) : appError ? (
            <div
              style={{
                padding: '12px 14px',
                borderRadius: '8px',
                background: 'rgba(239, 68, 68, 0.1)',
                border: '1px solid rgba(239, 68, 68, 0.25)',
                color: '#f87171',
                fontSize: '13px',
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                  <AlertTriangle size={16} />
                  <span><strong>Lỗi máy chủ:</strong> {appError}</span>
                </div>
                <button onClick={loadMyApplication} className="btn btn-secondary btn-sm" style={{ padding: '4px 8px', fontSize: '11px' }}>
                  Thử lại
                </button>
              </div>
            </div>
          ) : application ? (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '12px', fontSize: '13px' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <span style={{ color: 'var(--text-muted)' }}>Application Status:</span>
                <span
                  className={`badge ${
                    application.status === 'APPROVED' ? 'badge-approved' : application.status === 'REJECTED' ? 'badge-rejected' : 'badge-pending'
                  }`}
                >
                  {application.status}
                </span>
              </div>

              <div>
                <span style={{ color: 'var(--text-muted)', fontSize: '11px', textTransform: 'uppercase' }}>
                  Full Name
                </span>
                <p style={{ fontWeight: 600 }}>{application.fullName}</p>
              </div>

              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '8px' }}>
                <div>
                  <span style={{ color: 'var(--text-muted)', fontSize: '11px', textTransform: 'uppercase' }}>
                    Driver License
                  </span>
                  <p style={{ fontFamily: 'var(--font-mono)' }}>{application.driverLicenseNumber}</p>
                </div>
                <div>
                  <span style={{ color: 'var(--text-muted)', fontSize: '11px', textTransform: 'uppercase' }}>
                    Vehicle Plate
                  </span>
                  <p style={{ fontFamily: 'var(--font-mono)' }}>{application.vehiclePlate}</p>
                </div>
              </div>

              {application.rejectionReason && (
                <div
                  style={{
                    padding: '8px 12px',
                    background: 'rgba(239, 68, 68, 0.1)',
                    border: '1px solid rgba(239, 68, 68, 0.2)',
                    borderRadius: '6px',
                    color: '#f87171',
                    fontSize: '12px',
                  }}
                >
                  <strong>Rejection Reason:</strong> {application.rejectionReason}
                </div>
              )}

              <div style={{ fontSize: '11px', color: 'var(--text-muted)' }}>
                Application ID: <code>{application.id}</code>
              </div>
            </div>
          ) : (appNotFound || (!application && !appError)) ? (
            <div>
              <p style={{ fontSize: '13px', color: 'var(--text-secondary)', marginBottom: '14px' }}>
                You have not submitted a driver onboarding application yet. Fill out the form below to apply.
              </p>

              <form onSubmit={handleApply} style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
                <input
                  type="text"
                  required
                  placeholder="Full Name (e.g. Nguyen Van A)"
                  value={appForm.fullName}
                  onChange={(e) => setAppForm({ ...appForm, fullName: e.target.value })}
                  className="input-field"
                />
                <input
                  type="text"
                  required
                  placeholder="Phone (e.g. 0912345678)"
                  value={appForm.phone}
                  onChange={(e) => setAppForm({ ...appForm, phone: e.target.value })}
                  className="input-field"
                />
                <input
                  type="text"
                  required
                  placeholder="Driver License Number (e.g. B2-998877)"
                  value={appForm.driverLicenseNumber}
                  onChange={(e) => setAppForm({ ...appForm, driverLicenseNumber: e.target.value })}
                  className="input-field"
                />
                <input
                  type="text"
                  required
                  placeholder="Vehicle Plate (e.g. 30A-12345)"
                  value={appForm.vehiclePlate}
                  onChange={(e) => setAppForm({ ...appForm, vehiclePlate: e.target.value })}
                  className="input-field"
                />
                <button
                  type="submit"
                  disabled={appSubmitting}
                  className="btn btn-primary btn-sm"
                  style={{ marginTop: '4px' }}
                >
                  <Send size={13} />
                  {appSubmitting ? 'Submitting...' : 'Submit Driver Application'}
                </button>
              </form>
            </div>
          ) : null}
        </div>

        {/* CARD 3: Driver Telemetry Controls (if DRIVER) */}
        {hasRole('DRIVER') && (
          <div className="glass-panel" style={{ padding: '24px' }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '16px' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
                <div
                  style={{
                    width: '36px',
                    height: '36px',
                    borderRadius: '8px',
                    background: 'rgba(16, 185, 129, 0.15)',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                  }}
                >
                  <Navigation size={20} color="var(--success)" />
                </div>
                <h3 style={{ fontSize: '16px', fontWeight: 700 }}>Driver Telemetry (GPS)</h3>
              </div>
              <button
                onClick={() => {
                  loadMyLocation();
                  loadMyEligibility();
                }}
                disabled={locLoading || eligibilityLoading}
                className="btn btn-secondary btn-sm"
                title="Refresh Location & Eligibility"
                style={{ padding: '6px' }}
              >
                <RefreshCw size={13} className={locLoading || eligibilityLoading ? 'animate-pulse' : ''} />
              </button>
            </div>

            {locMsg && (
              <div
                style={{
                  padding: '8px 12px',
                  borderRadius: '6px',
                  fontSize: '12px',
                  marginBottom: '12px',
                  background: locMsg.error ? 'rgba(239, 68, 68, 0.15)' : 'rgba(16, 185, 129, 0.15)',
                  color: locMsg.error ? '#f87171' : '#34d399',
                }}
              >
                {locMsg.text}
              </div>
            )}

            {/* Ineligible Explanation Notice */}
            {eligibility && !eligibility.eligible && (
              <div
                style={{
                  padding: '10px 14px',
                  borderRadius: 'var(--radius-md)',
                  background: 'rgba(245, 158, 11, 0.12)',
                  border: '1px solid rgba(245, 158, 11, 0.3)',
                  marginBottom: '14px',
                  fontSize: '12px',
                  color: '#fbbf24',
                }}
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: '6px', fontWeight: 700, marginBottom: '4px' }}>
                  <AlertTriangle size={14} />
                  <span>Chưa đủ điều kiện bật trực tuyến (Ineligible to Go Online)</span>
                </div>
                {eligibility.reasons && eligibility.reasons.length > 0 ? (
                  <ul style={{ margin: '4px 0 0 16px', padding: 0, color: 'var(--text-secondary)' }}>
                    {eligibility.reasons.map((r) => (
                      <li key={r}>
                        {r === 'PROFILE_NOT_FOUND' && 'Chưa có hồ sơ đối tác tài xế KYC (vui lòng nộp hồ sơ ở thẻ KYC bên cạnh)'}
                        {r === 'APPLICATION_NOT_APPROVED' && `Hồ sơ tài xế đang ở trạng thái ${application?.status || 'PENDING'} (chờ Reviewer xét duyệt).`}
                        {r === 'NO_ACTIVE_VEHICLE' && 'Chưa có phương tiện nào ở trạng thái kích hoạt (ACTIVE).'}
                        {r === 'DRIVER_SUSPENDED' && 'Tài xế đang bị tạm đình chỉ hoạt động.'}
                        {r === 'ACCOUNT_INACTIVE' && 'Tài khoản người dùng đang bị khóa.'}
                        {r === 'DRIVER_ROLE_MISSING' && 'Tài khoản chưa được cấp vai trò DRIVER.'}
                        {!['PROFILE_NOT_FOUND', 'APPLICATION_NOT_APPROVED', 'NO_ACTIVE_VEHICLE', 'DRIVER_SUSPENDED', 'ACCOUNT_INACTIVE', 'DRIVER_ROLE_MISSING'].includes(r) && r}
                      </li>
                    ))}
                  </ul>
                ) : (
                  <p style={{ margin: 0, color: 'var(--text-secondary)' }}>
                    Tài khoản cần có hồ sơ KYC được duyệt và phương tiện ACTIVE để bật trực tuyến.
                  </p>
                )}
              </div>
            )}

            <div style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
              {/* Status Header */}
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  padding: '10px 14px',
                  background: 'var(--bg-surface-elevated)',
                  borderRadius: 'var(--radius-md)',
                  border: '1px solid var(--border-subtle)',
                }}
              >
                <span style={{ fontSize: '13px', fontWeight: 600 }}>Active Status:</span>
                <span
                  className={`badge ${driverLoc?.status === 'ONLINE' ? 'badge-approved' : 'badge-rejected'}`}
                >
                  {driverLoc?.status || 'OFFLINE'}
                </span>
              </div>

              {/* Coordinates Inputs */}
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '10px' }}>
                <div>
                  <label style={{ fontSize: '11px', color: 'var(--text-muted)' }}>LONGITUDE</label>
                  <input
                    type="number"
                    step="0.0001"
                    value={coords.longitude}
                    onChange={(e) => setCoords({ ...coords, longitude: parseFloat(e.target.value) || 0 })}
                    className="input-field font-mono"
                    style={{ fontSize: '12px' }}
                  />
                </div>
                <div>
                  <label style={{ fontSize: '11px', color: 'var(--text-muted)' }}>LATITUDE</label>
                  <input
                    type="number"
                    step="0.0001"
                    value={coords.latitude}
                    onChange={(e) => setCoords({ ...coords, latitude: parseFloat(e.target.value) || 0 })}
                    className="input-field font-mono"
                    style={{ fontSize: '12px' }}
                  />
                </div>
              </div>

              {/* Preset Coordinates */}
              <div style={{ display: 'flex', gap: '6px', flexWrap: 'wrap' }}>
                <button
                  type="button"
                  onClick={() => setCoords({ longitude: 105.8542, latitude: 21.0285 })}
                  className="btn btn-secondary btn-sm"
                  style={{ fontSize: '10px', padding: '3px 8px' }}
                >
                  Hoàn Kiếm
                </button>
                <button
                  type="button"
                  onClick={() => setCoords({ longitude: 105.8342, latitude: 21.0278 })}
                  className="btn btn-secondary btn-sm"
                  style={{ fontSize: '10px', padding: '3px 8px' }}
                >
                  Ba Đình
                </button>
                <button
                  type="button"
                  onClick={() => setCoords({ longitude: 105.7891, latitude: 21.0362 })}
                  className="btn btn-secondary btn-sm"
                  style={{ fontSize: '10px', padding: '3px 8px' }}
                >
                  Cầu Giấy
                </button>
              </div>

              {/* Action Buttons */}
              <div style={{ display: 'flex', gap: '8px' }}>
                <button
                  onClick={handleGoOnline}
                  disabled={eligibility?.eligible !== true || driverLoc?.status === 'ONLINE' || locLoading}
                  className="btn btn-success btn-sm"
                  style={{
                    flex: 1,
                    opacity: eligibility?.eligible !== true ? 0.5 : 1,
                    cursor: eligibility?.eligible !== true ? 'not-allowed' : 'pointer',
                  }}
                  title={eligibility?.eligible !== true ? 'Chưa đủ điều kiện trực tuyến' : 'Bật trực tuyến'}
                >
                  Go Online
                </button>
                <button
                  onClick={handleUpdateLocation}
                  disabled={eligibility?.eligible !== true || locLoading}
                  className="btn btn-primary btn-sm"
                  style={{
                    flex: 1,
                    opacity: eligibility?.eligible !== true ? 0.5 : 1,
                    cursor: eligibility?.eligible !== true ? 'not-allowed' : 'pointer',
                  }}
                >
                  Update GPS
                </button>
                <button
                  onClick={handleGoOffline}
                  disabled={driverLoc?.status !== 'ONLINE' || locLoading}
                  className="btn btn-danger btn-sm"
                  style={{ flex: 1 }}
                >
                  Go Offline
                </button>
              </div>
            </div>
          </div>
        )}

        {/* CARD 4: Reviewer Portal (if DRIVER_REVIEW) */}
        {hasPermission('DRIVER_REVIEW') && (
          <div className="glass-panel" style={{ padding: '24px' }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '16px' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
                <div
                  style={{
                    width: '36px',
                    height: '36px',
                    borderRadius: '8px',
                    background: 'rgba(139, 92, 246, 0.15)',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                  }}
                >
                  <Shield size={20} color="var(--reviewer)" />
                </div>
                <h3 style={{ fontSize: '16px', fontWeight: 700 }}>Reviewer Portal ({pendingApps.length})</h3>
              </div>
              <button
                onClick={loadPendingApplications}
                disabled={reviewerLoading}
                className="btn btn-secondary btn-sm"
                title="Refresh PENDING applications"
                style={{ padding: '6px' }}
              >
                <RefreshCw size={13} className={reviewerLoading ? 'animate-pulse' : ''} />
              </button>
            </div>

            {reviewerMsg && (
              <div
                style={{
                  padding: '8px 12px',
                  borderRadius: '6px',
                  fontSize: '12px',
                  marginBottom: '12px',
                  background: reviewerMsg.error ? 'rgba(239, 68, 68, 0.15)' : 'rgba(16, 185, 129, 0.15)',
                  color: reviewerMsg.error ? '#f87171' : '#34d399',
                }}
              >
                {reviewerMsg.text}
              </div>
            )}

            {pendingApps.length === 0 ? (
              <div style={{ textAlign: 'center', padding: '30px', color: 'var(--text-muted)', fontSize: '13px' }}>
                No applications currently waiting for review.
              </div>
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: '10px', maxHeight: '280px', overflowY: 'auto' }}>
                {pendingApps.map((a) => (
                  <div
                    key={a.id}
                    style={{
                      background: 'var(--bg-surface-elevated)',
                      padding: '12px',
                      borderRadius: 'var(--radius-md)',
                      border: '1px solid var(--border-subtle)',
                    }}
                  >
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
                      <div>
                        <span style={{ fontWeight: 700, fontSize: '13px' }}>{a.fullName}</span>
                        <p style={{ fontSize: '11px', color: 'var(--text-muted)', fontFamily: 'var(--font-mono)' }}>
                          {a.phone} • {a.vehiclePlate}
                        </p>
                      </div>
                      <span className="badge badge-pending" style={{ fontSize: '9px' }}>
                        PENDING
                      </span>
                    </div>

                    {rejectingId === a.id ? (
                      <div style={{ marginTop: '10px' }}>
                        <input
                          type="text"
                          value={rejectReason}
                          onChange={(e) => setRejectReason(e.target.value)}
                          placeholder="Rejection reason..."
                          className="input-field"
                          style={{ fontSize: '12px', marginBottom: '6px' }}
                        />
                        <div style={{ display: 'flex', gap: '6px' }}>
                          <button
                            onClick={() => handleReject(a.id)}
                            className="btn btn-danger btn-sm"
                            style={{ flex: 1 }}
                          >
                            Confirm Reject
                          </button>
                          <button
                            onClick={() => setRejectingId(null)}
                            className="btn btn-secondary btn-sm"
                          >
                            Cancel
                          </button>
                        </div>
                      </div>
                    ) : (
                      <div style={{ display: 'flex', gap: '8px', marginTop: '10px' }}>
                        <button
                          onClick={() => handleApprove(a.id)}
                          className="btn btn-success btn-sm"
                          style={{ flex: 1 }}
                        >
                          Approve
                        </button>
                        <button
                          onClick={() => setRejectingId(a.id)}
                          className="btn btn-danger btn-sm"
                          style={{ flex: 1 }}
                        >
                          Reject
                        </button>
                      </div>
                    )}
                  </div>
                ))}
              </div>
            )}
          </div>
        )}

        {/* CARD 5: Administrator Radar (if ADMINISTRATOR) */}
        {hasRole('ADMINISTRATOR') && (
          <div className="glass-panel" style={{ padding: '24px' }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '16px' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
                <div
                  style={{
                    width: '36px',
                    height: '36px',
                    borderRadius: '8px',
                    background: 'rgba(236, 72, 153, 0.15)',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                  }}
                >
                  <Radio size={20} color="var(--admin)" />
                </div>
                <h3 style={{ fontSize: '16px', fontWeight: 700 }}>Active Drivers Radar</h3>
              </div>
              <button
                onClick={handleRadarScan}
                disabled={radarLoading}
                className="btn btn-secondary btn-sm"
                title="Scan Redis GEO"
                style={{ padding: '6px' }}
              >
                <RefreshCw size={13} className={radarLoading ? 'animate-pulse' : ''} />
              </button>
            </div>

            <div style={{ display: 'flex', gap: '8px', marginBottom: '12px' }}>
              <input
                type="number"
                placeholder="Radius (km)"
                value={radarParams.radiusKm}
                onChange={(e) => setRadarParams({ ...radarParams, radiusKm: parseFloat(e.target.value) || 5 })}
                className="input-field font-mono"
                style={{ fontSize: '12px', width: '110px' }}
              />
              <button onClick={handleRadarScan} disabled={radarLoading} className="btn btn-primary btn-sm" style={{ flex: 1 }}>
                <Search size={13} /> Scan Radius
              </button>
            </div>

            {nearbyDrivers.length === 0 ? (
              <div style={{ textAlign: 'center', padding: '24px', color: 'var(--text-muted)', fontSize: '12px' }}>
                No online drivers found within {radarParams.radiusKm}km of selected center.
              </div>
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: '6px', maxHeight: '200px', overflowY: 'auto' }}>
                {nearbyDrivers.map((d) => (
                  <div
                    key={d.driverId}
                    style={{
                      background: 'var(--bg-surface-elevated)',
                      padding: '8px 12px',
                      borderRadius: 'var(--radius-sm)',
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'space-between',
                      fontSize: '12px',
                    }}
                  >
                    <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                      <MapPin size={13} color="var(--success)" />
                      <span style={{ fontFamily: 'var(--font-mono)' }}>{d.driverId.slice(0, 8)}...</span>
                    </div>
                    <span style={{ color: 'var(--text-secondary)' }}>{d.distanceKm.toFixed(2)} km away</span>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
};
