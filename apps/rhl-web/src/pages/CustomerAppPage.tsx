import React, { useState, useEffect } from 'react';
import { useAuth } from '../context/AuthContext';
import { fareService } from '../services/fareService';
import { tripService } from '../services/tripService';
import { useTripRealtime } from '../hooks/useTripRealtime';
import { useTripLocation } from '../hooks/useTripLocation';
import { TripLiveTracker } from '../components/realtime/TripLiveTracker';
import { CustomerPaymentCard } from '../components/payment/CustomerPaymentCard';
import { PaymentHistoryModal } from '../components/payment/PaymentHistoryModal';
import type {
  FareQuoteResponse,
  ServiceType,
  VehicleType,
  ApiError,
} from '../types/index';
import {
  Car,
  MapPin,
  Clock,
  Navigation,
  AlertTriangle,
  Sparkles,
  ArrowRight,
  ShieldAlert,
  Banknote,
} from 'lucide-react';

interface PresetRoute {
  label: string;
  pickupAddress: string;
  pickupLat: number;
  pickupLng: number;
  dropoffAddress: string;
  dropoffLat: number;
  dropoffLng: number;
}

const PRESET_ROUTES: PresetRoute[] = [
  {
    label: 'Hồ Gươm ➔ Hồ Tây',
    pickupAddress: 'Hồ Hoàn Kiếm, Tràng Tiền, Hoàn Kiếm, Hà Nội',
    pickupLat: 21.0285,
    pickupLng: 105.8542,
    dropoffAddress: 'Chùa Trấn Quốc, Đường Thanh Niên, Tây Hồ, Hà Nội',
    dropoffLat: 21.0483,
    dropoffLng: 105.8366,
  },
  {
    label: 'Bách Khoa ➔ Keangnam Landmark 72',
    pickupAddress: 'Đại học Bách Khoa, 1 Đại Cồ Việt, Hai Bà Trưng, Hà Nội',
    pickupLat: 21.0056,
    pickupLng: 105.8433,
    dropoffAddress: 'Keangnam Landmark 72, Phạm Hùng, Nam Từ Liêm, Hà Nội',
    dropoffLat: 21.0173,
    dropoffLng: 105.7838,
  },
  {
    label: 'Hồ Gươm ➔ Sân bay Nội Bài',
    pickupAddress: 'Nhà hát Lớn Hà Nội, 1 Tràng Tiền, Hoàn Kiếm, Hà Nội',
    pickupLat: 21.0245,
    pickupLng: 105.8576,
    dropoffAddress: 'Sân bay Quốc tế Nội Bài, Sóc Sơn, Hà Nội',
    dropoffLat: 21.2212,
    dropoffLng: 105.8072,
  },
];

