import { useEffect, useState, useRef } from 'react';
import { useParams, Link } from 'react-router';
import { api } from '../api';
import { useAuth } from '../auth';
import type { OrderResponse, CreatePaymentIntentResponse, DeliveryResponse, TrackingDetailResponse } from '../types';

const POLL_INTERVAL = 5000;

export default function OrderDetail() {
  const { id } = useParams();
  const { isAdmin } = useAuth();
  const [order, setOrder] = useState<OrderResponse | null>(null);
  const [payment, setPayment] = useState<CreatePaymentIntentResponse | null>(null);
  const [delivery, setDelivery] = useState<DeliveryResponse | null>(null);
  const [tracking, setTracking] = useState<TrackingDetailResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [refunding, setRefunding] = useState(false);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const fetchRef = useRef<() => void>(() => {});

  useEffect(() => {
    if (!id) return;
    let cancelled = false;

    const fetch = () => {
      const orderId = Number(id);
      Promise.all([
        api.orders.getOrder(orderId),
        api.payments.getPaymentIntent(orderId).catch(() => null),
        api.delivery.getDelivery(orderId),
        api.delivery.getDeliveryTracking(orderId),
      ])
        .then(([o, p, d, t]) => {
          if (cancelled) return;
          setOrder(o);
          setPayment(p);
          setDelivery(d);
          setTracking(t);
          setLoading(false);
          if (o.status === 'RETURNED') {
            // No Stripe webhooks in this stack — sync reconciles the refund state.
            api.payments.syncPaymentIntent(orderId).catch(() => null);
          }
          if (o.status !== 'PENDING' && o.status !== 'PROCESSING' && o.status !== 'RETURNED') {
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

    fetchRef.current = fetch;
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
  const conditionsMet = payment?.status === 'SUCCEEDED' && delivery?.status === 'FAILED';

  const handleRefund = () => {
    if (!window.confirm(`Refund the full order total of $${Number(order.total).toFixed(2)}? This cannot be undone.`)) return;
    setRefunding(true);
    api.orders.refundOrder(order.id)
      .then(o => {
        setError(null);
        setOrder(o);
        fetchRef.current();
      })
      .catch(e => setError(e.message))
      .finally(() => setRefunding(false));
  };

  return (
    <div className="page order-detail-page">
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16 }}>
        <h1 style={{ margin: 0 }}>Order #{order.id}</h1>
        <span className={statusClass}>{order.status}</span>
        {delivery && (
          <span className={`status status-${delivery.status.toLowerCase()}`}>
            Delivery: {delivery.status}
          </span>
        )}
        {(order.status === 'PENDING' || order.status === 'PROCESSING' || order.status === 'RETURNED') && (
          <span style={{ fontSize: '0.8rem', color: 'var(--text-secondary)' }}>
            (refreshing…)
          </span>
        )}
      </div>
      {payment && (
        <p>
          Payment: <span className={`status status-${payment.status.toLowerCase()}`}>{payment.status}</span>
        </p>
      )}
      <p>Placed: {new Date(order.createdAt).toLocaleString()}</p>
      <p>Total: <strong>${Number(order.total).toFixed(2)}</strong></p>

      {order.address && (
        <>
          <h3 style={{ marginTop: 24 }}>Delivery Address</h3>
          <p>{order.address.recipientName}</p>
          <p>{order.address.addressLine1}{order.address.addressLine2 ? `, ${order.address.addressLine2}` : ''}</p>
          <p>{order.address.city}{order.address.state ? `, ${order.address.state}` : ''} {order.address.zipCode}</p>
          <p>{order.address.country}</p>
        </>
      )}

      {delivery && (
        <>
          <h3 style={{ marginTop: 24 }}>Delivery</h3>
          <p>
            Status: <span className={`status status-${delivery.status.toLowerCase()}`}>{delivery.status}</span>
          </p>
          <p>
            Payment: <span className={`status status-${delivery.paymentStatus.toLowerCase()}`}>{delivery.paymentStatus}</span>
          </p>
          {delivery.items && <p>Items: {delivery.items}</p>}
          {delivery.estimatedDeliveryDate && (
            <p>Estimated delivery: {new Date(delivery.estimatedDeliveryDate).toLocaleDateString()}</p>
          )}

          {tracking && tracking.events.length > 0 && (
            <>
              <h4 style={{ marginTop: 16 }}>Tracking Timeline</h4>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                {tracking.events.map((event, i) => (
                  <div key={i} style={{
                    display: 'flex', gap: 12, alignItems: 'flex-start',
                    padding: '8px 12px', background: 'var(--surface)', borderRadius: 8,
                  }}>
                    <div style={{
                      width: 10, height: 10, borderRadius: '50%',
                      background: 'var(--primary)', marginTop: 6, flexShrink: 0,
                    }} />
                    <div>
                      <span className={`status status-${event.status.toLowerCase()}`}
                        style={{ fontSize: '0.8rem' }}>{event.status}</span>
                      <p style={{ margin: '4px 0' }}>{event.description}</p>
                      <p style={{ fontSize: '0.8rem', color: 'var(--text-secondary)' }}>
                        {new Date(event.timestamp).toLocaleString()}
                      </p>
                    </div>
                  </div>
                ))}
              </div>
            </>
          )}
        </>
      )}

      <h2 style={{ marginTop: 24 }}>Items</h2>
      <table className="order-items-table">
        <thead>
          <tr>
            <th>Product</th>
            <th>Qty</th>
            <th>Unit Price</th>
            <th>Subtotal</th>
          </tr>
        </thead>
        <tbody>
          {order.items.map(item => (
            <tr key={item.id}>
              <td><Link to={`/catalog/${item.productId}`}>{item.productName}</Link></td>
              <td>{item.quantity}</td>
              <td>${Number(item.unitPrice).toFixed(2)}</td>
              <td>${(Number(item.unitPrice) * item.quantity).toFixed(2)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {error && <p style={{ color: 'var(--danger)' }}>{error}</p>}
      <div style={{ display: 'flex', gap: 12, marginTop: 16, alignItems: 'center' }}>
        <Link to="/orders" className="btn">Back to Orders</Link>
        {payment && payment.status !== 'SUCCEEDED' && !TERMINAL_ORDER_STATUSES.includes(order.status) && (
          <Link to={`/orders/${order.id}/pay`} className="btn">Retry Payment</Link>
        )}
        {payment?.refund?.status === 'SUCCEEDED' && (
          <span style={{ fontSize: '0.9rem', color: 'var(--text-secondary)' }}>
            Refunded ${Number(payment.refund.amount).toFixed(2)} on {new Date(payment.refund.createdAt).toLocaleDateString()}
          </span>
        )}
        {payment?.refund?.status !== 'SUCCEEDED' && (order.status === 'RETURNED' || !!payment?.refund) && (
          <button className="btn" disabled style={{ background: '#e74c3c', color: '#fff', opacity: 0.7 }}>
            Refund processing…
          </button>
        )}
        {payment && !payment.refund && order.status !== 'RETURNED' && order.status !== 'REIMBURSED' && (isAdmin || conditionsMet) && (
          <button className="btn" style={{ background: '#e74c3c', color: '#fff' }}
            onClick={handleRefund} disabled={refunding}>
            {refunding ? 'Refunding…' : 'Refund'}
          </button>
        )}
      </div>
    </div>
  );
}

const TERMINAL_ORDER_STATUSES = ['SHIPPED', 'DELIVERED', 'CANCELLED', 'RETURNED', 'REIMBURSED'];
