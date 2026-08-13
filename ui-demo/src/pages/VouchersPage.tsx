import { useEffect, useState } from 'react';
import { useAuth } from '../auth';
import { api } from '../api';
import type { VoucherAdmin } from '../types';

/** datetime-local carries no zone; the browser's own is the honest reading of what was typed. */
function toInstant(local: string): string {
  return new Date(local).toISOString();
}

function defaultExpiry(): string {
  const d = new Date();
  d.setMonth(d.getMonth() + 1);
  d.setSeconds(0, 0);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

const inputStyle =
  'w-full border border-cocoa/20 bg-white/70 px-4 py-3 text-sm text-cocoa placeholder:text-cocoa/40 focus:border-gold focus:outline-none';

const STATUS_STYLES: Record<string, string> = {
  ACTIVE: 'bg-gold/15 text-cocoa',
  SCHEDULED: 'bg-cocoa/10 text-cocoa/70',
  EXPIRED: 'bg-cocoa/5 text-cocoa/50',
  REVOKED: 'bg-terracotta/10 text-terracotta',
};

/**
 * Voucher maintenance (docs/finance/vouchers.md §8.3).
 *
 * ADMIN or MANAGER, unlike the rest of the back office: running a discount campaign
 * is the manager's job, and a manager who can already refund an order in full can
 * already give money away. The server enforces it; this only decides what is worth
 * rendering.
 *
 * Withdrawing never deletes. Placed orders reference the voucher, and what they were
 * charged is snapshotted onto the order anyway, so pulling a code leaves history alone.
 */
export default function VouchersPage() {
  const { isAdmin, isManager, loading } = useAuth();
  const [vouchers, setVouchers] = useState<VoucherAdmin[]>([]);
  const [busy, setBusy] = useState(true);
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);
  const [code, setCode] = useState('');
  const [percentOff, setPercentOff] = useState(10);
  const [validUntil, setValidUntil] = useState(defaultExpiry());
  const [description, setDescription] = useState('');

  const canManage = isAdmin || isManager;

  const load = () => {
    setBusy(true);
    api
      .listVouchers()
      .then(setVouchers)
      .catch((e) => setError(e instanceof Error ? e.message : 'Could not load vouchers'))
      .finally(() => setBusy(false));
  };

  useEffect(() => {
    if (canManage) load();
  }, [canManage]);

  if (loading) {
    return <div className="mx-auto max-w-3xl px-6 py-24 text-sm text-cocoa/50">Loading…</div>;
  }

  if (!canManage) {
    return (
      <div className="mx-auto max-w-3xl px-6 py-24">
        <h1 className="font-display text-3xl text-cocoa">Not your door</h1>
        <p className="mt-3 text-sm text-cocoa/60">This page is for the boutique's staff.</p>
      </div>
    );
  }

  const create = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setError('');
    try {
      await api.createVoucher({
        code: code.trim(),
        percentOff,
        validUntil: toInstant(validUntil),
        description: description.trim() || undefined,
      });
      setCode('');
      setDescription('');
      load();
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Could not create the voucher');
    } finally {
      setSaving(false);
    }
  };

  const revoke = async (voucher: VoucherAdmin) => {
    if (!window.confirm(`Withdraw ${voucher.code}? Orders already placed keep their discount.`)) return;
    try {
      await api.revokeVoucher(voucher.id);
      load();
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Could not withdraw the voucher');
    }
  };

  return (
    <div className="mx-auto max-w-5xl px-6 py-16">
      <h1 className="font-display text-[32px] text-cocoa">Vouchers</h1>
      <p className="mt-3 max-w-2xl text-sm text-cocoa/60">
        Percentage discount codes. A voucher is not money — it takes a percentage off the
        pieces in an order, boxes are charged in full, and each guest may use a given code
        once. Nothing is discounted until a code exists here.
      </p>

      <section className="mt-12 max-w-xl">
        <h2 className="text-xs uppercase tracking-[0.18em] text-cocoa">New voucher</h2>
        <form onSubmit={create} className="mt-4 grid gap-3">
          <input
            aria-label="Code"
            placeholder="Code, e.g. SPRING25"
            value={code}
            onChange={(e) => setCode(e.target.value)}
            required
            className={`${inputStyle} uppercase`}
          />
          <input
            aria-label="Percent off"
            type="number"
            min={1}
            max={100}
            value={percentOff}
            required
            onChange={(e) => setPercentOff(Number(e.target.value))}
            className={inputStyle}
          />
          {percentOff > 90 && (
            <p className="text-sm text-terracotta">
              Above 90% an order can price below the smallest amount a payment provider will
              take — a cart with no boxes would then be refused at checkout rather than sold.
            </p>
          )}
          <label className="text-xs uppercase tracking-[0.14em] text-cocoa/50">
            Valid until
            <input
              type="datetime-local"
              value={validUntil}
              required
              onChange={(e) => setValidUntil(e.target.value)}
              className={`${inputStyle} mt-1`}
            />
          </label>
          <input
            aria-label="Description"
            placeholder="What this campaign is"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            className={inputStyle}
          />
          {error && <p className="text-sm text-terracotta">{error}</p>}
          <button
            type="submit"
            disabled={saving || !code.trim()}
            className="border border-cocoa px-6 py-3 text-xs uppercase tracking-[0.18em] text-cocoa transition hover:bg-cocoa hover:text-cream disabled:opacity-40"
          >
            {saving ? 'Creating…' : 'Create voucher'}
          </button>
        </form>
      </section>

      <section className="mt-16">
        <h2 className="text-xs uppercase tracking-[0.18em] text-cocoa">All vouchers</h2>
        {busy ? (
          <p className="mt-4 text-sm text-cocoa/50">Loading…</p>
        ) : vouchers.length === 0 ? (
          <p className="mt-4 text-sm text-cocoa/50">None yet.</p>
        ) : (
          <div className="mt-4 overflow-x-auto">
            <table className="w-full min-w-[720px] text-left text-sm">
              <thead>
                <tr className="border-b border-cocoa/15 text-xs uppercase tracking-[0.14em] text-cocoa/50">
                  <th className="py-3 pr-4">Code</th>
                  <th className="py-3 pr-4">Off</th>
                  <th className="py-3 pr-4">Valid until</th>
                  <th className="py-3 pr-4">Used</th>
                  <th className="py-3 pr-4">Status</th>
                  <th className="py-3 pr-4">Description</th>
                  <th className="py-3" />
                </tr>
              </thead>
              <tbody>
                {vouchers.map((v) => (
                  <tr key={v.id} className="border-b border-cocoa/10 text-cocoa">
                    <td className="py-3 pr-4 font-display">{v.code}</td>
                    <td className="py-3 pr-4">{v.percentOff}%</td>
                    <td className="py-3 pr-4">{new Date(v.validUntil).toLocaleString()}</td>
                    <td className="py-3 pr-4">{v.redemptions}</td>
                    <td className="py-3 pr-4">
                      <span className={`px-2 py-1 text-[10px] uppercase tracking-[0.12em] ${STATUS_STYLES[v.status] ?? ''}`}>
                        {v.status}
                      </span>
                    </td>
                    <td className="py-3 pr-4 text-cocoa/60">{v.description || '—'}</td>
                    <td className="py-3">
                      {v.status !== 'REVOKED' && (
                        <button
                          type="button"
                          onClick={() => revoke(v)}
                          className="text-xs uppercase tracking-[0.14em] text-cocoa/60 underline underline-offset-4 hover:text-terracotta"
                        >
                          Withdraw
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
}
