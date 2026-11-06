import React, { useState, useEffect, useRef } from 'react';
import type { TripResponse, PaymentResponse, PaymentMethod, RefundEligibilityResponse } from '../../types';
import { paymentService } from '../../services/paymentService';
import {
  Banknote,
  CreditCard,
  Wallet,
  CheckCircle2,
  AlertCircle,
  Clock,
  ShieldAlert,
  RefreshCw,
  Info,
} from 'lucide-react';

interface CustomerPaymentCardProps {
  trip: TripResponse;
  onPaymentComplete?: (payment: PaymentResponse) => void;
}

export const CustomerPaymentCard: React.FC<CustomerPaymentCardProps> = ({
  trip,
  onPaymentComplete,
}) => {
  const [selectedMethod, setSelectedMethod] = useState<PaymentMethod>('CASH');
  const [payment, setPayment] = useState<PaymentResponse | null>(null);
  const [isInitiating, setIsInitiating] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [isPolling, setIsPolling] = useState(false);

  // Refund eligibility state
  const [refundEligibility, setRefundEligibility] = useState<RefundEligibilityResponse | null>(null);
  const [isCheckingRefund, setIsCheckingRefund] = useState(false);

  // Stable idempotency key per payment intent
  const idempotencyKeyRef = useRef<string>(
    'pay-cust-' + trip.tripId.slice(0, 8) + '-' + Date.now().toString(36)
  );

  // Authoritative amount from trip
  const finalFare = trip.estimatedFare || 0;
  const currency = trip.currency || 'VND';

  // Check if trip already has existing payment
  useEffect(() => {
    let isMounted = true;
    const fetchExistingPayment = async () => {
      try {
        const payments = await paymentService.getPaymentsByTripId(trip.tripId);
        if (isMounted && payments.length > 0) {
          // Take the most recent payment
          const latest = payments[payments.length - 1];
          setPayment(latest);
          if (latest.status === 'PENDING' || latest.status === 'PROCESSING') {
            setIsPolling(true);
          }
        }
      } catch {
        // ignore if not yet initiated
      }
    };
    fetchExistingPayment();
    return () => {
      isMounted = false;
    };
  }, [trip.tripId]);

  // Bounded Polling when payment is PENDING or PROCESSING (every 3 seconds, up to 60 times = 3 mins)
  useEffect(() => {
    if (!isPolling || !payment || (payment.status !== 'PENDING' && payment.status !== 'PROCESSING')) {
      return;
    }

    let pollCount = 0;
    const maxPolls = 60;
    const interval = setInterval(async () => {
      pollCount++;
      if (pollCount > maxPolls) {
        setIsPolling(false);
        clearInterval(interval);
        return;
      }

      try {
        const updated = await paymentService.getPaymentById(payment.paymentId);
        setPayment(updated);
        if (updated.status === 'SUCCEEDED' || updated.status === 'FAILED' || updated.status === 'CANCELLED') {
          setIsPolling(false);
          clearInterval(interval);
          if (updated.status === 'SUCCEEDED' && onPaymentComplete) {
            onPaymentComplete(updated);
          }
        }
      } catch {
        // continue polling despite temporary network glitches
      }
    }, 3000);

    return () => clearInterval(interval);
  }, [isPolling, payment?.paymentId, payment?.status, onPaymentComplete]);

  // Handle Initiate Payment
  const handleInitiatePayment = async () => {
    if (selectedMethod !== 'CASH') {
      return; // MoMo and Credit Card are sandbox-gated
    }

    setIsInitiating(true);
    setErrorMessage(null);

    try {
      const res = await paymentService.initiatePayment(
        {
          tripId: trip.tripId,
          paymentMethod: 'CASH',
          currency,
          amount: finalFare,
        },
        idempotencyKeyRef.current
      );
      setPayment(res);
      setIsPolling(true);
    } catch (err: unknown) {
      const apiErr = err as { message?: string; status?: number };
      setErrorMessage(
        apiErr.message || 'Không thể khởi tạo thanh toán. Vui lòng thử lại sau.'
      );
    } finally {
      setIsInitiating(false);
    }
  };

  // Handle Check Refund Eligibility
  const handleCheckRefundEligibility = async () => {
    if (!payment) return;
    setIsCheckingRefund(true);
    try {
      const res = await paymentService.checkRefundEligibility(payment.paymentId);
      setRefundEligibility(res);
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      setErrorMessage(apiErr.message || 'Không thể kiểm tra điều kiện hoàn tiền.');
    } finally {
      setIsCheckingRefund(false);
    }
  };

  return (
    <div
      className="glass-panel"
      style={{
        padding: '24px',
        borderRadius: '12px',
        marginTop: '20px',
        border: '1px solid rgba(59, 130, 246, 0.3)',
        background: 'linear-gradient(180deg, rgba(30, 41, 59, 0.7) 0%, rgba(15, 23, 42, 0.85) 100%)',
      }}
    >
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
            <Banknote size={20} color="var(--primary)" />
          </div>
          <div>
            <h3 style={{ fontSize: '16px', fontWeight: 700, margin: 0, color: '#f8fafc' }}>
              Thanh toán chuyến đi
            </h3>
            <span style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
              Mã chuyến: <code>{trip.tripId.slice(0, 8)}...</code>
            </span>
          </div>
        </div>

        {payment && (
          <span
            className={
              payment.status === 'SUCCEEDED'
                ? 'badge badge-approved'
                : payment.status === 'FAILED'
                ? 'badge badge-rejected'
                : 'badge badge-pending'
            }
            style={{ fontSize: '12px', padding: '4px 10px' }}
          >
            {payment.status}
          </span>
        )}
      </div>

      {/* Authoritative Fare Summary */}
      <div
        style={{
          background: 'rgba(15, 23, 42, 0.6)',
          borderRadius: '8px',
          padding: '16px',
          marginBottom: '20px',
          border: '1px solid rgba(255, 255, 255, 0.05)',
        }}
      >
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
          <span style={{ fontSize: '14px', color: 'var(--text-secondary)' }}>Tổng cước cần thanh toán:</span>
          <div>
            <span style={{ fontSize: '26px', fontWeight: 800, color: '#38bdf8' }}>
              {finalFare.toLocaleString('vi-VN')}
            </span>
            <span style={{ fontSize: '14px', fontWeight: 700, marginLeft: '6px', color: 'var(--text-secondary)' }}>
              {currency}
            </span>
          </div>
        </div>
      </div>

      {/* Error Banner */}
      {errorMessage && (
        <div
          style={{
            background: 'rgba(239, 68, 68, 0.1)',
            border: '1px solid rgba(239, 68, 68, 0.3)',
            borderRadius: '8px',
            padding: '12px',
            marginBottom: '16px',
            display: 'flex',
            alignItems: 'center',
            gap: '10px',
            color: '#f87171',
            fontSize: '13px',
          }}
        >
          <AlertCircle size={18} />
          <span>{errorMessage}</span>
        </div>
      )}

      {/* State 1: Payment already SUCCEEDED */}
      {payment?.status === 'SUCCEEDED' ? (
        <div>
          <div
            style={{
              background: 'rgba(16, 185, 129, 0.1)',
              border: '1px solid rgba(16, 185, 129, 0.3)',
              borderRadius: '8px',
              padding: '16px',
              textAlign: 'center',
              marginBottom: '16px',
            }}
          >
            <CheckCircle2 size={36} color="#34d399" style={{ margin: '0 auto 8px' }} />
            <h4 style={{ fontSize: '16px', fontWeight: 700, color: '#34d399', margin: '0 0 4px' }}>
              Thanh toán thành công
            </h4>
            <p style={{ fontSize: '13px', color: 'var(--text-secondary)', margin: 0 }}>
              {payment.paymentMethod === 'CASH'
                ? 'Tài xế đã xác nhận thu tiền mặt đầy đủ.'
                : 'Giao dịch trực tuyến đã hoàn tất.'}
            </p>
            <div style={{ marginTop: '12px', fontSize: '12px', color: 'var(--text-muted)' }}>
              Mã giao dịch: <code>{payment.paymentId}</code> | Mã đối soát: <code>{payment.providerReference || 'N/A'}</code>
            </div>
          </div>

          {/* Refund Policy Information Gating */}
          <div
            style={{
              background: 'rgba(245, 158, 11, 0.08)',
              border: '1px solid rgba(245, 158, 11, 0.25)',
              borderRadius: '8px',
              padding: '14px',
              fontSize: '12px',
              color: '#fbbf24',
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px', fontWeight: 600, marginBottom: '6px' }}>
              <ShieldAlert size={16} />
              <span>Chính sách hoàn tiền (REFUND_POLICY_PENDING)</span>
            </div>
            <p style={{ margin: '0 0 10px', color: 'var(--text-secondary)' }}>
              Chính sách hoàn tiền tự phục vụ đang chờ phê duyệt từ ban vận hành. Hiện tại khách hàng không thể tự yêu cầu hoàn tiền tùy ý.
            </p>
            <button
              onClick={handleCheckRefundEligibility}
              disabled={isCheckingRefund}
              className="btn btn-secondary btn-sm"
              style={{ fontSize: '11px', display: 'flex', alignItems: 'center', gap: '6px' }}
            >
              {isCheckingRefund ? <RefreshCw size={12} className="spin" /> : <Info size={12} />}
              Kiểm tra điều kiện hoàn tiền (Eligibility Check)
            </button>

            {refundEligibility && (
              <div style={{ marginTop: '10px', padding: '10px', background: 'rgba(0,0,0,0.2)', borderRadius: '6px' }}>
                <div>Trạng thái hợp lệ: <strong>{refundEligibility.eligible ? 'ĐỦ ĐIỀU KIỆN' : 'KHÔNG ĐỦ ĐIỀU KIỆN'}</strong></div>
                <div>Lý do backend: <em>{refundEligibility.reason || 'N/A'}</em></div>
                <div>Chính sách: <code>{refundEligibility.policyStatus}</code></div>
              </div>
            )}
          </div>
        </div>
      ) : payment?.status === 'PENDING' || payment?.status === 'PROCESSING' ? (
        /* State 2: Payment in Progress (CASH collection waiting or Digital processing) */
        <div
          style={{
            background: 'rgba(59, 130, 246, 0.08)',
            border: '1px solid rgba(59, 130, 246, 0.3)',
            borderRadius: '8px',
            padding: '20px',
            textAlign: 'center',
          }}
        >
          <Clock size={32} color="#60a5fa" style={{ margin: '0 auto 8px', animation: 'pulse 1.5s infinite' }} />
          <h4 style={{ fontSize: '15px', fontWeight: 700, color: '#93c5fd', margin: '0 0 6px' }}>
            {payment.paymentMethod === 'CASH'
              ? 'Chờ tài xế xác nhận nhận tiền mặt'
              : 'Đang xử lý giao dịch...'}
          </h4>
          <p style={{ fontSize: '13px', color: 'var(--text-secondary)', margin: '0 0 14px' }}>
            {payment.paymentMethod === 'CASH'
              ? 'Vui lòng gửi tiền mặt cho tài xế. Tài xế sẽ bấm xác nhận trên ứng dụng Cockpit.'
              : 'Hệ thống đang kết nối với cổng thanh toán để cập nhật kết quả.'}
          </p>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '8px', fontSize: '12px', color: 'var(--text-muted)' }}>
            <RefreshCw size={13} className="spin" />
            <span>Tự động đồng bộ trạng thái (Realtime Polling)...</span>
          </div>
        </div>
      ) : (
        /* State 3: Payment Selection & Initiation */
        <div>
          <label style={{ fontSize: '13px', fontWeight: 600, color: 'var(--text-secondary)', display: 'block', marginBottom: '8px' }}>
            Chọn phương thức thanh toán:
          </label>

          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '10px', marginBottom: '16px' }}>
            {/* CASH Option */}
            <button
              type="button"
              onClick={() => setSelectedMethod('CASH')}
              style={{
                display: 'flex',
                flexDirection: 'column',
                alignItems: 'center',
                gap: '8px',
                padding: '14px 10px',
                borderRadius: '8px',
                border: selectedMethod === 'CASH' ? '2px solid #3b82f6' : '1px solid rgba(255, 255, 255, 0.1)',
                background: selectedMethod === 'CASH' ? 'rgba(59, 130, 246, 0.15)' : 'rgba(255, 255, 255, 0.03)',
                color: selectedMethod === 'CASH' ? '#60a5fa' : 'var(--text-secondary)',
                cursor: 'pointer',
                transition: 'all 0.2s',
              }}
            >
              <Banknote size={22} />
              <span style={{ fontSize: '12px', fontWeight: 600 }}>Tiền mặt (CASH)</span>
            </button>

            {/* DIGITAL WALLET (MoMo) Option */}
            <button
              type="button"
              onClick={() => setSelectedMethod('DIGITAL_WALLET')}
              style={{
                display: 'flex',
                flexDirection: 'column',
                alignItems: 'center',
                gap: '8px',
                padding: '14px 10px',
                borderRadius: '8px',
                border: selectedMethod === 'DIGITAL_WALLET' ? '2px solid #ec4899' : '1px solid rgba(255, 255, 255, 0.1)',
                background: selectedMethod === 'DIGITAL_WALLET' ? 'rgba(236, 72, 153, 0.15)' : 'rgba(255, 255, 255, 0.03)',
                color: selectedMethod === 'DIGITAL_WALLET' ? '#f472b6' : 'var(--text-secondary)',
                cursor: 'pointer',
                transition: 'all 0.2s',
              }}
            >
              <Wallet size={22} />
              <span style={{ fontSize: '12px', fontWeight: 600 }}>Ví MoMo</span>
            </button>

            {/* CREDIT CARD Option */}
            <button
              type="button"
              onClick={() => setSelectedMethod('CREDIT_CARD')}
              style={{
                display: 'flex',
                flexDirection: 'column',
                alignItems: 'center',
                gap: '8px',
                padding: '14px 10px',
                borderRadius: '8px',
                border: selectedMethod === 'CREDIT_CARD' ? '2px solid #8b5cf6' : '1px solid rgba(255, 255, 255, 0.1)',
                background: selectedMethod === 'CREDIT_CARD' ? 'rgba(139, 92, 246, 0.15)' : 'rgba(255, 255, 255, 0.03)',
                color: selectedMethod === 'CREDIT_CARD' ? '#a78bfa' : 'var(--text-secondary)',
                cursor: 'pointer',
                transition: 'all 0.2s',
              }}
            >
              <CreditCard size={22} />
              <span style={{ fontSize: '12px', fontWeight: 600 }}>Thẻ quốc tế</span>
            </button>
          </div>

          {/* MoMo Sandbox / Credit Card Provider Notice */}
          {selectedMethod !== 'CASH' && (
            <div
              style={{
                background: 'rgba(245, 158, 11, 0.08)',
                border: '1px solid rgba(245, 158, 11, 0.25)',
                borderRadius: '8px',
                padding: '12px',
                marginBottom: '16px',
                display: 'flex',
                alignItems: 'flex-start',
                gap: '10px',
                color: '#fbbf24',
                fontSize: '12px',
              }}
            >
              <ShieldAlert size={18} style={{ flexShrink: 0, marginTop: '2px' }} />
              <div>
                <strong style={{ display: 'block', marginBottom: '4px' }}>
                  MoMo Sandbox Credentials Chưa Cấu Hình (MOMO_LIVE_SANDBOX_E2E_PENDING)
                </strong>
                Hệ thống chưa được nạp Merchant Secret & Partner Code thật của MoMo Sandbox. Theo nguyên tắc an toàn tài chính, không giả lập (fake) link thanh toán hay webhook success. Vui lòng sử dụng phương thức <strong>Tiền mặt (CASH)</strong> để trải nghiệm trọn vẹn luồng thanh toán và đối soát.
              </div>
            </div>
          )}

          {/* Action Button */}
          {selectedMethod === 'CASH' ? (
            <button
              type="button"
              onClick={handleInitiatePayment}
              disabled={isInitiating}
              className="btn btn-primary"
              style={{ width: '100%', padding: '12px', fontWeight: 700 }}
            >
              {isInitiating ? (
                <>
                  <RefreshCw size={16} className="spin" />
                  <span>Đang khởi tạo thanh toán tiền mặt...</span>
                </>
              ) : (
                <span>Xác nhận thanh toán TIỀN MẶT ({finalFare.toLocaleString('vi-VN')} {currency})</span>
              )}
            </button>
          ) : (
            <button
              type="button"
              disabled
              className="btn btn-secondary"
              style={{ width: '100%', padding: '12px', opacity: 0.6, cursor: 'not-allowed' }}
            >
              Phương thức trực tuyến tạm khóa (Chờ cấu hình Sandbox)
            </button>
          )}
        </div>
      )}
    </div>
  );
};
