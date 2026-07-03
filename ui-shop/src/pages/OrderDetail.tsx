import { useEffect, useState } from 'react';
import { useParams, Link } from 'react-router';
import { api } from '../api';
import type { OrderResponse } from '../types';

export default function OrderDetail() {
  const { id } = useParams();
  const [order, setOrder] = useState<OrderResponse | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!id) return;
    api.getOrder(Number(id))
      .then(setOrder)
      .catch(() => setOrder(null))
      .finally(() => setLoading(false));
  }, [id]);

  if (loading) return <div className="page"><p>Loading...</p></div>;
  if (!order) return <div className="page"><p>Order not found.</p></div>;

  return (
    <div className="page order-detail-page">
      <h1>Order #{order.id}</h1>
      <p>Status: <span className={`status status-${order.status.toLowerCase()}`}>{order.status}</span></p>
      <p>Placed: {new Date(order.createdAt).toLocaleString()}</p>
      <p>Total: <strong>${Number(order.total).toFixed(2)}</strong></p>
      <h2>Items</h2>
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
