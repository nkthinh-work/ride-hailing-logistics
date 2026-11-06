import React, { useState, useEffect } from 'react';
import type { PaymentResponse } from '../../types';
import { paymentService } from '../../services/paymentService';
import {
  X,
  CreditCard,
  Banknote,
  Wallet,
  RefreshCw,
  AlertCircle,
  Clock,
} from 'lucide-react';

interface PaymentHistoryModalProps {
  isOpen: boolean;
  onClose: () => void;
}

export const PaymentHistoryModal: React.FC<PaymentHistoryModalProps> = ({
  isOpen,
  onClose,
}) => {
  const [payments, setPayments] = useState<PaymentResponse[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!isOpen) return;

    const loadPayments = async () => {
      setIsLoading(true);
      setError(null);
      try {
        const list = await paymentService.getMyPayments();
        setPayments(list);
      } catch (err: unknown) {
        const apiErr = err as { message?: string };
        setError(apiErr.message || 'Không thể tải lịch sử thanh toán.');
      } finally {
        setIsLoading(false);
      }
    };

    loadPayments();
  }, [isOpen]);

  if (!isOpen) return null;

  const renderMethodIcon = (method: string) => {
    if (method === 'CASH') return <Banknote size={16} color="#34d399" />;
    if (method === 'DIGITAL_WALLET') return <Wallet size={16} color="#f472b6" />;
    return <CreditCard size={16} color="#a78bfa" />;
  };

  return (
    <div
      style={{
        position: 'fixed',
        top: 0,
        left: 0,
        right: 0,
        bottom: 0,
        backgroundColor: 'rgba(0, 0, 0, 0.75)',
        backdropFilter: 'blur(4px)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        zIndex: 100,
        padding: '20px',
      }}
    >
      <div
        className="glass-panel"
        style={{
          width: '100%',
          maxWidth: '700px',
          maxHeight: '85vh',
          display: 'flex',
          flexDirection: 'column',
          borderRadius: '12px',
          background: 'rgba(15, 23, 42, 0.95)',
          border: '1px solid rgba(255, 255, 255, 0.1)',
        }}
      >
        {/* Modal Header */}
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            padding: '16px 20px',
            borderBottom: '1px solid rgba(255, 255, 255, 0.08)',
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <Banknote size={20} color="var(--primary)" />
            <h3 style={{ fontSize: '16px', fontWeight: 700, margin: 0 }}>
              Lịch sử thanh toán của tôi
            </h3>
          </div>
          <button
            onClick={onClose}
            className="btn btn-secondary btn-sm"
            style={{ padding: '4px', borderRadius: '50%' }}
          >
            <X size={16} />
          </button>
        </div>

        {/* Modal Content */}
        <div style={{ padding: '20px', overflowY: 'auto', flex: 1 }}>
          {isLoading ? (
            <div style={{ textAlign: 'center', padding: '40px 0', color: 'var(--text-muted)' }}>
              <RefreshCw size={24} className="spin" style={{ margin: '0 auto 8px' }} />
              <div>Đang tải dữ liệu thanh toán...</div>
            </div>
          ) : error ? (
            <div
              style={{
                background: 'rgba(239, 68, 68, 0.1)',
                border: '1px solid rgba(239, 68, 68, 0.3)',
                padding: '12px',
                borderRadius: '8px',
                color: '#f87171',
                fontSize: '13px',
                display: 'flex',
                alignItems: 'center',
                gap: '8px',
              }}
            >
              <AlertCircle size={16} />
              <span>{error}</span>
            </div>
          ) : payments.length === 0 ? (
            <div style={{ textAlign: 'center', padding: '40px 0', color: 'var(--text-muted)' }}>
              <Clock size={32} style={{ margin: '0 auto 8px', opacity: 0.5 }} />
              <p>Chưa có giao dịch thanh toán nào được ghi nhận.</p>
            </div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
              {payments.map((p) => (
                <div
                  key={p.paymentId}
                  style={{
                    background: 'rgba(255, 255, 255, 0.03)',
                    border: '1px solid rgba(255, 255, 255, 0.06)',
                    borderRadius: '8px',
                    padding: '12px 16px',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                  }}
                >
                  <div>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '4px' }}>
                      {renderMethodIcon(p.paymentMethod)}
                      <span style={{ fontSize: '13px', fontWeight: 600 }}>{p.paymentMethod}</span>
                      <span
                        className={
                          p.status === 'SUCCEEDED'
                            ? 'badge badge-approved'
                            : p.status === 'FAILED'
                            ? 'badge badge-rejected'
                            : 'badge badge-pending'
                        }
                        style={{ fontSize: '10px' }}
                      >
                        {p.status}
                      </span>
                    </div>
                    <div style={{ fontSize: '11px', color: 'var(--text-muted)' }}>
                      Trip: <code>{p.tripId.slice(0, 8)}...</code> | Giao dịch: <code>{p.paymentId.slice(0, 8)}...</code>
                    </div>
                    <div style={{ fontSize: '11px', color: 'var(--text-muted)', marginTop: '2px' }}>
                      {new Date(p.createdAt).toLocaleString('vi-VN')}
                    </div>
                  </div>

                  <div style={{ textAlign: 'right' }}>
                    <div style={{ fontSize: '16px', fontWeight: 800, color: '#38bdf8' }}>
                      {p.amount.toLocaleString('vi-VN')} {p.currency}
                    </div>
                    {p.providerReference && (
                      <div style={{ fontSize: '10px', color: 'var(--text-muted)' }}>
                        Ref: {p.providerReference}
                      </div>
                    )}
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>

        {/* Modal Footer */}
        <div
          style={{
            padding: '12px 20px',
            borderTop: '1px solid rgba(255, 255, 255, 0.08)',
            display: 'flex',
            justifyContent: 'flex-end',
          }}
        >
          <button onClick={onClose} className="btn btn-secondary btn-sm">
            Đóng
          </button>
        </div>
      </div>
    </div>
  );
};