export const CustomerAppPage: React.FC = () => {
  const { user, hasRole } = useAuth();

  // Booking Form State
  const [selectedPreset, setSelectedPreset] = useState<number>(0);
  const [serviceType, setServiceType] = useState<ServiceType>('RIDE');
  const [vehicleType, setVehicleType] = useState<VehicleType>('CAR_4_SEAT');
  const [pickupAddress, setPickupAddress] = useState(PRESET_ROUTES[0].pickupAddress);
  const [pickupLat, setPickupLat] = useState(PRESET_ROUTES[0].pickupLat);
  const [pickupLng, setPickupLng] = useState(PRESET_ROUTES[0].pickupLng);
  const [dropoffAddress, setDropoffAddress] = useState(PRESET_ROUTES[0].dropoffAddress);
  const [dropoffLat, setDropoffLat] = useState(PRESET_ROUTES[0].dropoffLat);
  const [dropoffLng, setDropoffLng] = useState(PRESET_ROUTES[0].dropoffLng);

  // Quote State
  const [quote, setQuote] = useState<FareQuoteResponse | null>(null);
  const [isQuoting, setIsQuoting] = useState(false);
  const [quoteError, setQuoteError] = useState<string | null>(null);
  const [quoteTimeLeft, setQuoteTimeLeft] = useState<number | null>(null);

  // Active Trip State
  const [activeTripId, setActiveTripId] = useState<string>('');
  const [manualTripInput, setManualTripInput] = useState<string>('');
  const [isBooking, setIsBooking] = useState(false);
  const [bookingError, setBookingError] = useState<string | null>(null);
  const [paymentHistoryOpen, setPaymentHistoryOpen] = useState(false);

  // Realtime hooks
  const { trip, tripStatus, syncTrip, cancelTrip } = useTripRealtime(activeTripId || null);
  const {
    location: liveLocation,
    isStreamable,
    isLoadingSnapshot,
    isStale,
    lastUpdated,
    refreshSnapshot,
  } = useTripLocation(activeTripId || null, tripStatus);

  // Handle Preset Change
  const handleSelectPreset = (idx: number) => {
    setSelectedPreset(idx);
    const p = PRESET_ROUTES[idx];
    setPickupAddress(p.pickupAddress);
    setPickupLat(p.pickupLat);
    setPickupLng(p.pickupLng);
    setDropoffAddress(p.dropoffAddress);
    setDropoffLat(p.dropoffLat);
    setDropoffLng(p.dropoffLng);
    setQuote(null);
    setQuoteError(null);
  };

  // Quote Expiry Timer
  useEffect(() => {
    if (!quote?.expiresAt) {
      setQuoteTimeLeft(null);
      return;
    }
    const updateExpiry = () => {
      const exp = new Date(quote.expiresAt).getTime();
      const diff = Math.max(0, Math.floor((exp - Date.now()) / 1000));
      setQuoteTimeLeft(diff);
    };
    updateExpiry();
    const interval = setInterval(updateExpiry, 1000);
    return () => clearInterval(interval);
  }, [quote]);

  // Request Fare Quote
  const handleGetQuote = async () => {
    setIsQuoting(true);
    setQuoteError(null);
    setBookingError(null);
    try {
      const q = await fareService.createQuote({
        serviceType,
        vehicleType,
        pickupLatitude: pickupLat,
        pickupLongitude: pickupLng,
        pickupAddress,
        dropoffLatitude: dropoffLat,
        dropoffLongitude: dropoffLng,
      });
      setQuote(q);
    } catch (err: unknown) {
      const apiErr = err as ApiError;
      setQuoteError(apiErr.message || 'Không thể tạo báo giá. Vui lòng kiểm tra lại dịch vụ Pricing.');
      setQuote(null);
    } finally {
      setIsQuoting(false);
    }
  };

  // Confirm Trip Booking
  const handleConfirmBooking = async () => {
    if (!quote) return;
    setIsBooking(true);
    setBookingError(null);
    try {
      const idempotencyKey = 'rhl-book-' + crypto.randomUUID();
      const res = await tripService.bookTrip({
        quoteId: quote.quoteId,
        idempotencyKey,
        serviceType,
        vehicleType,
        customerConfirmedSurge: true,
        pickupLatitude: pickupLat,
        pickupLongitude: pickupLng,
        dropoffLatitude: dropoffLat,
        dropoffLongitude: dropoffLng,
      });
      setActiveTripId(res.tripId);
      setQuote(null);
    } catch (err: unknown) {
      const apiErr = err as ApiError;
      setBookingError(apiErr.message || 'Đặt chuyến thất bại. Vui lòng thử lại.');
    } finally {
      setIsBooking(false);
    }
  };

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
                color: 'var(--primary)',
                background: 'rgba(59, 130, 246, 0.1)',
                padding: '2px 8px',
                borderRadius: '4px',
                border: '1px solid rgba(59, 130, 246, 0.25)',
              }}
            >
              CUSTOMER EXPERIENCE
            </span>
            <span style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
              M07 Realtime Ride-Hailing Application
            </span>
          </div>
          <h1 style={{ fontSize: '26px', fontWeight: 800, letterSpacing: '-0.5px' }}>
            Khách hàng đặt xe & Theo dõi trực tiếp
          </h1>
          <p style={{ color: 'var(--text-secondary)', fontSize: '14px', marginTop: '4px' }}>
            Trải nghiệm gọi xe thực tế: Nhận báo giá minh bạch, tìm tài xế qua Dispatch matching và theo dõi vị trí GPS thời gian thực.
          </p>
        </div>

        {/* Manual Trip Tracker Input */}
        <div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
          <input
            type="text"
            placeholder="Tra cứu mã chuyến đi (Trip UUID)..."
            value={manualTripInput}
            onChange={(e) => setManualTripInput(e.target.value.trim())}
            className="input-field font-mono"
            style={{ fontSize: '12px', width: '280px' }}
          />
          <button
            onClick={() => {
              if (manualTripInput) {
                setActiveTripId(manualTripInput);
              }
            }}
            className="btn btn-secondary btn-sm"
          >
            Tra cứu
          </button>
          <button
            type="button"
            onClick={() => setPaymentHistoryOpen(true)}
            className="btn btn-secondary btn-sm"
            style={{ display: 'flex', alignItems: 'center', gap: '6px' }}
          >
            <Banknote size={14} />
            <span>Lịch sử thanh toán</span>
          </button>
        </div>
      </div>

      {!hasRole('CUSTOMER') && (
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
            Tài khoản hiện tại ({user?.email}) chưa có quyền <strong>CUSTOMER</strong>. Vui lòng bấm <strong>Ctrl + `</strong> mở Dev Console để chuyển sang tài khoản <code>CUSTOMER</code> phục vụ việc đặt chuyến.
          </span>
        </div>
      )}

      <div style={{ display: 'grid', gridTemplateColumns: activeTripId ? '1fr 1.2fr' : '1fr', gap: '24px' }}>
        {/* LEFT COLUMN: Booking Form & Fare Quote */}
        <div className="glass-panel" style={{ padding: '24px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '20px' }}>
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
              <h2 style={{ fontSize: '17px', fontWeight: 700 }}>Đặt xe chuyến mới</h2>
              <p style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
                Tạo báo giá từ Pricing Service và đặt cuốc tới Trip Service
              </p>
            </div>
          </div>

          {/* Preset Routes Carousel */}
          <div style={{ marginBottom: '16px' }}>
            <label style={{ fontSize: '12px', fontWeight: 600, color: 'var(--text-secondary)', display: 'block', marginBottom: '6px' }}>
              Tuyến đường mẫu gợi ý (Hà Nội Demo)
            </label>
            <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap' }}>
              {PRESET_ROUTES.map((p, idx) => (
                <button
                  key={idx}
                  onClick={() => handleSelectPreset(idx)}
                  className="btn btn-sm"
                  style={{
                    fontSize: '12px',
                    borderColor: selectedPreset === idx ? 'var(--primary)' : 'var(--border-subtle)',
                    background: selectedPreset === idx ? 'rgba(59, 130, 246, 0.2)' : 'var(--bg-surface-elevated)',
                    color: selectedPreset === idx ? 'white' : 'var(--text-secondary)',
                  }}
                >
                  {p.label}
                </button>
              ))}
            </div>
          </div>

          {/* Form Fields */}
          <div style={{ display: 'flex', flexDirection: 'column', gap: '14px', marginBottom: '20px' }}>
            <div>
              <label style={{ fontSize: '12px', color: 'var(--text-muted)', display: 'flex', alignItems: 'center', gap: '4px', marginBottom: '4px' }}>
                <MapPin size={13} color="var(--success)" /> Điểm đón khách (Pickup Address)
              </label>
              <input
                type="text"
                value={pickupAddress}
                onChange={(e) => setPickupAddress(e.target.value)}
                className="input-field"
                placeholder="Địa chỉ đón khách..."
              />
              <div style={{ display: 'flex', gap: '8px', marginTop: '4px' }}>
                <input
                  type="number"
                  step="0.0001"
                  value={pickupLat}
                  onChange={(e) => setPickupLat(parseFloat(e.target.value) || 0)}
                  className="input-field font-mono"
                  style={{ fontSize: '11px', padding: '6px 10px' }}
                  placeholder="Vĩ độ Lat"
                />
                <input
                  type="number"
                  step="0.0001"
                  value={pickupLng}
                  onChange={(e) => setPickupLng(parseFloat(e.target.value) || 0)}
                  className="input-field font-mono"
                  style={{ fontSize: '11px', padding: '6px 10px' }}
                  placeholder="Kinh độ Lng"
                />
              </div>
            </div>

            <div>
              <label style={{ fontSize: '12px', color: 'var(--text-muted)', display: 'flex', alignItems: 'center', gap: '4px', marginBottom: '4px' }}>
                <MapPin size={13} color="var(--danger)" /> Điểm trả khách (Dropoff Address)
              </label>
              <input
                type="text"
                value={dropoffAddress}
                onChange={(e) => setDropoffAddress(e.target.value)}
                className="input-field"
                placeholder="Địa chỉ trả khách..."
              />
              <div style={{ display: 'flex', gap: '8px', marginTop: '4px' }}>
                <input
                  type="number"
                  step="0.0001"
                  value={dropoffLat}
                  onChange={(e) => setDropoffLat(parseFloat(e.target.value) || 0)}
                  className="input-field font-mono"
                  style={{ fontSize: '11px', padding: '6px 10px' }}
                  placeholder="Vĩ độ Lat"
                />
                <input
                  type="number"
                  step="0.0001"
                  value={dropoffLng}
                  onChange={(e) => setDropoffLng(parseFloat(e.target.value) || 0)}
                  className="input-field font-mono"
                  style={{ fontSize: '11px', padding: '6px 10px' }}
                  placeholder="Kinh độ Lng"
                />
              </div>
            </div>

            {/* Service & Vehicle Type Selector */}
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '12px' }}>
              <div>
                <label style={{ fontSize: '12px', color: 'var(--text-muted)', display: 'block', marginBottom: '4px' }}>
                  Loại dịch vụ
                </label>
                <select
                  value={serviceType}
                  onChange={(e) => setServiceType(e.target.value as ServiceType)}
                  className="input-field"
                  style={{ fontSize: '13px' }}
                >
                  <option value="RIDE">RIDE (Chở khách)</option>
                  <option value="DELIVERY">DELIVERY (Giao hàng)</option>
                </select>
              </div>

              <div>
                <label style={{ fontSize: '12px', color: 'var(--text-muted)', display: 'block', marginBottom: '4px' }}>
                  Loại phương tiện
                </label>
                <select
                  value={vehicleType}
                  onChange={(e) => setVehicleType(e.target.value as VehicleType)}
                  className="input-field"
                  style={{ fontSize: '13px' }}
                >
                  <option value="CAR_4_SEAT">Ô tô 4 chỗ tiêu chuẩn</option>
                  <option value="CAR_7_SEAT">Ô tô 7 chỗ rộng rãi</option>
                  <option value="MOTORBIKE">Xe máy 2 bánh</option>
                </select>
              </div>
            </div>
          </div>

          {/* Action: Get Quote */}
          <button
            onClick={handleGetQuote}
            disabled={isQuoting}
            className="btn btn-primary"
            style={{ width: '100%', marginBottom: '16px' }}
          >
            {isQuoting ? 'Đang tính toán giá cước...' : 'Nhận báo giá (Get Fare Quote)'}
          </button>

          {quoteError && (
            <div
              style={{
                padding: '10px 14px',
                borderRadius: '8px',
                background: 'rgba(239, 68, 68, 0.15)',
                color: '#f87171',
                fontSize: '13px',
                marginBottom: '16px',
                display: 'flex',
                alignItems: 'center',
                gap: '8px',
              }}
            >
              <AlertTriangle size={16} />
              <span>{quoteError}</span>
            </div>
          )}

          {bookingError && (
            <div
              style={{
                padding: '10px 14px',
                borderRadius: '8px',
                background: 'rgba(239, 68, 68, 0.15)',
                color: '#f87171',
                fontSize: '13px',
                marginBottom: '16px',
                display: 'flex',
                alignItems: 'center',
                gap: '8px',
              }}
            >
              <AlertTriangle size={16} />
              <span>{bookingError}</span>
            </div>
          )}

          {/* Fare Quote Display Card */}
          {quote && (
            <div
              style={{
                background: 'linear-gradient(135deg, rgba(30, 41, 59, 0.8) 0%, rgba(15, 23, 42, 0.9) 100%)',
                border: '1px solid rgba(59, 130, 246, 0.3)',
                borderRadius: 'var(--radius-md)',
                padding: '18px',
                marginTop: '10px',
              }}
            >
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: '12px' }}>
                <div>
                  <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                    <Sparkles size={16} color="var(--primary)" />
                    <span style={{ fontSize: '14px', fontWeight: 700 }}>Báo giá từ Pricing Service</span>
                  </div>
                  <p style={{ fontSize: '11px', color: 'var(--text-muted)', fontFamily: 'var(--font-mono)', marginTop: '2px' }}>
                    Quote #{quote.quoteId.slice(0, 8)} • v{quote.pricingRuleVersion || '1.0'}
                  </p>
                </div>
                {quoteTimeLeft !== null && (
                  <div
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      gap: '4px',
                      fontSize: '12px',
                      color: quoteTimeLeft < 30 ? 'var(--danger)' : 'var(--warning)',
                      fontWeight: 600,
                      fontFamily: 'var(--font-mono)',
                    }}
                  >
                    <Clock size={13} />
                    <span>Hết hạn: {quoteTimeLeft}s</span>
                  </div>
                )}
              </div>

              {/* Price Tag */}
              <div style={{ display: 'flex', alignItems: 'baseline', gap: '8px', marginBottom: '14px' }}>
                <span style={{ fontSize: '28px', fontWeight: 900, color: '#38bdf8' }}>
                  {quote.totalFare?.toLocaleString('vi-VN')}
                </span>
                <span style={{ fontSize: '14px', fontWeight: 700, color: 'var(--text-secondary)' }}>
                  {quote.currency || 'VND'}
                </span>
                {quote.isSurgeApplied && (
                  <span
                    className="badge badge-pending"
                    style={{ fontSize: '10px', marginLeft: 'auto' }}
                  >
                    Hệ số tăng giá (Surge Active)
                  </span>
                )}
              </div>

              {/* Route Summary */}
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '8px', fontSize: '12px', color: 'var(--text-secondary)', marginBottom: '14px' }}>
                <div>
                  <span style={{ color: 'var(--text-muted)' }}>Khoảng cách: </span>
                  <strong>{(quote.distanceMeters / 1000).toFixed(1)} km</strong>
                </div>
                <div>
                  <span style={{ color: 'var(--text-muted)' }}>Thời gian ước tính: </span>
                  <strong>{Math.ceil(quote.durationSeconds / 60)} phút</strong>
                </div>
              </div>

              {/* Confirm Booking Button */}
              <button
                onClick={handleConfirmBooking}
                disabled={isBooking || (quoteTimeLeft !== null && quoteTimeLeft <= 0)}
                className="btn btn-success"
                style={{ width: '100%', padding: '11px', fontWeight: 700 }}
              >
                {isBooking ? (
                  'Đang khởi tạo chuyến đi...'
                ) : (
                  <>
                    <span>Xác nhận & Đặt chuyến ngay</span>
                    <ArrowRight size={16} />
                  </>
                )}
              </button>
            </div>
          )}
        </div>

        {/* RIGHT COLUMN: Active Trip Live Tracker */}
        {activeTripId ? (
          <div>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '12px' }}>
              <h2 style={{ fontSize: '16px', fontWeight: 700, display: 'flex', alignItems: 'center', gap: '8px' }}>
                <Car size={18} color="var(--primary)" />
                <span>Theo dõi chuyến đi: </span>
                <span style={{ fontFamily: 'var(--font-mono)', fontSize: '13px', color: 'var(--primary)' }}>
                  {activeTripId.slice(0, 13)}...
                </span>
              </h2>
              <button
                onClick={() => setActiveTripId('')}
                className="btn btn-secondary btn-sm"
                style={{ fontSize: '11px' }}
              >
                Đóng theo dõi
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
              isDriver={false}
              onRefresh={() => {
                syncTrip();
                refreshSnapshot();
              }}
              onCancelTrip={(reason) => cancelTrip(reason)}
            />

            {/* Customer Payment Section when trip is completed */}
            {tripStatus === 'COMPLETED' && trip && (
              <CustomerPaymentCard trip={trip} />
            )}
          </div>
        ) : (
          <div
            className="glass-panel"
            style={{
              padding: '40px 20px',
              textAlign: 'center',
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              justifyContent: 'center',
              color: 'var(--text-muted)',
              borderStyle: 'dashed',
            }}
          >
            <Navigation size={40} style={{ opacity: 0.3, marginBottom: '12px' }} />
            <h3 style={{ fontSize: '16px', fontWeight: 600, color: 'var(--text-secondary)', marginBottom: '4px' }}>
              Chưa có chuyến đi nào đang hoạt động
            </h3>
            <p style={{ fontSize: '13px', maxWidth: '380px' }}>
              Hãy chọn tuyến đường và bấm <strong>Nhận báo giá</strong> để trải nghiệm quy trình điều phối và theo dõi xe trực tiếp qua WebSocket/STOMP.
            </p>
          </div>
        )}
      </div>

      <PaymentHistoryModal
        isOpen={paymentHistoryOpen}
        onClose={() => setPaymentHistoryOpen(false)}
      />
    </div>
  );
};
