import { useEffect, useMemo, useState } from 'react';
import { useAuth } from '../auth';
import { api } from '../api';
import type { DeliveryResponse } from '../types';

type SortKey = 'orderId' | 'createdAt';
type SortDir = 'asc' | 'desc';

export default function DeliveryManagement() {
  const { isAdmin, isManager } = useAuth();
  const [deliveries, setDeliveries] = useState<DeliveryResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionLoading, setActionLoading] = useState<number | null>(null);
  const [filterStatus, setFilterStatus] = useState('');
  const [dateFrom, setDateFrom] = useState('');
  const [dateTo, setDateTo] = useState('');
  const [sortKey, setSortKey] = useState<SortKey>('orderId');
  const [sortDir, setSortDir] = useState<SortDir>('desc');

  const canManage = isAdmin || isManager;

  const fetchDeliveries = () => {
    setLoading(true);
    setError(null);
    api.getDeliveries()
      .then(setDeliveries)
      .catch(e => setError(e.message))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    if (canManage) fetchDeliveries();
  }, [canManage]);

  const filtered = useMemo(() => {
    let result = [...deliveries];

    if (filterStatus) {
      result = result.filter(d => d.status === filterStatus);
    }
    if (dateFrom) {
      const from = new Date(dateFrom).getTime();
      result = result.filter(d => new Date(d.createdAt).getTime() >= from);
    }
    if (dateTo) {
      const to = new Date(dateTo).getTime() + 86400000;
      result = result.filter(d => new Date(d.createdAt).getTime() < to);
    }

    result.sort((a, b) => {
      let cmp: number;
      if (sortKey === 'orderId') {
        cmp = a.orderId - b.orderId;
      } else {
        cmp = new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime();
      }
      return sortDir === 'asc' ? cmp : -cmp;
    });

    return result;
  }, [deliveries, filterStatus, dateFrom, dateTo, sortKey, sortDir]);

  const toggleSort = (key: SortKey) => {
    if (sortKey === key) {
      setSortDir(d => d === 'asc' ? 'desc' : 'asc');
    } else {
      setSortKey(key);
      setSortDir('asc');
    }
  };

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

      <div style={{ display: 'flex', gap: 12, alignItems: 'center', flexWrap: 'wrap', marginBottom: 16 }}>
        <label>Status:</label>
        <select value={filterStatus} onChange={e => setFilterStatus(e.target.value)}
          style={{ padding: '4px 8px', borderRadius: 4, border: '1px solid var(--border)' }}>
          <option value="">All</option>
          <option value="PENDING">PENDING</option>
          <option value="DISPATCHED">DISPATCHED</option>
          <option value="DELIVERED">DELIVERED</option>
          <option value="FAILED">FAILED</option>
        </select>

        <label>From:</label>
        <input type="date" value={dateFrom} onChange={e => setDateFrom(e.target.value)}
          style={{ padding: '4px 8px', borderRadius: 4, border: '1px solid var(--border)' }} />

        <label>To:</label>
        <input type="date" value={dateTo} onChange={e => setDateTo(e.target.value)}
          style={{ padding: '4px 8px', borderRadius: 4, border: '1px solid var(--border)' }} />

        <button className="btn" onClick={fetchDeliveries} style={{ padding: '4px 12px' }}>Refresh</button>
      </div>

      <div style={{ display: 'flex', gap: 12, alignItems: 'center', marginBottom: 12, fontSize: '0.85rem' }}>
        <span style={{ color: 'var(--text-secondary)' }}>Sort by:</span>
        <button className="btn" onClick={() => toggleSort('orderId')}
          style={{ padding: '2px 8px', fontSize: '0.85rem' }}>
          Order # {sortKey === 'orderId' ? (sortDir === 'asc' ? '↑' : '↓') : ''}
        </button>
        <button className="btn" onClick={() => toggleSort('createdAt')}
          style={{ padding: '2px 8px', fontSize: '0.85rem' }}>
          Date {sortKey === 'createdAt' ? (sortDir === 'asc' ? '↑' : '↓') : ''}
        </button>
        <span style={{ color: 'var(--text-secondary)' }}>{filtered.length} deliveries</span>
      </div>

      {error && <p style={{ color: 'var(--danger)' }}>{error}</p>}

      {loading ? (
        <p>Loading deliveries...</p>
      ) : filtered.length === 0 ? (
        <p>No deliveries found.</p>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
          {filtered.map(d => (
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
                  <span style={{ marginLeft: 8, fontSize: '0.8rem', color: 'var(--text-secondary)' }}>
                    {new Date(d.createdAt).toLocaleDateString()}
                  </span>
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
