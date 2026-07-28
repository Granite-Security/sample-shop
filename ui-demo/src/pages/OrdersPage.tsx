import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { api } from '../api';
import { formatPrice } from '../store';
import type { OrderResponse } from '../types';

const statusStyle: Record<string, string> = {
  PENDING: 'bg-gold/15 text-cocoa',
  PROCESSING: 'bg-gold/15 text-cocoa',
  PAID: 'bg-sage/15 text-cocoa',
  SHIPPED: 'bg-sage/15 text-cocoa',
  DELIVERED: 'bg-sage/15 text-cocoa',
  CANCELLED: 'bg-terracotta/15 text-terracotta',
  RETURNED: 'bg-terracotta/15 text-terracotta',
  REIMBURSED: 'bg-terracotta/15 text-terracotta',
};

function StatusBadge({ status }: { status: string }) {
  return (
    <span
      className={`inline-block rounded-full px-2.5 py-0.5 text-[10px] uppercase tracking-[0.12em] ${
        statusStyle[status] ?? 'bg-cocoa/10 text-cocoa'
      }`}
    >
      {status}
    </span>
  );
}

export function OrdersPage() {
  const [orders, setOrders] = useState<OrderResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api
      .getOrders()
      .then((r) => setOrders(r.items))
      .catch((err) => setError(err instanceof Error ? err.message : String(err)))
      .finally(() => setLoading(false));
  }, []);

  return (
    <div className="bg-ivory pt-28 lg:pt-32">
      <div className="mx-auto max-w-3xl px-5 pb-24 lg:px-8">
        <p className="text-xs uppercase tracking-[0.3em] text-terracotta">Your Account</p>
        <h1 className="mt-3 font-display text-[36px] leading-tight text-cocoa lg:text-[48px]">My Orders</h1>

        <section aria-label="Orders" className="mt-10">
          {loading ? (
            <p className="text-sm text-cocoa/50">Loading…</p>
          ) : error ? (
            <p className="border-l-2 border-terracotta bg-terracotta/10 px-4 py-3 text-sm text-terracotta">{error}</p>
          ) : orders.length === 0 ? (
            <>
              <p className="text-sm text-cocoa/50">No orders yet.</p>
              <Link
                to="/#bestsellers"
                className="mt-6 inline-block border border-cocoa px-8 py-3 text-xs uppercase tracking-[0.18em] text-cocoa transition-colors duration-300 hover:bg-cocoa hover:text-ivory"
              >
                Browse the Collection
              </Link>
            </>
          ) : (
            <ul className="divide-y divide-cocoa/10 border-y border-cocoa/10">
              {orders.map((order) => (
                <li key={order.id} className="flex items-center justify-between gap-4 py-4">
                  <div>
                    <p className="text-cocoa">
                      Order <span className="font-display">#{order.id}</span>
                    </p>
                    <p className="mt-1 text-sm text-cocoa/50">
                      {new Date(order.createdAt).toLocaleDateString()} · {formatPrice(order.total)}
                    </p>
                  </div>
                  <div className="flex shrink-0 items-center gap-4">
                    <StatusBadge status={order.status} />
                    <Link
                      to={`/profile/orders/${order.id}`}
                      className="text-xs uppercase tracking-[0.14em] text-cocoa underline decoration-gold underline-offset-4 hover:text-terracotta"
                    >
                      View
                    </Link>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>
    </div>
  );
}
