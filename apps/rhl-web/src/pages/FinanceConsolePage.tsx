import React, { useState, useEffect } from 'react';
import { useAuth } from '../context/AuthContext';
import { paymentService } from '../services/paymentService';
import type {
  ReconciliationRecord,
  RefundResponse,
  RefundEligibilityResponse,
} from '../types';
import {
  DollarSign,
  ShieldCheck,
  RefreshCw,
  Play,
  Search,
  CheckCircle2,
  FileText,
  ShieldAlert,
} from 'lucide-react';

export const FinanceConsolePage: React.FC = () => {
  const { user } = useAuth();

  // Active Tab
  const [activeTab, setActiveTab] = useState<'RECONCILIATION' | 'REFUNDS'>('RECONCILIATION');

  // Reconciliation State
  const [mismatches, setMismatches] = useState<ReconciliationRecord[]>([]);
  const [isLoadingRecon, setIsLoadingRecon] = useState(false);
  const [isRunningRecon, setIsRunningRecon] = useState(false);
  const [reconRunResult, setReconRunResult] = useState<{ processed: number; mismatches: number; runAt: string } | null>(null);
  const [reconError, setReconError] = useState<string | null>(null);

  // Refund State
  const [lookupPaymentId, setLookupPaymentId] = useState('');
  const [refunds, setRefunds] = useState<RefundResponse[]>([]);
  const [eligibility, setEligibility] = useState<RefundEligibilityResponse | null>(null);
  const [isLoadingRefunds, setIsLoadingRefunds] = useState(false);
  const [refundError, setRefundError] = useState<string | null>(null);
  const [refundActionMsg, setRefundActionMsg] = useState<{ text: string; error?: boolean } | null>(null);

  // Load initial reconciliation records
  const loadMismatches = async () => {
    setIsLoadingRecon(true);
    setReconError(null);
    try {
      const records = await paymentService.getReconciliationMismatches();
      setMismatches(records);
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      setReconError(apiErr.message || 'Không thể tải danh sách đối soát.');
    } finally {
      setIsLoadingRecon(false);
    }
  };

  useEffect(() => {
    loadMismatches();
  }, []);

  // Trigger reconciliation run
  const handleTriggerReconciliation = async () => {
    setIsRunningRecon(true);
    setReconError(null);
    try {
      const res = await paymentService.runReconciliation();
      setReconRunResult(res);
      await loadMismatches();
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      setReconError(apiErr.message || 'Chạy quy trình đối soát thất bại.');
    } finally {
      setIsRunningRecon(false);
    }
  };

  // Lookup payment refunds & eligibility
  const handleLookupRefunds = async (e?: React.FormEvent) => {
    if (e) e.preventDefault();
    if (!lookupPaymentId.trim()) return;

    setIsLoadingRefunds(true);
    setRefundError(null);
    setRefundActionMsg(null);
    setRefunds([]);
    setEligibility(null);

    try {
      const [refundList, elig] = await Promise.allSettled([
        paymentService.getPaymentRefunds(lookupPaymentId.trim()),
        paymentService.checkRefundEligibility(lookupPaymentId.trim()),
      ]);

      if (refundList.status === 'fulfilled') {
        setRefunds(refundList.value);
      }
      if (elig.status === 'fulfilled') {
        setEligibility(elig.value);
      }
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      setRefundError(apiErr.message || 'Lỗi tra cứu thông tin hoàn tiền.');
    } finally {
      setIsLoadingRefunds(false);
    }
  };

  // Approve refund (gated by policy)
  const handleApproveRefund = async (refundId: string) => {
    if (!window.confirm('CẢNH BÁO: Chính sách hoàn tiền hiện ở trạng thái REFUND_POLICY_PENDING. Bạn có chắc chắn muốn thử duyệt yêu cầu này?')) {
      return;
    }
    setRefundActionMsg(null);
    try {
      const approved = await paymentService.approveRefund(refundId);
      setRefunds((prev) => prev.map((r) => (r.refundId === approved.refundId ? approved : r)));
      setRefundActionMsg({ text: `Đã duyệt yêu cầu hoàn tiền #${refundId.slice(0, 8)}.` });
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      setRefundActionMsg({
        text: `Không thể duyệt hoàn tiền: ${apiErr.message || 'Bị từ chối do chính sách (REFUND_POLICY_PENDING)'}`,
        error: true,
      });
    }
  };

  return (
    <div style={{ maxWidth: '1280px', margin: '0 auto', padding: '32px 20px' }}>
      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '28px' }}>
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '6px' }}>
            <div
              style={{
                width: '40px',
                height: '40px',
                borderRadius: '10px',
                background: 'linear-gradient(135deg, #f59e0b 0%, #d97706 100%)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                boxShadow: '0 0 15px rgba(245, 158, 11, 0.4)',
              }}
            >
              <DollarSign size={22} color="white" />
            </div>
            <div>
              <h1 style={{ fontSize: '24px', fontWeight: 800, margin: 0, letterSpacing: '-0.5px' }}>
                Bảng điều khiển Tài chính & Đối soát (Finance Console)
              </h1>
              <span style={{ fontSize: '13px', color: 'var(--text-secondary)' }}>
                Giám sát đối soát giao dịch, lệch sổ (Mismatches), và kiểm soát quy trình hoàn tiền
              </span>
            </div>
          </div>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
          <span className="badge badge-admin" style={{ padding: '6px 12px', fontSize: '12px' }}>
            <ShieldCheck size={14} style={{ marginRight: '4px', verticalAlign: 'text-top' }} />
            RBAC: {user?.roles?.join(', ')}
          </span>
        </div>
      </div>

      {/* Tabs */}
      <div style={{ display: 'flex', gap: '12px', marginBottom: '24px', borderBottom: '1px solid rgba(255, 255, 255, 0.08)' }}>
        <button
          onClick={() => setActiveTab('RECONCILIATION')}
          style={{
            padding: '10px 18px',
            fontSize: '14px',
            fontWeight: 700,
            background: 'transparent',
            border: 'none',
            borderBottom: activeTab === 'RECONCILIATION' ? '2px solid #f59e0b' : '2px solid transparent',
            color: activeTab === 'RECONCILIATION' ? '#fbbf24' : 'var(--text-secondary)',
            cursor: 'pointer',
            transition: 'all 0.2s',
          }}
        >
          Đối soát & Lệch số (Reconciliation)
        </button>
        <button
          onClick={() => setActiveTab('REFUNDS')}
          style={{
            padding: '10px 18px',
            fontSize: '14px',
            fontWeight: 700,
            background: 'transparent',
            border: 'none',
            borderBottom: activeTab === 'REFUNDS' ? '2px solid #f59e0b' : '2px solid transparent',
            color: activeTab === 'REFUNDS' ? '#fbbf24' : 'var(--text-secondary)',
            cursor: 'pointer',
            transition: 'all 0.2s',
          }}
        >
          Quản lý hoàn tiền & Chính sách (Refunds)
        </button>
      </div>

      {/* TAB 1: RECONCILIATION */}
      {activeTab === 'RECONCILIATION' && (
        <div>
          {/* Controls & Summary */}
          <div
            className="glass-panel"
            style={{
              padding: '20px',
              borderRadius: '12px',
              marginBottom: '20px',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              gap: '20px',
            }}
          >
            <div>
              <h3 style={{ fontSize: '16px', fontWeight: 700, margin: '0 0 4px', color: '#f8fafc' }}>
                Đối soát giao dịch định kỳ (Reconciliation Worker)
              </h3>
              <p style={{ fontSize: '13px', color: 'var(--text-secondary)', margin: 0 }}>
                So sánh trạng thái cục bộ của giao dịch (Local DB) với báo cáo từ cổng thanh toán đối tác (Provider Settlement).
              </p>
              {reconRunResult && (
                <div style={{ marginTop: '8px', fontSize: '12px', color: '#34d399' }}>
                  Kết quả lần chạy gần nhất: Đã kiểm tra <strong>{reconRunResult.processed}</strong> giao dịch, phát hiện <strong>{reconRunResult.mismatches}</strong> lệch số lúc {new Date(reconRunResult.runAt).toLocaleTimeString('vi-VN')}.
                </div>
              )}
            </div>

            <div style={{ display: 'flex', gap: '10px' }}>
              <button
                onClick={loadMismatches}
                disabled={isLoadingRecon}
                className="btn btn-secondary btn-sm"
              >
                <RefreshCw size={14} className={isLoadingRecon ? 'spin' : ''} />
                <span>Làm mới bảng</span>
              </button>
              <button
                onClick={handleTriggerReconciliation}
                disabled={isRunningRecon}
                className="btn btn-primary btn-sm"
                style={{ background: 'linear-gradient(135deg, #f59e0b 0%, #d97706 100%)', borderColor: '#d97706' }}
              >
                {isRunningRecon ? (
                  <>
                    <RefreshCw size={14} className="spin" />
                    <span>Đang đối soát...</span>
                  </>
                ) : (
                  <>
                    <Play size={14} />
                    <span>Kích hoạt đối soát ngay</span>
                  </>
                )}
              </button>
            </div>
          </div>

          {/* Error Banner */}
          {reconError && (
            <div
              style={{
                background: 'rgba(239, 68, 68, 0.1)',
                border: '1px solid rgba(239, 68, 68, 0.3)',
                padding: '12px 16px',
                borderRadius: '8px',
                color: '#f87171',
                fontSize: '13px',
                marginBottom: '16px',
              }}
            >
              {reconError}
            </div>
          )}

          {/* Mismatch Records Table */}
          <div className="glass-panel" style={{ borderRadius: '12px', overflow: 'hidden' }}>
            <div style={{ padding: '16px 20px', borderBottom: '1px solid rgba(255, 255, 255, 0.08)' }}>
              <h4 style={{ fontSize: '15px', fontWeight: 700, margin: 0 }}>
                Hồ sơ lệch sổ & đối soát ({mismatches.length})
              </h4>
            </div>

            {isLoadingRecon ? (
              <div style={{ textAlign: 'center', padding: '40px', color: 'var(--text-muted)' }}>
                <RefreshCw size={24} className="spin" style={{ margin: '0 auto 8px' }} />
                <div>Đang tải dữ liệu đối soát...</div>
              </div>
            ) : mismatches.length === 0 ? (
              <div style={{ textAlign: 'center', padding: '48px', color: 'var(--text-muted)' }}>
                <CheckCircle2 size={36} color="#34d399" style={{ margin: '0 auto 10px', opacity: 0.8 }} />
                <h5 style={{ fontSize: '15px', fontWeight: 600, color: 'var(--text-secondary)', margin: '0 0 4px' }}>
                  Không có lệch số nào được phát hiện
                </h5>
                <p style={{ fontSize: '13px', margin: 0 }}>
                  Tất cả các giao dịch đã khớp trạng thái hoàn toàn với bản ghi thanh toán.
                </p>
              </div>
            ) : (
              <div style={{ overflowX: 'auto' }}>
                <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '13px' }}>
                  <thead>
                    <tr style={{ background: 'rgba(255, 255, 255, 0.03)', textAlign: 'left', color: 'var(--text-muted)' }}>
                      <th style={{ padding: '12px 16px' }}>Mã đối soát</th>
                      <th style={{ padding: '12px 16px' }}>Mã giao dịch</th>
                      <th style={{ padding: '12px 16px' }}>Cổng / Order ID</th>
                      <th style={{ padding: '12px 16px' }}>Trạng thái Local</th>
                      <th style={{ padding: '12px 16px' }}>Trạng thái Provider</th>
                      <th style={{ padding: '12px 16px' }}>Loại lệch số</th>
                      <th style={{ padding: '12px 16px' }}>Thời điểm</th>
                    </tr>
                  </thead>
                  <tbody>
                    {mismatches.map((m) => (
                      <tr key={m.id} style={{ borderBottom: '1px solid rgba(255, 255, 255, 0.05)' }}>
                        <td style={{ padding: '12px 16px', fontFamily: 'var(--font-mono)', fontSize: '12px' }}>
                          {m.id.slice(0, 8)}...
                        </td>
                        <td style={{ padding: '12px 16px', fontFamily: 'var(--font-mono)', fontSize: '12px' }}>
                          {m.paymentId.slice(0, 8)}...
                        </td>
                        <td style={{ padding: '12px 16px' }}>
                          <span style={{ fontWeight: 600 }}>{m.provider}</span>
                          <span style={{ display: 'block', fontSize: '11px', color: 'var(--text-muted)' }}>
                            {m.providerOrderId}
                          </span>
                        </td>
                        <td style={{ padding: '12px 16px' }}>
                          <span className="badge badge-pending" style={{ fontSize: '10px' }}>
                            {m.localStatus}
                          </span>
                        </td>
                        <td style={{ padding: '12px 16px' }}>
                          <span className="badge badge-rejected" style={{ fontSize: '10px' }}>
                            {m.providerStatus}
                          </span>
                        </td>
                        <td style={{ padding: '12px 16px', color: '#fbbf24', fontWeight: 600 }}>
                          {m.mismatchType || 'STATUS_MISMATCH'}
                        </td>
                        <td style={{ padding: '12px 16px', color: 'var(--text-muted)', fontSize: '12px' }}>
                          {new Date(m.checkedAt).toLocaleString('vi-VN')}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </div>
      )}

      {/* TAB 2: REFUNDS */}
      {activeTab === 'REFUNDS' && (
        <div>
          {/* Policy Gating Banner */}
          <div
            style={{
              background: 'rgba(245, 158, 11, 0.1)',
              border: '1px solid rgba(245, 158, 11, 0.35)',
              borderRadius: '12px',
              padding: '20px',
              marginBottom: '24px',
              display: 'flex',
              alignItems: 'flex-start',
              gap: '14px',
              color: '#fbbf24',
            }}
          >
            <ShieldAlert size={28} style={{ flexShrink: 0, marginTop: '2px' }} />
            <div>
              <h3 style={{ fontSize: '16px', fontWeight: 800, margin: '0 0 6px', color: '#fbbf24' }}>
                CHÍNH SÁCH HOÀN TIỀN: REFUND_POLICY_PENDING
              </h3>
              <p style={{ fontSize: '13px', color: 'var(--text-secondary)', margin: 0, lineHeight: 1.5 }}>
                Chính sách hoàn tiền (Refund Policy) và biểu phí hủy chuyến hiện đang trong giai đoạn xây dựng và chưa được phê duyệt chính thức. Theo nguyên tắc an toàn tài chính:
              </p>
              <ul style={{ fontSize: '12px', color: 'var(--text-muted)', margin: '8px 0 0', paddingLeft: '20px' }}>
                <li>Không cho phép tự động giải ngân hoàn tiền ra cổng thanh toán bên thứ ba.</li>
                <li>Giao dịch TIỀN MẶT (CASH) tuyệt đối không gửi lệnh MoMo Refund.</li>
                <li>Mọi hành động phê duyệt yêu cầu hoàn tiền đều phải tuân thủ quyền hạn `ROLE_FINANCE_STAFF` và chính sách ban hành.</li>
              </ul>
            </div>
          </div>

          {/* Lookup Input */}
          <div className="glass-panel" style={{ padding: '20px', borderRadius: '12px', marginBottom: '20px' }}>
            <h4 style={{ fontSize: '15px', fontWeight: 700, margin: '0 0 12px' }}>
              Tra cứu yêu cầu hoàn tiền theo Mã Giao Dịch (Payment ID)
            </h4>
            <form onSubmit={handleLookupRefunds} style={{ display: 'flex', gap: '10px' }}>
              <div style={{ flex: 1, position: 'relative' }}>
                <Search size={16} style={{ position: 'absolute', left: '12px', top: '12px', color: 'var(--text-muted)' }} />
                <input
                  type="text"
                  placeholder="Nhập Payment UUID (VD: 123e4567-e89b-12d3-a456-426614174000)..."
                  value={lookupPaymentId}
                  onChange={(e) => setLookupPaymentId(e.target.value)}
                  style={{
                    width: '100%',
                    padding: '10px 14px 10px 38px',
                    borderRadius: '8px',
                    border: '1px solid rgba(255, 255, 255, 0.1)',
                    background: 'rgba(0, 0, 0, 0.3)',
                    color: '#f8fafc',
                    fontSize: '13px',
                    fontFamily: 'var(--font-mono)',
                  }}
                />
              </div>
              <button
                type="submit"
                disabled={isLoadingRefunds || !lookupPaymentId.trim()}
                className="btn btn-primary"
                style={{ padding: '0 20px', fontWeight: 700 }}
              >
                {isLoadingRefunds ? <RefreshCw size={14} className="spin" /> : <span>Tra cứu</span>}
              </button>
            </form>

            {/* Eligibility result */}
            {eligibility && (
              <div
                style={{
                  marginTop: '16px',
                  padding: '14px',
                  borderRadius: '8px',
                  background: 'rgba(255, 255, 255, 0.03)',
                  border: '1px solid rgba(255, 255, 255, 0.06)',
                  fontSize: '13px',
                }}
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '4px' }}>
                  <strong>Điều kiện hoàn tiền: </strong>
                  <span className={eligibility.eligible ? 'badge badge-approved' : 'badge badge-rejected'}>
                    {eligibility.eligible ? 'ĐỦ ĐIỀU KIỆN' : 'KHÔNG ĐỦ ĐIỀU KIỆN'}
                  </span>
                </div>
                <div style={{ color: 'var(--text-muted)', fontSize: '12px' }}>
                  Lý do: {eligibility.reason || 'N/A'} | Số tiền tối đa: {eligibility.availableAmount?.toLocaleString('vi-VN')} {eligibility.currency} | Trạng thái: {eligibility.policyStatus}
                </div>
              </div>
            )}
          </div>

          {/* Refund Error Banner */}
          {refundError && (
            <div
              style={{
                background: 'rgba(239, 68, 68, 0.1)',
                border: '1px solid rgba(239, 68, 68, 0.3)',
                padding: '12px 16px',
                borderRadius: '8px',
                color: '#f87171',
                fontSize: '13px',
                marginBottom: '16px',
              }}
            >
              {refundError}
            </div>
          )}

          {/* Action notification */}
          {refundActionMsg && (
            <div
              style={{
                background: refundActionMsg.error ? 'rgba(239, 68, 68, 0.1)' : 'rgba(16, 185, 129, 0.1)',
                border: `1px solid ${refundActionMsg.error ? 'rgba(239, 68, 68, 0.3)' : 'rgba(16, 185, 129, 0.3)'}`,
                padding: '12px 16px',
                borderRadius: '8px',
                color: refundActionMsg.error ? '#f87171' : '#34d399',
                fontSize: '13px',
                marginBottom: '16px',
              }}
            >
              {refundActionMsg.text}
            </div>
          )}

          {/* Refund List */}
          <div className="glass-panel" style={{ borderRadius: '12px', overflow: 'hidden' }}>
            <div style={{ padding: '16px 20px', borderBottom: '1px solid rgba(255, 255, 255, 0.08)' }}>
              <h4 style={{ fontSize: '15px', fontWeight: 700, margin: 0 }}>
                Danh sách yêu cầu hoàn tiền ({refunds.length})
              </h4>
            </div>

            {isLoadingRefunds ? (
              <div style={{ textAlign: 'center', padding: '40px', color: 'var(--text-muted)' }}>
                <RefreshCw size={24} className="spin" style={{ margin: '0 auto 8px' }} />
                <div>Đang tra cứu hồ sơ hoàn tiền...</div>
              </div>
            ) : refunds.length === 0 ? (
              <div style={{ textAlign: 'center', padding: '48px', color: 'var(--text-muted)' }}>
                <FileText size={36} style={{ margin: '0 auto 10px', opacity: 0.5 }} />
                <p>Chưa có yêu cầu hoàn tiền nào cho giao dịch này.</p>
              </div>
            ) : (
              <div style={{ overflowX: 'auto' }}>
                <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '13px' }}>
                  <thead>
                    <tr style={{ background: 'rgba(255, 255, 255, 0.03)', textAlign: 'left', color: 'var(--text-muted)' }}>
                      <th style={{ padding: '12px 16px' }}>Mã hoàn tiền</th>
                      <th style={{ padding: '12px 16px' }}>Số tiền</th>
                      <th style={{ padding: '12px 16px' }}>Lý do</th>
                      <th style={{ padding: '12px 16px' }}>Trạng thái</th>
                      <th style={{ padding: '12px 16px' }}>Người yêu cầu</th>
                      <th style={{ padding: '12px 16px' }}>Thời gian</th>
                      <th style={{ padding: '12px 16px', textAlign: 'right' }}>Thao tác</th>
                    </tr>
                  </thead>
                  <tbody>
                    {refunds.map((r) => (
                      <tr key={r.refundId} style={{ borderBottom: '1px solid rgba(255, 255, 255, 0.05)' }}>
                        <td style={{ padding: '12px 16px', fontFamily: 'var(--font-mono)', fontSize: '12px' }}>
                          {r.refundId.slice(0, 8)}...
                        </td>
                        <td style={{ padding: '12px 16px', fontWeight: 700, color: '#38bdf8' }}>
                          {r.amount.toLocaleString('vi-VN')} {r.currency}
                        </td>
                        <td style={{ padding: '12px 16px' }}>{r.reason}</td>
                        <td style={{ padding: '12px 16px' }}>
                          <span
                            className={
                              r.status === 'SUCCEEDED'
                                ? 'badge badge-approved'
                                : r.status === 'REJECTED' || r.status === 'FAILED'
                                ? 'badge badge-rejected'
                                : 'badge badge-pending'
                            }
                            style={{ fontSize: '10px' }}
                          >
                            {r.status}
                          </span>
                        </td>
                        <td style={{ padding: '12px 16px', color: 'var(--text-muted)', fontSize: '12px' }}>
                          {r.requestedBy}
                        </td>
                        <td style={{ padding: '12px 16px', color: 'var(--text-muted)', fontSize: '12px' }}>
                          {new Date(r.createdAt).toLocaleString('vi-VN')}
                        </td>
                        <td style={{ padding: '12px 16px', textAlign: 'right' }}>
                          {r.status === 'PENDING' && (
                            <button
                              onClick={() => handleApproveRefund(r.refundId)}
                              className="btn btn-primary btn-sm"
                              style={{ fontSize: '11px', padding: '4px 8px' }}
                            >
                              Duyệt (Approve)
                            </button>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
};
