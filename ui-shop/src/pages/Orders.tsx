import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { api } from '../api';
import type { OrderResponse } from '../types';
import { useAuth } from '../auth';

export default function Orders() {
  const [orders, setOrders] = useState<OrderResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const { isAuthenticated } = useAuth();

  useEffect(() => {
    if (!isAuthenticated) {
      setLoading(false);
      return;
    }
    api.getOrders()
      .then(r => setOrders(r.items))
      .finally(() => setLoading(false));
  }, [isAuthenticated]);

  if (!isAuthenticated) {
    return (
      <div className="page">
        <h1>My Orders</h1>
        <p>Please log in to view your orders.</p>
        <Link to="/login" className="btn">Login</Link>
      </div>
    );
  }

  if (loading) return <div className="page"><p>Loading...</p></div>;

  return (
    <div className="page orders-page">
      <h1>My Orders</h1>
      {orders.length === 0 ? (
        <p>No orders yet.</p>
      ) : (
        <table className="orders-table">
          <thead>
            <tr>
              <th>#</th>
              <th>Status</th>
              <th>Total</th>
              <th>Date</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {orders.map(o => (
              <tr key={o.id}>
                <td>{o.id}</td>
                <td><span className={`status status-${o.status.toLowerCase()}`}>{o.status}</span></td>
                <td>${Number(o.total).toFixed(2)}</td>
                <td>{new Date(o.createdAt).toLocaleDateString()}</td>
                <td><Link to={`/orders/${o.id}`}>View</Link></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
