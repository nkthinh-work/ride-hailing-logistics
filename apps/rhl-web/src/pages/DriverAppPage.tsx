import React, { useState, useEffect, useCallback } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { locationService } from '../services/locationService';
import { driverService } from '../services/driverService';
import { tripService } from '../services/tripService';
import { useDriverOffers } from '../hooks/useDriverOffers';
import { useTripRealtime } from '../hooks/useTripRealtime';
import { useTripLocation } from '../hooks/useTripLocation';
import { DriverOfferCard } from '../components/realtime/DriverOfferCard';
import { TripLiveTracker } from '../components/realtime/TripLiveTracker';
import { DriverCashCollectionCard } from '../components/payment/DriverCashCollectionCard';
import type {
  DriverLocationResponse,
  DriverApplicationResponse,
  DriverEligibilityResponse,
  ApiError,
} from '../types/index';
import {
  Car,
  Power,
  Navigation,
  RefreshCw,
  Send,
  Radio,
  ShieldAlert,
  ArrowUp,
  ArrowDown,
  ArrowLeft,
  ArrowRight,
  AlertTriangle,
} from 'lucide-react';

export const DriverAppPage: React.FC = () => {
  const { user, hasRole } = useAuth();

  // Driver Telemetry & Location State
  const [driverLoc, setDriverLoc] = useState<DriverLocationResponse | null>(null);
  const [locLoading, setLocLoading] = useState(false);
  const [coords, setCoords] = useState({ longitude: 105.8542, latitude: 21.0285 });
  const [locMsg, setLocMsg] = useState<{ text: string; error?: boolean } | null>(null);

  // Driver Application & Eligibility Info
  const [application, setApplication] = useState<DriverApplicationResponse | null>(null);
  const [eligibility, setEligibility] = useState<DriverEligibilityResponse | null>(null);

  // Realtime Driver Offers (M07)
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

  // Active Trip State
  const [activeTripId, setActiveTripId] = useState<string>('');

  // Automatically track trip when driver assignment is confirmed
  useEffect(() => {
    if (activeOffer && assignmentStatus === 'ASSIGNMENT_CONFIRMED' && activeOffer.tripId) {
      setActiveTripId(activeOffer.tripId);
    }
  }, [activeOffer, assignmentStatus]);

  // Trip realtime & GPS hooks
  const { trip, tripStatus, syncTrip } = useTripRealtime(activeTripId || null);
  const {
    location: liveLocation,
    isStreamable,
    isLoadingSnapshot,
    isStale,
    lastUpdated,
    refreshSnapshot,
  } = useTripLocation(activeTripId || null, tripStatus);

  // Load Driver Profile, Location & Eligibility
  const loadDriverStatus = useCallback(async () => {
    setLocLoading(true);
    try {
      const [app, loc, elig] = await Promise.allSettled([
        driverService.getMyApplication(),
        locationService.getMyLocation(),
        driverService.getMyEligibility(),
      ]);
      if (app.status === 'fulfilled') setApplication(app.value);
      if (loc.status === 'fulfilled' && loc.value) {
        setDriverLoc(loc.value);
        if (loc.value.longitude && loc.value.latitude) {
          setCoords({ longitude: loc.value.longitude, latitude: loc.value.latitude });
        }
      }
      if (elig.status === 'fulfilled') {
        setEligibility(elig.value);
      }
    } catch {
      // ignore
    } finally {
      setLocLoading(false);
    }
  }, []);

  useEffect(() => {
    loadDriverStatus();
  }, [loadDriverStatus]);

  // Toggle Online
  const handleGoOnline = async () => {
    setLocMsg(null);
    try {
      const res = await locationService.turnOnline(coords.longitude, coords.latitude);
      setDriverLoc(res);
      setLocMsg({ text: 'Tài xế đã BẬT ONLINE. Đang lắng nghe cuốc xe từ Dispatch...' });
    } catch (err: unknown) {
      const apiErr = err as ApiError;
      setLocMsg({ text: apiErr.message || 'Không thể chuyển ONLINE', error: true });
    }
  };

  // Toggle Offline
  const handleGoOffline = async () => {
    setLocMsg(null);
    try {
      const res = await locationService.turnOffline();
      setDriverLoc(res);
      setLocMsg({ text: 'Tài xế đã chuyển OFFLINE. Đã xóa heartbeat Redis GEO.' });
    } catch (err: unknown) {
      const apiErr = err as ApiError;
      setLocMsg({ text: apiErr.message || 'Không thể chuyển OFFLINE', error: true });
    }
  };

  // Move / Transmit GPS Telemetry
  const handleTransmitGps = async (newLng: number, newLat: number) => {
    setLocMsg(null);
    const updated = {
      longitude: parseFloat(newLng.toFixed(6)),
      latitude: parseFloat(newLat.toFixed(6)),
    };
    setCoords(updated);
    try {
      const res = await locationService.updateLocation(updated.longitude, updated.latitude, driverLoc?.sessionId);
      setDriverLoc(res);
      setLocMsg({ text: `Đã phát GPS: [${updated.longitude}, ${updated.latitude}]` });
    } catch (err: unknown) {
      const apiErr = err as ApiError;
      setLocMsg({ text: apiErr.message || 'Phát GPS thất bại', error: true });
    }
  };

  const isOnline = driverLoc?.status === 'ONLINE';
  const isDriverEligible = eligibility ? eligibility.eligible : application?.status === 'APPROVED';

  return (
    <div style={{ maxWidth: '1280px', margin: '0 auto', padding: '32px 20px' }}>
      {/* Page Header */}
      <div style={{ marginBottom: '24px', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '4px' }}>
            <span
              style={{
                fontSize: '11px',
                fontWeight: 700,
                color: 'var(--success)',
                background: 'rgba(16, 185, 129, 0.1)',
                padding: '2px 8px',
                borderRadius: '4px',
                border: '1px solid rgba(16, 185, 129, 0.25)',
              }}
            >
              DRIVER COCKPIT
            </span>
            <span style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
              M07 Realtime Driver Application
            </span>
          </div>
          <h1 style={{ fontSize: '26px', fontWeight: 800, letterSpacing: '-0.5px' }}>
            Ứng dụng Tài xế & Tiếp nhận cuốc xe
          </h1>
          <p style={{ color: 'var(--text-secondary)', fontSize: '14px', marginTop: '4px' }}>
            Bật trạng thái trực tuyến, nhận DriverOffer thời gian thực, chấp nhận cuốc và thực hiện quy trình chuyến đi.
          </p>
        </div>

        {/* Refresh button */}
        <button
          onClick={loadDriverStatus}
          disabled={locLoading}
          className="btn btn-secondary btn-sm"
          style={{ display: 'flex', alignItems: 'center', gap: '6px' }}
        >
          <RefreshCw size={13} className={locLoading ? 'animate-pulse' : ''} />
          <span>Làm mới trạng thái</span>
        </button>
      </div>

      {!hasRole('DRIVER') && (
        <div
          style={{
            padding: '12px 16px',
            borderRadius: '8px',
            background: 'rgba(245, 158, 11, 0.15)',
            border: '1px solid rgba(245, 158, 11, 0.3)',
            color: '#fbbf24',
            fontSize: '13px',
            marginBottom: '20px',
            display: 'flex',
            alignItems: 'center',
            gap: '10px',
          }}
        >
          <ShieldAlert size={18} />
          <span>
            Tài khoản hiện tại ({user?.email}) chưa có quyền <strong>DRIVER</strong>. Hãy bấm <strong>Ctrl + `</strong> mở Dev Console để chuyển sang tài khoản <code>DRIVER</code> (hoặc nộp hồ sơ KYC tại trang Dashboard).
          </span>
        </div>
      )}

      {/* Realtime Notification Banner if Offer arrives */}
      {activeOffer && (
        <div style={{ marginBottom: '24px' }}>
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
        </div>
      )}

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(360px, 1fr))', gap: '24px' }}>
        {/* CARD 1: Online Status & Vehicle Cockpit */}
        <div className="glass-panel" style={{ padding: '24px' }}>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '18px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
              <div
                style={{
                  width: '36px',
                  height: '36px',
                  borderRadius: '8px',
                  background: isOnline ? 'rgba(16, 185, 129, 0.15)' : 'rgba(239, 68, 68, 0.15)',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                }}
              >
                <Power size={20} color={isOnline ? 'var(--success)' : 'var(--danger)'} />
              </div>
              <div>
                <h3 style={{ fontSize: '16px', fontWeight: 700 }}>Trạng thái hoạt động</h3>
                <p style={{ fontSize: '11px', color: 'var(--text-muted)' }}>
                  Redis GEO Indexing & Live Heartbeat
                </p>
              </div>
            </div>

            <span
              className={`badge ${isOnline ? 'badge-success' : 'badge-pending'}`}
              style={{
                fontSize: '11px',
                padding: '4px 10px',
                display: 'flex',
                alignItems: 'center',
                gap: '6px',
              }}
            >
              <Radio size={12} className={isOnline ? 'animate-pulse' : ''} />
              {isOnline ? 'ONLINE' : 'OFFLINE'}
            </span>
          </div>

          {locMsg && (
            <div
              style={{
                padding: '8px 12px',
                borderRadius: '6px',
                fontSize: '12px',
                marginBottom: '16px',
                background: locMsg.error ? 'rgba(239, 68, 68, 0.15)' : 'rgba(16, 185, 129, 0.15)',
                color: locMsg.error ? '#f87171' : '#34d399',
              }}
            >
              {locMsg.text}
            </div>
          )}

          {/* KYC Incomplete / Ineligible Warning Banner */}
          {!isDriverEligible && (
            <div
              style={{
                padding: '12px 14px',
                borderRadius: 'var(--radius-md)',
                background: 'rgba(245, 158, 11, 0.12)',
                border: '1px solid rgba(245, 158, 11, 0.3)',
                marginBottom: '16px',
                fontSize: '12px',
                color: '#fbbf24',
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px', fontWeight: 700, marginBottom: '4px' }}>
                <AlertTriangle size={15} />
                <span>Chưa đủ điều kiện bật trực tuyến (Ineligible to Go Online)</span>
              </div>
              <div style={{ margin: 0, color: 'var(--text-secondary)' }}>
                {eligibility?.reasons && eligibility.reasons.length > 0 ? (
                  <ul style={{ margin: '4px 0 0 16px', padding: 0 }}>
                    {eligibility.reasons.map((r) => (
                      <li key={r}>
                        {r === 'PROFILE_NOT_FOUND' && 'Chưa có hồ sơ đối tác tài xế KYC (vui lòng nộp hồ sơ KYC để đủ điều kiện).'}
                        {r === 'APPLICATION_NOT_APPROVED' && `Hồ sơ tài xế đang ở trạng thái ${application?.status || 'PENDING'} (chờ xét duyệt).`}
                        {r === 'NO_ACTIVE_VEHICLE' && 'Chưa có phương tiện nào ở trạng thái kích hoạt (ACTIVE).'}
                        {r === 'DRIVER_SUSPENDED' && 'Tài xế đang bị tạm đình chỉ hoạt động.'}
                        {r === 'ACCOUNT_INACTIVE' && 'Tài khoản người dùng đang bị khóa.'}
                        {r === 'DRIVER_ROLE_MISSING' && 'Tài khoản chưa được cấp vai trò DRIVER.'}
                        {!['PROFILE_NOT_FOUND', 'APPLICATION_NOT_APPROVED', 'NO_ACTIVE_VEHICLE', 'DRIVER_SUSPENDED', 'ACCOUNT_INACTIVE', 'DRIVER_ROLE_MISSING'].includes(r) && r}
                      </li>
                    ))}
                  </ul>
                ) : (
                  <p style={{ margin: 0 }}>
                    {application
                      ? `Hồ sơ tài xế đang ở trạng thái ${application.status}. Bạn chỉ có thể bật trực tuyến sau khi hồ sơ được phê duyệt.`
                      : 'Tài khoản chưa có hồ sơ tài xế được phê duyệt. Vui lòng nộp hồ sơ KYC để đủ điều kiện bật trực tuyến nhận cuốc.'}
                  </p>
                )}
              </div>
              <div style={{ marginTop: '10px' }}>
                <Link
                  to="/"
                  className="btn btn-secondary btn-sm"
                  style={{ display: 'inline-flex', alignItems: 'center', gap: '6px', fontSize: '11px', textDecoration: 'none' }}
                >
                  <span>Đi đến nộp / kiểm tra hồ sơ KYC</span>
                  <ArrowRight size={12} />
                </Link>
              </div>
            </div>
          )}

          {/* Toggle Online/Offline Buttons */}
          <div style={{ display: 'flex', gap: '10px', marginBottom: '20px' }}>
            <button
              onClick={handleGoOnline}
              disabled={!isDriverEligible || isOnline || locLoading}
              className="btn btn-success"
              style={{
                flex: 1,
                padding: '10px',
                fontWeight: 600,
                opacity: !isDriverEligible ? 0.5 : 1,
                cursor: !isDriverEligible ? 'not-allowed' : 'pointer',
              }}
              title={!isDriverEligible ? 'Chưa đủ điều kiện trực tuyến (KYC hoặc phương tiện chưa duyệt)' : 'Bật trực tuyến'}
            >
              <Power size={14} /> Bật trực tuyến (ONLINE)
            </button>
            <button
              onClick={handleGoOffline}
              disabled={!isOnline || locLoading}
              className="btn btn-danger"
              style={{ flex: 1, padding: '10px', fontWeight: 600 }}
            >
              <Power size={14} /> Nghỉ ca (OFFLINE)
            </button>
          </div>

          {/* Driver & Vehicle Summary */}
          <div style={{ background: 'var(--bg-surface-elevated)', padding: '14px', borderRadius: 'var(--radius-md)', fontSize: '12px' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '6px' }}>
              <span style={{ color: 'var(--text-muted)' }}>Tài xế:</span>
              <strong>{application?.fullName || user?.email || 'Chưa cập nhật'}</strong>
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '6px' }}>
              <span style={{ color: 'var(--text-muted)' }}>Biển số xe:</span>
              <span style={{ fontFamily: 'var(--font-mono)', fontWeight: 600 }}>
                {application?.vehiclePlate || 'Chưa đăng ký'}
              </span>
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between' }}>
              <span style={{ color: 'var(--text-muted)' }}>Giấy phép lái xe:</span>
              <span style={{ fontFamily: 'var(--font-mono)' }}>
                {application?.driverLicenseNumber || 'Chưa cập nhật'}
              </span>
            </div>
          </div>
        </div>

        {/* CARD 2: Interactive GPS Transmitter / Movement Simulator */}
        <div className="glass-panel" style={{ padding: '24px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '16px' }}>
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
              <Navigation size={20} color="var(--primary)" />
            </div>
            <div>
              <h3 style={{ fontSize: '16px', fontWeight: 700 }}>Mô phỏng phát GPS xe di chuyển</h3>
              <p style={{ fontSize: '11px', color: 'var(--text-muted)' }}>
                Bắn tọa độ thời gian thực lên Location Service & Realtime Topic
              </p>
            </div>
          </div>

          <div style={{ display: 'flex', gap: '10px', marginBottom: '14px' }}>
            <div style={{ flex: 1 }}>
              <label style={{ fontSize: '11px', color: 'var(--text-muted)', display: 'block', marginBottom: '3px' }}>
                Kinh độ (Longitude)
              </label>
              <input
                type="number"
                step="0.0001"
                value={coords.longitude}
                onChange={(e) => setCoords({ ...coords, longitude: parseFloat(e.target.value) || 0 })}
                className="input-field font-mono"
                style={{ fontSize: '12px' }}
              />
            </div>
            <div style={{ flex: 1 }}>
              <label style={{ fontSize: '11px', color: 'var(--text-muted)', display: 'block', marginBottom: '3px' }}>
                Vĩ độ (Latitude)
              </label>
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

          {/* D-Pad / Step Simulator */}
          <div style={{ marginBottom: '16px' }}>
            <span style={{ fontSize: '11px', color: 'var(--text-muted)', display: 'block', marginBottom: '6px' }}>
              Di chuyển xe (Mô phỏng lái xe):
            </span>
            <div style={{ display: 'flex', justifyContent: 'center', gap: '6px', marginBottom: '6px' }}>
              <button
                onClick={() => handleTransmitGps(coords.longitude, coords.latitude + 0.0015)}
                className="btn btn-secondary btn-sm"
                title="Đi về phía Bắc (+Lat)"
              >
                <ArrowUp size={14} /> Bắc (+Lat)
              </button>
            </div>
            <div style={{ display: 'flex', justifyContent: 'center', gap: '8px' }}>
              <button
                onClick={() => handleTransmitGps(coords.longitude - 0.0015, coords.latitude)}
                className="btn btn-secondary btn-sm"
                title="Đi về phía Tây (-Lng)"
              >
                <ArrowLeft size={14} /> Tây (-Lng)
              </button>
              <button
                onClick={() => handleTransmitGps(coords.longitude, coords.latitude - 0.0015)}
                className="btn btn-secondary btn-sm"
                title="Đi về phía Nam (-Lat)"
              >
                <ArrowDown size={14} /> Nam (-Lat)
              </button>
              <button
                onClick={() => handleTransmitGps(coords.longitude + 0.0015, coords.latitude)}
                className="btn btn-secondary btn-sm"
                title="Đi về phía Đông (+Lng)"
              >
                <ArrowRight size={14} /> Đông (+Lng)
              </button>
            </div>
          </div>

          <button
            onClick={() => handleTransmitGps(coords.longitude, coords.latitude)}
            className="btn btn-primary btn-sm"
            style={{ width: '100%' }}
          >
            <Send size={13} /> Phát tọa độ hiện tại ngay lập tức
          </button>
        </div>
      </div>

      {/* Active Trip Execution Cockpit */}
      {activeTripId && (
        <div style={{ marginTop: '28px' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '14px' }}>
            <h2 style={{ fontSize: '17px', fontWeight: 700, display: 'flex', alignItems: 'center', gap: '8px' }}>
              <Car size={20} color="var(--primary)" />
              <span>Chuyến xe đang phục vụ: </span>
              <span style={{ fontFamily: 'var(--font-mono)', fontSize: '14px', color: 'var(--primary)' }}>
                {activeTripId}
              </span>
            </h2>
            <button
              onClick={() => setActiveTripId('')}
              className="btn btn-secondary btn-sm"
              style={{ fontSize: '11px' }}
            >
              Bỏ theo dõi
            </button>
          </div>

          <TripLiveTracker
            trip={trip}
            tripStatus={tripStatus}
            location={liveLocation}
            isStreamable={isStreamable}
            isLoadingSnapshot={isLoadingSnapshot}
            isStale={isStale}
            lastUpdated={lastUpdated}
            isDriver={true}
            onRefresh={() => {
              syncTrip();
              refreshSnapshot();
            }}
            onStartPickup={async () => {
              await tripService.startPickingUp(activeTripId);
              syncTrip();
            }}
            onArrive={async () => {
              await tripService.confirmArrived(activeTripId);
              syncTrip();
            }}
            onStartTrip={async () => {
              await tripService.startTrip(activeTripId);
              syncTrip();
            }}
            onCompleteTrip={async () => {
              await tripService.completeTrip(activeTripId);
              syncTrip();
            }}
          />

          {/* Cash Collection Section when trip is completed */}
          {tripStatus === 'COMPLETED' && (
            <DriverCashCollectionCard
              tripId={activeTripId}
              trip={trip}
              onConfirmed={() => syncTrip()}
            />
          )}
        </div>
      )}
    </div>
  );
};
