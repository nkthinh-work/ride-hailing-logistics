import React, { useState, useEffect } from 'react';
import type { TripResponse, PaymentResponse } from '../../types';
import { paymentService } from '../../services/paymentService';
import {
  Banknote,
  CheckCircle2,
  AlertCircle,
  RefreshCw,
  Clock,
  Check,
} from 'lucide-react';

interface DriverCashCollectionCardProps {
  tripId: string;
  trip?: TripResponse | null;
  onConfirmed?: () => void;
}

export const DriverCashCollectionCard: React.FC<DriverCashCollectionCardProps> = ({
  tripId,
  trip,
  onConfirmed,
}) => {
  const [payments, setPayments] = useState<PaymentResponse[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [isConfirming, setIsConfirming] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const [confirmDialogOpen, setConfirmDialogOpen] = useState(false);

  const fetchPayments = async () => {
    setIsLoading(true);
    setErrorMsg(null);
    try {
      const list = await paymentService.getDriverTripPayments(tripId);
      setPayments(list);
    } catch (err: unknown) {
      const apiErr = err as { message?: string; status?: number };
      if (apiErr.status === 403) {
        setErrorMsg('Bạn không phải tài xế được chỉ định cho chuyến đi này (HTTP 403).');
      } else {
        setErrorMsg(apiErr.message || 'Không thể tra cứu thông tin thanh toán chuyến đi.');
      }
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    if (tripId) {
      fetchPayments();
    }
  }, [tripId]);

  // Find CASH payment
  const cashPayment = payments.find((p) => p.paymentMethod === 'CASH') || payments[payments.length - 1];

  const handleConfirmCash = async () => {
    if (!cashPayment) return;

    setIsConfirming(true);
    setErrorMsg(null);
    try {
      const updated = await paymentService.confirmCashPayment(cashPayment.paymentId);
      setPayments((prev) =>
        prev.map((p) => (p.paymentId === updated.paymentId ? updated : p))
      );
      setConfirmDialogOpen(false);
      if (onConfirmed) {
        onConfirmed();
      }
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      setErrorMsg(apiErr.message || 'Xác nhận thu tiền mặt thất bại.');
    } finally {
      setIsConfirming(false);
    }
  };

  const amountToCollect = cashPayment?.amount || trip?.estimatedFare || 0;
  const currency = cashPayment?.currency || trip?.currency || 'VND';

  return (
    <div
      className="glass-panel"
      style={{
        padding: '24px',
        borderRadius: '12px',
        marginTop: '20px',
        border: '1px solid rgba(16, 185, 129, 0.3)',
        background: 'linear-gradient(180deg, rgba(6, 78, 59, 0.25) 0%, rgba(15, 23, 42, 0.9) 100%)',
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '16px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
          <div
            style={{
              width: '36px',
              height: '36px',
              borderRadius: '8px',
              background: 'rgba(16, 185, 129, 0.2)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <Banknote size={20} color="#34d399" />
          </div>
          <div>
            <h3 style={{ fontSize: '16px', fontWeight: 700, margin: 0, color: '#f8fafc' }}>
              Thu tiền mặt chuyến đi (Cash Collection)
            </h3>
            <span style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
              Mã chuyến: <code>{tripId.slice(0, 8)}...</code>
            </span>
          </div>
        </div>

        <button
          onClick={fetchPayments}
          disabled={isLoading}
          className="btn btn-secondary btn-sm"
          style={{ fontSize: '11px', display: 'flex', alignItems: 'center', gap: '4px' }}
        >
          <RefreshCw size={12} className={isLoading ? 'spin' : ''} />
          <span>Làm mới</span>
        </button>
      </div>

      {/* Error Alert */}
      {errorMsg && (
        <div
          style={{
            background: 'rgba(239, 68, 68, 0.1)',
            border: '1px solid rgba(239, 68, 68, 0.3)',
            borderRadius: '8px',
            padding: '12px',
            marginBottom: '16px',
            display: 'flex',
            alignItems: 'center',
            gap: '8px',
            color: '#f87171',
            fontSize: '13px',
          }}
        >
          <AlertCircle size={16} />
          <span>{errorMsg}</span>
        </div>
      )}

      {/* Amount Display */}
      <div
        style={{
          background: 'rgba(15, 23, 42, 0.6)',
          borderRadius: '8px',
          padding: '16px',
          marginBottom: '16px',
          border: '1px solid rgba(255, 255, 255, 0.05)',
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'baseline',
        }}
      >
        <span style={{ fontSize: '14px', color: 'var(--text-secondary)' }}>
          Số tiền cần thu từ khách:
        </span>
        <div>
          <span style={{ fontSize: '26px', fontWeight: 800, color: '#34d399' }}>
            {amountToCollect.toLocaleString('vi-VN')}
          </span>
          <span style={{ fontSize: '14px', fontWeight: 700, marginLeft: '6px', color: 'var(--text-secondary)' }}>
            {currency}
          </span>
        </div>
      </div>

      {/* Scenario 1: Cash payment already SUCCEEDED */}
      {cashPayment?.status === 'SUCCEEDED' ? (
        <div
          style={{
            background: 'rgba(16, 185, 129, 0.12)',
            border: '1px solid rgba(16, 185, 129, 0.3)',
            borderRadius: '8px',
            padding: '16px',
            textAlign: 'center',
          }}
        >
          <CheckCircle2 size={36} color="#34d399" style={{ margin: '0 auto 8px' }} />
          <h4 style={{ fontSize: '15px', fontWeight: 700, color: '#34d399', margin: '0 0 4px' }}>
            Đã xác nhận thu tiền mặt thành công
          </h4>
          <p style={{ fontSize: '13px', color: 'var(--text-secondary)', margin: 0 }}>
            Giao dịch tiền mặt đã được ghi nhận quyết toán vào hệ thống.
          </p>
          <div style={{ marginTop: '10px', fontSize: '11px', color: 'var(--text-muted)' }}>
            Mã giao dịch: <code>{cashPayment.paymentId}</code> | Đối soát: <code>{cashPayment.providerReference}</code>
          </div>
        </div>
      ) : cashPayment?.status === 'PENDING' || cashPayment?.status === 'PROCESSING' ? (
        /* Scenario 2: Payment waiting for driver cash confirmation */
        <div>
          <div
            style={{
              background: 'rgba(59, 130, 246, 0.08)',
              border: '1px solid rgba(59, 130, 246, 0.25)',
              borderRadius: '8px',
              padding: '12px',
              marginBottom: '16px',
              display: 'flex',
              alignItems: 'center',
              gap: '10px',
              fontSize: '13px',
              color: '#93c5fd',
            }}
          >
            <Clock size={18} />
            <span>Khách hàng đã chọn phương thức Tiền mặt. Hãy thu đủ tiền trước khi xác nhận.</span>
          </div>

          {confirmDialogOpen ? (
            <div
              style={{
                background: 'rgba(15, 23, 42, 0.9)',
                border: '1px solid rgba(16, 185, 129, 0.4)',
                borderRadius: '8px',
                padding: '16px',
                textAlign: 'center',
              }}
            >
              <h4 style={{ fontSize: '14px', fontWeight: 700, margin: '0 0 8px', color: '#f8fafc' }}>
                Xác nhận đã nhận đủ {amountToCollect.toLocaleString('vi-VN')} {currency} tiền mặt?
              </h4>
              <p style={{ fontSize: '12px', color: 'var(--text-secondary)', margin: '0 0 14px' }}>
                Hành động này sẽ cập nhật trạng thái thanh toán của cuốc xe thành SUCCEEDED và không thể hoàn tác trực tiếp bởi tài xế.
              </p>
              <div style={{ display: 'flex', gap: '10px', justifyContent: 'center' }}>
                <button
                  type="button"
                  onClick={() => setConfirmDialogOpen(false)}
                  disabled={isConfirming}
                  className="btn btn-secondary btn-sm"
                >
                  Hủy bỏ
                </button>
                <button
                  type="button"
                  onClick={handleConfirmCash}
                  disabled={isConfirming}
                  className="btn btn-success btn-sm"
                  style={{ display: 'flex', alignItems: 'center', gap: '6px' }}
                >
                  {isConfirming ? (
                    <>
                      <RefreshCw size={14} className="spin" />
                      <span>Đang xác nhận...</span>
                    </>
                  ) : (
                    <>
                      <Check size={14} />
                      <span>Xác nhận đã nhận tiền</span>
                    </>
                  )}
                </button>
              </div>
            </div>
          ) : (
            <button
              type="button"
              onClick={() => setConfirmDialogOpen(true)}
              className="btn btn-success"
              style={{ width: '100%', padding: '12px', fontWeight: 700, display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '8px' }}
            >
              <Check size={18} />
              <span>Xác nhận đã nhận tiền mặt ({amountToCollect.toLocaleString('vi-VN')} {currency})</span>
            </button>
          )}
        </div>
      ) : (
        /* Scenario 3: No payment transaction initiated by customer yet */
        <div
          style={{
            background: 'rgba(255, 255, 255, 0.03)',
            borderRadius: '8px',
            padding: '16px',
            textAlign: 'center',
            color: 'var(--text-muted)',
            fontSize: '13px',
          }}
        >
          <Clock size={24} style={{ margin: '0 auto 8px', opacity: 0.5 }} />
          <div>Chờ hành khách tạo thanh toán tiền mặt...</div>
          <div style={{ fontSize: '11px', marginTop: '4px' }}>
            Bấm "Làm mới" khi hành khách hoàn tất lựa chọn phương thức thanh toán.
          </div>
        </div>
      )}
    </div>
  );
};
