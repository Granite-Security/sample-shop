import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router';
import { useAuth } from '../auth';
import { api } from '../api';
import type { DeliveryResponse } from '../types';

type SortKey = 'orderId' | 'createdAt';
type SortDir = 'asc' | 'desc';

const PAGE_SIZE = 20;

export default function DeliveryManagement() {
  const { isAdmin, isManager } = useAuth();
  const [deliveries, setDeliveries] = useState<DeliveryResponse[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionLoading, setActionLoading] = useState<number | null>(null);
  const [filterStatus, setFilterStatus] = useState('');
  const [dateFrom, setDateFrom] = useState('');
  const [dateTo, setDateTo] = useState('');
  const [sortKey, setSortKey] = useState<SortKey>('orderId');
  const [sortDir, setSortDir] = useState<SortDir>('desc');

  const canManage = isAdmin || isManager;
  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  const fetchDeliveries = useCallback(() => {
    setLoading(true);
    setError(null);
    api.delivery.getDeliveries({
      status: filterStatus || undefined,
      from: dateFrom || undefined,
      to: dateTo || undefined,
      sort: sortKey,
      dir: sortDir,
      page,
      size: PAGE_SIZE,
    })
      .then(result => {
        setDeliveries(result.items);
        setTotal(result.total);
      })
      .catch(e => setError(e.message))
      .finally(() => setLoading(false));
  }, [filterStatus, dateFrom, dateTo, sortKey, sortDir, page]);

  useEffect(() => {
    if (canManage) fetchDeliveries();
  }, [canManage, fetchDeliveries]);

  /**
   * Any change to what is being asked for sends the reader back to page 0. Page 3
   * of the old filter is rarely page 3 of the new one, and can be past the end.
   */
  const applyFilter = <T,>(set: (value: T) => void) => (value: T) => {
    set(value);
    setPage(0);
  };

  const toggleSort = (key: SortKey) => {
    if (sortKey === key) {
      setSortDir(d => d === 'asc' ? 'desc' : 'asc');
    } else {
      setSortKey(key);
      setSortDir('asc');
    }
    setPage(0);
  };

  const updateStatus = (orderId: number, status: string, description: string) => {
    setActionLoading(orderId);
    api.delivery.updateDeliveryStatus(orderId, status, description)
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
        <select value={filterStatus} onChange={e => applyFilter(setFilterStatus)(e.target.value)}
          style={{ padding: '4px 8px', borderRadius: 4, border: '1px solid var(--border)' }}>
          <option value="">All</option>
          <option value="PENDING">PENDING</option>
          <option value="DISPATCHED">DISPATCHED</option>
          <option value="DELIVERED">DELIVERED</option>
          <option value="FAILED">FAILED</option>
        </select>

        <label>From:</label>
        <input type="date" value={dateFrom} onChange={e => applyFilter(setDateFrom)(e.target.value)}
          style={{ padding: '4px 8px', borderRadius: 4, border: '1px solid var(--border)' }} />

        <label>To:</label>
        <input type="date" value={dateTo} onChange={e => applyFilter(setDateTo)(e.target.value)}
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
        <span style={{ color: 'var(--text-secondary)' }}>{total} deliveries</span>
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
                <Link to={`/orders/${d.orderId}`} className="btn">View Order</Link>
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

      {total > PAGE_SIZE && (
        <div style={{ display: 'flex', gap: 12, alignItems: 'center', marginTop: 16 }}>
          <button className="btn" disabled={page === 0 || loading}
            onClick={() => setPage(p => Math.max(0, p - 1))}>
            Previous
          </button>
          <span style={{ color: 'var(--text-secondary)', fontSize: '0.85rem' }}>
            Page {page + 1} of {totalPages}
          </span>
          <button className="btn" disabled={page + 1 >= totalPages || loading}
            onClick={() => setPage(p => p + 1)}>
            Next
          </button>
        </div>
      )}
    </div>
  );
}
