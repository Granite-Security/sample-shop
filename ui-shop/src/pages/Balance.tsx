import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router';
import { api } from '../api';
import { ApiError } from '../api/client';
import PaymentWidget from '../components/payment/PaymentWidget';
import type {
  BalanceResponse,
  BalanceTransaction,
  CreatePaymentIntentResponse,
  PaymentProviderInfo,
} from '../types';

const KIND_LABEL: Record<BalanceTransaction['kind'], string> = {
  TOPUP: 'Top-up',
  GIFT: 'Gift',
  TRANSFER: 'Transfer',
  SPEND: 'Purchase',
  REFUND: 'Refund',
};

/**
 * The account holder's view of their CHF balance (docs/finance/finance.md).
 *
 * Route is /profile/balance because payment sends a returning top-up shopper
 * there — see PaymentService.balancePageUrl().
 */
export default function Balance() {
  const [balance, setBalance] = useState<BalanceResponse | null>(null);
  const [transactions, setTransactions] = useState<BalanceTransaction[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [params, setParams] = useSearchParams();

  const load = () =>
    Promise.all([api.balance.get(), api.balance.transactions()])
      .then(([b, t]) => {
        setBalance(b);
        setTransactions(t);
      })
      .catch(e => setError(e instanceof Error ? e.message : String(e)))
      .finally(() => setLoading(false));

  useEffect(() => {
    // Coming back from a provider. The credit only lands once payment has
    // confirmed with the provider, so ask it to before reading the balance —
    // otherwise the page shows the old figure and looks broken.
    const returning = params.get('topup');
    if (returning && returning !== 'cancelled') {
      api.balance
        .syncTopup(returning)
        .catch(() => {})
        .finally(() => {
          params.delete('topup');
          setParams(params, { replace: true });
          load();
        });
      return;
    }
    if (returning === 'cancelled') {
      setNotice('Top-up cancelled.');
      params.delete('topup');
      setParams(params, { replace: true });
    }
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (loading) return <div className="spinner" style={{ margin: '0 auto' }} />;

  return (
    <div>
      <h1 style={{ marginBottom: 16 }}>My Balance</h1>

      {error && <p className="error" style={{ marginBottom: 12 }}>{error}</p>}
      {notice && <p style={{ marginBottom: 12, color: 'var(--text-secondary)' }}>{notice}</p>}

      <div
        style={{
          border: '1px solid var(--border)',
          borderRadius: 'var(--radius)',
          padding: '1.25rem',
          marginBottom: '1.5rem',
        }}
      >
        <div style={{ fontSize: '0.8rem', color: 'var(--text-secondary)' }}>Available</div>
        <div
          style={{
            fontSize: '2rem',
            fontWeight: 600,
            // A negative balance is credit extended, not an error state — but it
            // should look different from money you have.
            color: (balance?.balanceMinor ?? 0) < 0 ? 'var(--danger)' : 'var(--text)',
          }}
        >
          {balance ? `CHF ${balance.balanceChf.toFixed(2)}` : '—'}
        </div>
        {(balance?.balanceMinor ?? 0) < 0 && (
          <div style={{ fontSize: '0.8rem', color: 'var(--danger)' }}>
            You are using credit. Top up to clear it.
          </div>
        )}
      </div>

      <TopUp onDone={load} />
      <SendMoney onSent={load} />

      <h2 style={{ margin: '1.5rem 0 0.75rem' }}>History</h2>
      {transactions.length === 0 ? (
        <p style={{ color: 'var(--text-secondary)' }}>Nothing yet.</p>
      ) : (
        <table className="orders-table" style={{ width: '100%' }}>
          <thead>
            <tr>
              <th>When</th>
              <th>What</th>
              <th>Details</th>
              <th style={{ textAlign: 'right' }}>Amount</th>
            </tr>
          </thead>
          <tbody>
            {transactions.map(t => (
              <tr key={t.id}>
                <td>{new Date(t.createdAt).toLocaleString()}</td>
                <td>{KIND_LABEL[t.kind] ?? t.kind}</td>
                <td style={{ color: 'var(--text-secondary)' }}>{t.memo ?? t.reference ?? ''}</td>
                <td
                  style={{
                    textAlign: 'right',
                    fontWeight: 600,
                    color: t.amountMinor < 0 ? 'var(--danger)' : 'var(--primary)',
                  }}
                >
                  {t.amountMinor < 0 ? '−' : '+'} CHF {Math.abs(t.amountChf).toFixed(2)}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

function TopUp({ onDone }: { onDone: () => void }) {
  const [providers, setProviders] = useState<PaymentProviderInfo[]>([]);
  const [provider, setProvider] = useState('');
  const [amount, setAmount] = useState('20');
  const [intent, setIntent] = useState<CreatePaymentIntentResponse | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.payments
      .listProviders()
      // Balance cannot fund itself, and offering it here would be a loop.
      .then(list => {
        const usable = list.filter(p => p.id !== 'balance');
        setProviders(usable);
        setProvider(usable[0]?.id ?? '');
      })
      .catch(() => {});
  }, []);

  const start = async () => {
    setBusy(true);
    setError(null);
    try {
      setIntent(await api.balance.createTopupIntent(Number(amount), provider));
    } catch (e) {
      setError(e instanceof ApiError ? String(e.message).replace(/^\[\d+\]\s*/, '') : 'Could not start the top-up.');
    } finally {
      setBusy(false);
    }
  };

  if (intent) {
    const info = providers.find(p => p.id === intent.provider);
    return (
      <div style={{ border: '1px solid var(--border)', borderRadius: 'var(--radius)', padding: '1rem', marginBottom: '1rem' }}>
        <h3 style={{ marginBottom: 8 }}>Complete your top-up</h3>
        <PaymentWidget
          provider={intent.provider}
          displayName={info?.displayName}
          confirmationMode={info?.confirmationMode ?? 'CLIENT_SDK'}
          payload={intent.providerPayload ?? {}}
          // Stripe returns here; the effect above then syncs and reloads.
          returnUrl={`${window.location.origin}/profile/balance?topup=${intent.id}`}
          onPaymentConfirmed={() => {
            api.balance.syncTopup(intent.id).catch(() => {}).finally(() => {
              setIntent(null);
              onDone();
            });
          }}
          onError={msg => setError(msg)}
        />
        <button className="btn" style={{ marginTop: 12 }} onClick={() => setIntent(null)}>
          Cancel
        </button>
        {error && <p className="error" style={{ marginTop: 8 }}>{error}</p>}
      </div>
    );
  }

  return (
    <div style={{ border: '1px solid var(--border)', borderRadius: 'var(--radius)', padding: '1rem', marginBottom: '1rem' }}>
      <h3 style={{ marginBottom: 8 }}>Top up</h3>
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
        <input
          type="number"
          min="1"
          step="0.05"
          value={amount}
          onChange={e => setAmount(e.target.value)}
          style={{ width: 120 }}
          aria-label="Amount in CHF"
        />
        <select value={provider} onChange={e => setProvider(e.target.value)} aria-label="Pay with">
          {providers.map(p => (
            <option key={p.id} value={p.id}>
              {p.displayName}
            </option>
          ))}
        </select>
        <button className="btn btn-primary" disabled={busy || !provider || Number(amount) <= 0} onClick={start}>
          {busy ? 'Starting…' : 'Top up'}
        </button>
      </div>
      {providers.length === 0 && (
        <p style={{ color: 'var(--text-secondary)', marginTop: 8 }}>No payment provider is available.</p>
      )}
      {error && <p className="error" style={{ marginTop: 8 }}>{error}</p>}
    </div>
  );
}

function SendMoney({ onSent }: { onSent: () => void }) {
  const [to, setTo] = useState('');
  const [amount, setAmount] = useState('');
  const [memo, setMemo] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [sent, setSent] = useState<string | null>(null);

  const send = async () => {
    setBusy(true);
    setError(null);
    setSent(null);
    try {
      const r = await api.balance.transfer({
        to: to.trim(),
        amountChf: Number(amount),
        memo: memo.trim() || undefined,
        // Survives a double-click or a retry without sending twice.
        idempotencyKey: crypto.randomUUID(),
      });
      setSent(`Sent CHF ${r.amountChf.toFixed(2)} to ${r.to}.`);
      setTo('');
      setAmount('');
      setMemo('');
      onSent();
    } catch (e) {
      // 402 is a decline by the credit policy, 404 an unknown recipient — the
      // server's own wording is the useful one.
      setError(e instanceof ApiError ? String(e.message).replace(/^\[\d+\]\s*/, '') : 'Could not send.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div style={{ border: '1px solid var(--border)', borderRadius: 'var(--radius)', padding: '1rem' }}>
      <h3 style={{ marginBottom: 8 }}>Send money</h3>
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        <input placeholder="Username" value={to} onChange={e => setTo(e.target.value)} aria-label="Recipient username" />
        <input
          type="number"
          min="0.01"
          step="0.05"
          placeholder="CHF"
          value={amount}
          onChange={e => setAmount(e.target.value)}
          style={{ width: 110 }}
          aria-label="Amount in CHF"
        />
        <input placeholder="Message (optional)" value={memo} onChange={e => setMemo(e.target.value)} style={{ flex: 1, minWidth: 160 }} />
        <button className="btn btn-primary" disabled={busy || !to.trim() || Number(amount) <= 0} onClick={send}>
          {busy ? 'Sending…' : 'Send'}
        </button>
      </div>
      {sent && <p style={{ marginTop: 8, color: 'var(--primary)' }}>{sent}</p>}
      {error && <p className="error" style={{ marginTop: 8 }}>{error}</p>}
    </div>
  );
}
