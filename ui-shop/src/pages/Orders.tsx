import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { api } from '../api';
import type { DeliveryResponse, OrderResponse } from '../types';

export default function Orders() {
  const [orders, setOrders] = useState<OrderResponse[]>([]);
  const [deliveryMap, setDeliveryMap] = useState<Record<number, DeliveryResponse>>({});
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    // Deliveries are fetched one per displayed order rather than by listing every
    // delivery in the system and picking ours out of it. The list endpoint is
    // paginated now, so the old bulk call would have quietly covered only the first
    // page — and it was always the wrong shape for this page, which needs exactly
    // the orders it is showing and nothing else.
    api.orders.getOrders()
      .then(r => r.items)
      .then(async userOrders => {
        setOrders(userOrders);
        const found = await Promise.all(userOrders.map(o => api.delivery.getDelivery(o.id)));
        const map: Record<number, DeliveryResponse> = {};
        found.forEach(d => { if (d) map[d.orderId] = d; });
        setDeliveryMap(map);
      })
      .finally(() => setLoading(false));
  }, []);

  if (loading) return <p>Loading...</p>;

  return (
    <div className="orders-page">
      <h1>My Orders</h1>
      {orders.length === 0 ? (
        <p>No orders yet.</p>
      ) : (
        <table className="orders-table">
          <thead>
            <tr>
              <th>#</th>
              <th>Status</th>
              <th>Delivery</th>
              <th>Total</th>
              <th>Date</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {orders.map(o => {
              const d = deliveryMap[o.id];
              return (
                <tr key={o.id}>
                  <td>{o.id}</td>
                  <td><span className={`status status-${o.status.toLowerCase()}`}>{o.status}</span></td>
                  <td>{d ? (
                    <span className={`status status-${d.status.toLowerCase()}`}>{d.status}</span>
                  ) : (
                    <span style={{ color: 'var(--text-secondary)' }}>—</span>
                  )}</td>
                  <td>
                    ${Number(o.total).toFixed(2)}
                    {Number(o.discountTotal ?? 0) > 0 && (
                      <span style={{ color: 'var(--text-secondary)', fontSize: '0.85em' }}>
                        {' '}({o.voucherCode})
                      </span>
                    )}
                  </td>
                  <td>{new Date(o.createdAt).toLocaleDateString()}</td>
                  <td><Link to={`/orders/${o.id}`}>View</Link></td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
    </div>
  );
}
