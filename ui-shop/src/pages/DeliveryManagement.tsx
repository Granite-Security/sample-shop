import { useEffect, useState } from 'react';
import { useAuth } from '../auth';
import { api } from '../api';
import type { DeliveryResponse } from '../types';

export default function DeliveryManagement() {
  const { isAdmin, isManager } = useAuth();
  const [deliveries, setDeliveries] = useState<DeliveryResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionLoading, setActionLoading] = useState<number | null>(null);
  const [filterStatus, setFilterStatus] = useState('');

  const canManage = isAdmin || isManager;

  const fetchDeliveries = () => {
    setLoading(true);
    setError(null);
    const params = filterStatus ? `?status=${filterStatus}` : '';
    api.getDeliveries(params)
      .then(setDeliveries)
      .catch(e => setError(e.message))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    if (canManage) fetchDeliveries();
  }, [canManage, filterStatus]);

  const updateStatus = (orderId: number, status: string, description: string) => {
    setActionLoading(orderId);
    api.updateDeliveryStatus(orderId, status, description)
      .then(() => fetchDeliveries())
      .catch(e => setError(e.message))
      .finally(() => setActionLoading(null));
  };

  if (!canManage) {
    return (
      <div className="page">
        <h1>Access Denied</h1>
        <p>You do not have permission to manage deliveries.</p>
      </div>
    );
  }

  return (
    <div className="page">
      <h1>Delivery Management</h1>

      <div style={{ display: 'flex', gap: 12, alignItems: 'center', marginBottom: 16 }}>
        <label>Filter by status:</label>
        <select value={filterStatus} onChange={e => setFilterStatus(e.target.value)}
          style={{ padding: '4px 8px', borderRadius: 4, border: '1px solid var(--border)' }}>
          <option value="">All</option>
          <option value="PENDING">PENDING</option>
          <option value="DISPATCHED">DISPATCHED</option>
          <option value="DELIVERED">DELIVERED</option>
          <option value="FAILED">FAILED</option>
        </select>
        <button className="btn" onClick={fetchDeliveries} style={{ padding: '4px 12px' }}>Refresh</button>
      </div>

      {error && <p style={{ color: 'var(--danger)' }}>{error}</p>}

      {loading ? (
        <p>Loading deliveries...</p>
      ) : deliveries.length === 0 ? (
        <p>No deliveries found.</p>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
          {deliveries.map(d => (
            <div key={d.id} style={{
              padding: 16, background: 'var(--surface)', borderRadius: 8,
              border: '1px solid var(--border)',
            }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
                <div>
                  <strong>Order #{d.orderId}</strong>
                  <span className={`status status-${d.status.toLowerCase()}`}
                    style={{ marginLeft: 8 }}>{d.status}</span>
                  <span className={`status status-${d.paymentStatus.toLowerCase()}`}
                    style={{ marginLeft: 4 }}>{d.paymentStatus}</span>
                </div>
                {actionLoading === d.orderId && <span>Updating...</span>}
              </div>
              <p style={{ margin: '8px 0 4px', fontSize: '0.9rem' }}>
                {d.recipientName} — {d.addressLine1}{d.city ? `, ${d.city}` : ''}{d.country ? `, ${d.country}` : ''}
              </p>
              {d.items && <p style={{ margin: '4px 0', fontSize: '0.85rem', color: 'var(--text-secondary)' }}>Items: {d.items}</p>}

              <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
                {d.status === 'PENDING' && (
                  <button className="btn" onClick={() => updateStatus(d.orderId, 'DISPATCHED', 'Order dispatched')}
                    disabled={actionLoading === d.orderId}>
                    Mark as Dispatched
                  </button>
                )}
                {d.status === 'DISPATCHED' && (
                  <>
                    <button className="btn" onClick={() => updateStatus(d.orderId, 'DELIVERED', 'Order delivered successfully')}
                      disabled={actionLoading === d.orderId}>
                      Mark as Delivered
                    </button>
                    <button className="btn" style={{ background: '#e74c3c', color: '#fff' }}
                      onClick={() => updateStatus(d.orderId, 'FAILED', 'Delivery failed')}
                      disabled={actionLoading === d.orderId}>
                      Mark as Failed
                    </button>
                  </>
                )}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
