import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router';
import { api } from '../api';
import { useAuth } from '../auth';
import type { DeliveryResponse } from '../types';

type SortKey = 'orderId' | 'createdAt';
type SortDir = 'asc' | 'desc';

const STATUSES = ['PENDING', 'DISPATCHED', 'DELIVERED', 'FAILED'] as const;

const PAGE_SIZE = 20;

const STATUS_STYLES: Record<string, string> = {
  PENDING: 'border-gold text-cocoa',
  DISPATCHED: 'border-sage text-cocoa',
  DELIVERED: 'border-sage bg-sage/20 text-cocoa',
  FAILED: 'border-terracotta text-terracotta',
  PAID: 'border-sage text-cocoa',
  CANCELLED: 'border-terracotta text-terracotta',
};

const selectStyle =
  'border border-cocoa/20 bg-white/70 px-3 py-2 text-sm text-cocoa focus:border-gold focus:outline-none';

/**
 * Back of house shipment desk — the ui-demo counterpart of
 * ui-shop/src/pages/DeliveryManagement.tsx. Marking a shipment dispatched,
 * delivered or failed is the only writable step in the order lifecycle that
 * isn't driven by a Kafka event, so this page is where a human moves it.
 */
export function DeliveriesPage() {
  const { isAdmin, isManager, loading: authLoading } = useAuth();
  const [deliveries, setDeliveries] = useState<DeliveryResponse[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<number | null>(null);
  const [filterStatus, setFilterStatus] = useState('');
  const [dateFrom, setDateFrom] = useState('');
  const [dateTo, setDateTo] = useState('');
  const [sortKey, setSortKey] = useState<SortKey>('orderId');
  const [sortDir, setSortDir] = useState<SortDir>('desc');

  const canManage = isAdmin || isManager;
  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    api
      .getDeliveries({
        status: filterStatus || undefined,
        from: dateFrom || undefined,
        to: dateTo || undefined,
        sort: sortKey,
        dir: sortDir,
        page,
        size: PAGE_SIZE,
      })
      .then((result) => {
        setDeliveries(result.items);
        setTotal(result.total);
      })
      .catch((e) => setError(e instanceof Error ? e.message : String(e)))
      .finally(() => setLoading(false));
  }, [filterStatus, dateFrom, dateTo, sortKey, sortDir, page]);

  useEffect(() => {
    if (canManage) load();
  }, [canManage, load]);

  /**
   * Any change to what is being asked for sends the reader back to page 0. Page 3
   * of the old filter is rarely page 3 of the new one, and can be past the end.
   */
  const applyFilter =
    <T,>(set: (value: T) => void) =>
    (value: T) => {
      set(value);
      setPage(0);
    };

  const toggleSort = (key: SortKey) => {
    if (sortKey === key) {
      setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'));
    } else {
      setSortKey(key);
      setSortDir('asc');
    }
    setPage(0);
  };

  const updateStatus = (orderId: number, status: string, description: string) => {
    setBusy(orderId);
    setError(null);
    api
      .updateDeliveryStatus(orderId, status, description)
      .then(() => load())
      .catch((e) => setError(e instanceof Error ? e.message : String(e)))
      .finally(() => setBusy(null));
  };

  if (authLoading) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-ivory pt-28 text-cocoa/50">
        Loading…
      </div>
    );
  }

  if (!canManage) {
    return (
      <div className="flex min-h-screen flex-col items-center justify-center gap-4 bg-ivory px-6 pt-28 text-center">
        <h1 className="font-display text-[32px] text-cocoa">The Back of House is Locked</h1>
        <p className="max-w-md text-cocoa/60">
          This area is reserved for SI Chocolate staff. Sign in with an admin or manager account to
          move shipments along.
        </p>
        <Link
          to="/"
          className="mt-4 border border-cocoa px-8 py-3 text-xs uppercase tracking-[0.18em] text-cocoa transition-colors duration-300 hover:bg-cocoa hover:text-ivory"
        >
          Back to the Boutique
        </Link>
      </div>
    );
  }

  return (
    <div className="bg-ivory pt-28 lg:pt-32">
      <div className="mx-auto max-w-5xl px-5 pb-24 lg:px-8">
        <p className="text-xs uppercase tracking-[0.3em] text-terracotta">Back of House</p>
        <h1 className="mt-3 font-display text-[36px] leading-tight text-cocoa lg:text-[48px]">
          Shipments
        </h1>
        <p className="mt-2 text-sm text-cocoa/60">
          Every box the boutique owes a customer — dispatch it, confirm it arrived, or record a
          failure.{' '}
          <Link
            to="/admin"
            className="text-cocoa underline decoration-gold underline-offset-4 hover:text-terracotta"
          >
            Back to the collection
          </Link>
        </p>

        <div className="mt-8 flex flex-wrap items-end gap-4">
          <label className="flex flex-col gap-1">
            <span className="text-xs uppercase tracking-[0.16em] text-cocoa/60">Status</span>
            <select
              value={filterStatus}
              onChange={(e) => applyFilter(setFilterStatus)(e.target.value)}
              className={selectStyle}
            >
              <option value="">All</option>
              {STATUSES.map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>
          </label>

          <label className="flex flex-col gap-1">
            <span className="text-xs uppercase tracking-[0.16em] text-cocoa/60">From</span>
            <input
              type="date"
              value={dateFrom}
              onChange={(e) => applyFilter(setDateFrom)(e.target.value)}
              className={selectStyle}
            />
          </label>

          <label className="flex flex-col gap-1">
            <span className="text-xs uppercase tracking-[0.16em] text-cocoa/60">To</span>
            <input
              type="date"
              value={dateTo}
              onChange={(e) => applyFilter(setDateTo)(e.target.value)}
              className={selectStyle}
            />
          </label>

          <button
            onClick={load}
            className="border border-cocoa/30 px-6 py-2.5 text-xs uppercase tracking-[0.18em] text-cocoa transition-colors hover:border-cocoa"
          >
            Refresh
          </button>
        </div>

        <div className="mt-4 flex flex-wrap items-center gap-5 text-xs uppercase tracking-[0.14em] text-cocoa/60">
          <span>Sort by</span>
          <button
            onClick={() => toggleSort('orderId')}
            className="text-cocoa underline decoration-gold underline-offset-4 hover:text-terracotta"
          >
            Order {sortKey === 'orderId' ? (sortDir === 'asc' ? '↑' : '↓') : ''}
          </button>
          <button
            onClick={() => toggleSort('createdAt')}
            className="text-cocoa underline decoration-gold underline-offset-4 hover:text-terracotta"
          >
            Date {sortKey === 'createdAt' ? (sortDir === 'asc' ? '↑' : '↓') : ''}
          </button>
          <span className="normal-case tracking-normal text-cocoa/50">
            {total} shipment{total === 1 ? '' : 's'}
          </span>
        </div>

        {error && (
          <p
            role="status"
            className="mt-6 border-l-2 border-terracotta bg-terracotta/10 px-4 py-3 text-sm text-terracotta"
          >
            {error}
          </p>
        )}

        {loading ? (
          <p className="mt-10 text-sm text-cocoa/50">Loading shipments…</p>
        ) : deliveries.length === 0 ? (
          <p className="mt-10 text-sm text-cocoa/50">
            {filterStatus || dateFrom || dateTo
              ? 'No shipments match these filters.'
              : 'No shipments yet — none of the boutique’s orders have reached the delivery service.'}
          </p>
        ) : (
          <ul className="mt-10 divide-y divide-cocoa/10 border-y border-cocoa/10">
            {deliveries.map((d) => {
              const working = busy === d.orderId;
              return (
                <li key={d.id} className="py-6">
                  <div className="flex flex-wrap items-center gap-3">
                    <Link
                      to={`/orders/${d.orderId}`}
                      className="font-display text-lg text-cocoa hover:text-terracotta"
                    >
                      Order #{d.orderId}
                    </Link>
                    <Badge text={d.status} />
                    <Badge text={d.paymentStatus} />
                    <span className="text-sm text-cocoa/50">
                      {new Date(d.createdAt).toLocaleDateString(undefined, { dateStyle: 'medium' })}
                    </span>
                    {working && <span className="text-xs text-cocoa/50">Updating…</span>}
                  </div>

                  <p className="mt-2 text-sm text-cocoa/70">
                    {d.recipientName} — {d.addressLine1}
                    {d.city && `, ${d.city}`}
                    {d.zipCode && ` ${d.zipCode}`}
                    {d.country && `, ${d.country}`}
                  </p>
                  {d.items && <p className="mt-1 text-sm text-cocoa/50">{d.items}</p>}

                  <div className="mt-4 flex flex-wrap items-center gap-5">
                    <Link
                      to={`/orders/${d.orderId}`}
                      className="text-xs uppercase tracking-[0.14em] text-cocoa underline decoration-gold underline-offset-4 hover:text-terracotta"
                    >
                      View order
                    </Link>

                    {d.status === 'PENDING' && (
                      <button
                        disabled={working}
                        onClick={() => updateStatus(d.orderId, 'DISPATCHED', 'Order dispatched')}
                        className="text-xs uppercase tracking-[0.14em] text-cocoa underline underline-offset-4 hover:text-terracotta disabled:opacity-40"
                      >
                        Mark dispatched
                      </button>
                    )}

                    {d.status === 'DISPATCHED' && (
                      <>
                        <button
                          disabled={working}
                          onClick={() =>
                            updateStatus(d.orderId, 'DELIVERED', 'Order delivered successfully')
                          }
                          className="text-xs uppercase tracking-[0.14em] text-cocoa underline underline-offset-4 hover:text-terracotta disabled:opacity-40"
                        >
                          Mark delivered
                        </button>
                        <button
                          disabled={working}
                          onClick={() => updateStatus(d.orderId, 'FAILED', 'Delivery failed')}
                          className="text-xs uppercase tracking-[0.14em] text-terracotta/80 underline underline-offset-4 hover:text-terracotta disabled:opacity-40"
                        >
                          Mark failed
                        </button>
                      </>
                    )}
                  </div>
                </li>
              );
            })}
          </ul>
        )}

        {total > PAGE_SIZE && (
          <div className="mt-10 flex items-center gap-6 text-xs uppercase tracking-[0.14em] text-cocoa/60">
            <button
              disabled={page === 0 || loading}
              onClick={() => setPage((p) => Math.max(0, p - 1))}
              className="text-cocoa underline decoration-gold underline-offset-4 hover:text-terracotta disabled:opacity-40 disabled:no-underline"
            >
              Previous
            </button>
            <span className="normal-case tracking-normal text-cocoa/50">
              Page {page + 1} of {totalPages}
            </span>
            <button
              disabled={page + 1 >= totalPages || loading}
              onClick={() => setPage((p) => p + 1)}
              className="text-cocoa underline decoration-gold underline-offset-4 hover:text-terracotta disabled:opacity-40 disabled:no-underline"
            >
              Next
            </button>
          </div>
        )}
      </div>
    </div>
  );
}

function Badge({ text }: { text: string }) {
  return (
    <span
      className={`rounded-full border px-2.5 py-0.5 text-[10px] uppercase tracking-[0.12em] ${
        STATUS_STYLES[text] ?? 'border-cocoa/30 text-cocoa/60'
      }`}
    >
      {text}
    </span>
  );
}
