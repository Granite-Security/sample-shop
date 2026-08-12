import { useEffect, useRef, useState } from 'react';
import { Link, useParams } from 'react-router';
import { api } from '../api';
import { formatPrice } from '../store';
import type { CreatePaymentIntentResponse, OrderResponse } from '../types';

const POLL_INTERVAL = 5000;
const POLLED_STATUSES = ['PENDING', 'PROCESSING'];

export function OrderDetailPage() {
  const { id } = useParams();
  const [order, setOrder] = useState<OrderResponse | null>(null);
  const [payment, setPayment] = useState<CreatePaymentIntentResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [notFound, setNotFound] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => {
    const orderId = Number(id);
    if (!id || Number.isNaN(orderId)) return;
    let cancelled = false;

    const fetchOrder = () => {
      api
        .getOrder(orderId)
        .then((o) => {
          if (cancelled) return;
          setOrder(o);
          setNotFound(false);
          setLoadError(null);
          setLoading(false);
          return api
            .getPaymentIntent(orderId)
            .catch(() => null)
            .then((p) => {
              if (cancelled) return;
              setPayment(p);
              if (!POLLED_STATUSES.includes(o.status) && pollRef.current) {
                clearInterval(pollRef.current);
                pollRef.current = null;
              }
            });
        })
        .catch((err) => {
          if (cancelled) return;
          const message = err instanceof Error ? err.message : String(err);
          if (/^\[404\]/.test(message)) {
            setNotFound(true);
            if (pollRef.current) {
              clearInterval(pollRef.current);
              pollRef.current = null;
            }
          } else {
            setLoadError(message);
          }
          setLoading(false);
        });
    };

    fetchOrder();
    pollRef.current = setInterval(fetchOrder, POLL_INTERVAL);

    return () => {
      cancelled = true;
      if (pollRef.current) {
        clearInterval(pollRef.current);
        pollRef.current = null;
      }
    };
  }, [id]);

  return (
    <div>
      <p className="text-xs uppercase tracking-[0.3em] text-terracotta">Your Account</p>
      <h1 className="mt-3 font-display text-[36px] leading-tight text-cocoa lg:text-[48px]">
        Order #{id}
      </h1>

      <section aria-label="Order detail" className="mt-10">
        {loading ? (
          <p className="text-sm text-cocoa/50">Loading…</p>
        ) : notFound ? (
          <p className="text-sm text-cocoa/50">Order not found.</p>
        ) : !order ? (
          <p className="border-l-2 border-terracotta bg-terracotta/10 px-4 py-3 text-sm text-terracotta">
            {loadError ?? "Couldn't load this order."}
          </p>
        ) : (
          <>
            <div className="flex flex-wrap items-center gap-3">
              <span className="inline-block rounded-full bg-cocoa/10 px-2.5 py-0.5 text-[10px] uppercase tracking-[0.12em] text-cocoa">
                {order.status}
              </span>
              {payment && (
                <span className="inline-block rounded-full bg-cocoa/10 px-2.5 py-0.5 text-[10px] uppercase tracking-[0.12em] text-cocoa">
                  Payment: {payment.status}
                </span>
              )}
              {POLLED_STATUSES.includes(order.status) && (
                <span className="text-xs text-cocoa/40">(refreshing…)</span>
              )}
            </div>

            <p className="mt-4 text-sm text-cocoa/60">
              Placed {new Date(order.createdAt).toLocaleString()}
            </p>
            {Number(order.discountTotal ?? 0) > 0 && (
              <p className="mt-1 text-sm text-cocoa/60">
                Voucher {order.voucherCode} ({order.discountPercent}% off)
                {' '}−{formatPrice(Number(order.discountTotal))}
              </p>
            )}
            <p className="mt-1 font-display text-xl text-cocoa">{formatPrice(order.total)}</p>

            {order.address && (
              <div className="mt-8">
                <h2 className="font-display text-[20px] text-cocoa">Delivery Address</h2>
                <p className="mt-2 text-sm text-cocoa/70">
                  {order.address.recipientName}
                  <br />
                  {order.address.addressLine1}
                  {order.address.addressLine2 ? `, ${order.address.addressLine2}` : ''}
                  <br />
                  {order.address.city}
                  {order.address.state ? `, ${order.address.state}` : ''} {order.address.zipCode}
                  <br />
                  {order.address.country}
                </p>
              </div>
            )}

            <div className="mt-8">
              <h2 className="font-display text-[20px] text-cocoa">Items</h2>
              <ul className="mt-4 divide-y divide-cocoa/10 border-y border-cocoa/10">
                {order.items.map((item) => (
                  <li key={item.id} className="flex items-center justify-between gap-4 py-4">
                    <Link to={`/products/${item.productId}`} className="text-cocoa hover:text-terracotta">
                      {item.productName} <span className="text-cocoa/50">× {item.quantity}</span>
                    </Link>
                    <span className="text-cocoa">{formatPrice(item.unitPrice * item.quantity)}</span>
                  </li>
                ))}
              </ul>
            </div>

            <Link
              to="/profile/orders"
              className="mt-10 inline-block border border-cocoa px-8 py-3 text-xs uppercase tracking-[0.18em] text-cocoa transition-colors duration-300 hover:bg-cocoa hover:text-ivory"
            >
              Back to Orders
            </Link>
          </>
        )}
      </section>
    </div>
  );
}
