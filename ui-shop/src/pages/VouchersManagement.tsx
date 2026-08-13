import { useEffect, useState } from 'react';
import { useAuth } from '../auth';
import { api } from '../api';
import type { VoucherAdmin } from '../types';

/** Datetime-local gives no zone; the browser's own is the honest reading of what an admin typed. */
function toInstant(local: string): string {
  return new Date(local).toISOString();
}

function defaultExpiry(): string {
  const d = new Date();
  d.setMonth(d.getMonth() + 1);
  d.setSeconds(0, 0);
  // datetime-local wants a local-time string with no zone and no seconds.
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/**
 * Voucher maintenance (docs/finance/vouchers.md §8.3).
 *
 * ADMIN or MANAGER, unlike the rest of the admin panel: running a discount campaign is
 * the manager's job, and a manager who can already refund an order in full can already
 * give money away. The server enforces it; this only decides what is worth rendering.
 *
 * Revoking never deletes: placed orders reference the voucher, and what they were
 * charged is snapshotted onto the order anyway, so withdrawing one leaves history alone.
 */
export default function VouchersManagement() {
  const { isAdmin, isManager } = useAuth();
  const canManage = isAdmin || isManager;
  const [vouchers, setVouchers] = useState<VoucherAdmin[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);
  const [code, setCode] = useState('');
  const [percentOff, setPercentOff] = useState(10);
  const [validUntil, setValidUntil] = useState(defaultExpiry());
  const [description, setDescription] = useState('');

  const load = () => {
    setLoading(true);
    api.vouchers.list()
      .then(setVouchers)
      .catch(e => setError(e instanceof Error ? e.message : 'Could not load vouchers'))
      .finally(() => setLoading(false));
  };

  // Guarded: a signed-in shopper landing here must not fire a request that can
  // only come back 403.
  useEffect(() => { if (canManage) load(); }, [canManage]);

  if (!canManage) {
    return (
      <div className="page">
        <h1>Access Denied</h1>
        <p>You do not have permission to manage vouchers.</p>
      </div>
    );
  }

  const create = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setError('');
    try {
      await api.vouchers.create({
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
      await api.vouchers.revoke(voucher.id);
      load();
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Could not revoke the voucher');
    }
  };

  return (
    <div className="page">
      <h1>Vouchers</h1>
      <p style={{ color: 'var(--text-secondary)' }}>
        Percentage discount codes. A voucher is not money — it takes a percentage off the
        goods in an order, boxes are charged in full, and each shopper can use a given code
        once.
      </p>

      <section style={{ marginTop: 24, maxWidth: 520 }}>
        <h2>New voucher</h2>
        <form onSubmit={create} style={{ display: 'grid', gap: 12, marginTop: 12 }}>
          <label>
            Code
            <input value={code} onChange={e => setCode(e.target.value)} placeholder="SPRING25"
                   required style={{ textTransform: 'uppercase', width: '100%' }} />
          </label>
          <label>
            Percent off
            <input type="number" min={1} max={100} value={percentOff} required
                   onChange={e => setPercentOff(Number(e.target.value))} style={{ width: '100%' }} />
          </label>
          {percentOff > 90 && (
            <p style={{ color: 'var(--text-secondary)', fontSize: '0.85rem', margin: 0 }}>
              Above 90% an order can price below the smallest amount a payment provider will
              take — a cart with no boxes would then be refused at checkout rather than sold.
            </p>
          )}
          <label>
            Valid until
            <input type="datetime-local" value={validUntil} required
                   onChange={e => setValidUntil(e.target.value)} style={{ width: '100%' }} />
          </label>
          <label>
            Description
            <input value={description} onChange={e => setDescription(e.target.value)}
                   placeholder="Spring newsletter" style={{ width: '100%' }} />
          </label>
          {error && <p className="error">{error}</p>}
          <button className="btn btn-primary" disabled={saving || !code.trim()}>
            {saving ? 'Creating…' : 'Create voucher'}
          </button>
        </form>
      </section>

      <section style={{ marginTop: 32 }}>
        <h2>All vouchers</h2>
        {loading ? <p>Loading…</p> : vouchers.length === 0 ? (
          <p style={{ color: 'var(--text-secondary)' }}>None yet. Nothing is discounted until one exists.</p>
        ) : (
          <table className="orders-table" style={{ width: '100%', marginTop: 12 }}>
            <thead>
              <tr>
                <th>Code</th><th>Off</th><th>Valid until</th><th>Used</th>
                <th>Status</th><th>Description</th><th />
              </tr>
            </thead>
            <tbody>
              {vouchers.map(v => (
                <tr key={v.id} style={{ opacity: v.status === 'ACTIVE' ? 1 : 0.6 }}>
                  <td><strong>{v.code}</strong></td>
                  <td>{v.percentOff}%</td>
                  <td>{new Date(v.validUntil).toLocaleString()}</td>
                  <td>{v.redemptions}</td>
                  <td><span className={`status status-${v.status.toLowerCase()}`}>{v.status}</span></td>
                  <td>{v.description || '—'}</td>
                  <td>
                    {v.status !== 'REVOKED' && (
                      <button className="btn" onClick={() => revoke(v)}>Withdraw</button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </div>
  );
}
