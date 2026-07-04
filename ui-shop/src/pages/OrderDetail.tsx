import { useEffect, useState, useRef } from 'react';
import { useParams, Link } from 'react-router';
import { api } from '../api';
import type { OrderResponse } from '../types';

const POLL_INTERVAL = 5000;

export default function OrderDetail() {
  const { id } = useParams();
  const [order, setOrder] = useState<OrderResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => {
    if (!id) return;
    let cancelled = false;

    const fetch = () => {
      api.getOrder(Number(id))
        .then(o => {
          if (cancelled) return;
          setOrder(o);
          setLoading(false);
          if (o.status !== 'PENDING' && o.status !== 'PROCESSING') {
            if (pollRef.current) {
              clearInterval(pollRef.current);
              pollRef.current = null;
            }
          }
        })
        .catch(() => {
          if (!cancelled) setOrder(null);
          setLoading(false);
        });
    };

    fetch();
    pollRef.current = setInterval(fetch, POLL_INTERVAL);

    return () => {
      cancelled = true;
      if (pollRef.current) {
        clearInterval(pollRef.current);
        pollRef.current = null;
      }
    };
  }, [id]);

  if (loading) return (
    <div className="page" style={{ textAlign: 'center', paddingTop: '3rem' }}>
      <div className="spinner" style={{ margin: '0 auto 1rem' }} />
      <p>Loading order...</p>
    </div>
  );
  if (!order) return <div className="page"><p>Order not found.</p></div>;

  const statusClass = `status status-${order.status.toLowerCase()}`;

  return (
    <div className="page order-detail-page">
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16 }}>
        <h1 style={{ margin: 0 }}>Order #{order.id}</h1>
        <span className={statusClass}>{order.status}</span>
        {(order.status === 'PENDING' || order.status === 'PROCESSING') && (
          <span style={{ fontSize: '0.8rem', color: 'var(--text-secondary)' }}>
            (refreshing…)
          </span>
        )}
      </div>
      <p>Placed: {new Date(order.createdAt).toLocaleString()}</p>
      <p>Total: <strong>${Number(order.total).toFixed(2)}</strong></p>
      <h2 style={{ marginTop: 24 }}>Items</h2>
      <table className="order-items-table">
        <thead>
          <tr>
            <th>Product ID</th>
            <th>Qty</th>
            <th>Unit Price</th>
            <th>Subtotal</th>
          </tr>
        </thead>
        <tbody>
          {order.items.map(item => (
            <tr key={item.id}>
              <td>{item.productId}</td>
              <td>{item.quantity}</td>
              <td>${Number(item.unitPrice).toFixed(2)}</td>
              <td>${(Number(item.unitPrice) * item.quantity).toFixed(2)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <Link to="/orders" className="btn" style={{ marginTop: 16 }}>Back to Orders</Link>
    </div>
  );
}
