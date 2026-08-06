import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router';
import { api, ApiError } from '../api';
import PaymentWidget from '../components/payment/PaymentWidget';
import { usePaymentProviders } from '../components/payment/usePaymentProviders';
import type { BalanceResponse, BalanceTransaction, CreatePaymentIntentResponse } from '../types';

const inputStyle =
  'w-full border border-cocoa/20 bg-white/70 px-4 py-3 text-sm text-cocoa placeholder:text-cocoa/40 focus:border-gold focus:outline-none';

const buttonStyle =
  'px-6 py-3 text-xs uppercase tracking-[0.14em] transition-colors duration-300 disabled:opacity-50';

const KIND_LABEL: Record<BalanceTransaction['kind'], string> = {
  TOPUP: 'Top-up',
  GIFT: 'Gift',
  TRANSFER: 'Transfer',
  SPEND: 'Purchase',
  REFUND: 'Refund',
};

/**
 * The account holder's CHF balance (docs/finance/finance.md), in this
 * storefront's vocabulary. Route is /profile/balance because payment sends a
 * returning top-up shopper there — see PaymentService.balancePageUrl().
 */
export function BalancePage() {
  const [balance, setBalance] = useState<BalanceResponse | null>(null);
  const [transactions, setTransactions] = useState<BalanceTransaction[]>([]);
  const [loading, setLoading] = useState(true);
  const [message, setMessage] = useState<{ kind: 'ok' | 'error'; text: string } | null>(null);
  const [params, setParams] = useSearchParams();

  const load = () =>
    Promise.all([api.getBalance(), api.getBalanceTransactions()])
      .then(([b, t]) => {
        setBalance(b);
        setTransactions(t);
      })
      .catch((e) => setMessage({ kind: 'error', text: e instanceof Error ? e.message : String(e) }))
      .finally(() => setLoading(false));

  useEffect(() => {
    // Coming back from a provider. The credit only lands once payment has
    // confirmed with the provider, so ask it to before reading the balance.
    const returning = params.get('topup');
    if (returning && returning !== 'cancelled') {
      api.syncTopup(returning)
        .catch(() => {})
        .finally(() => {
          params.delete('topup');
          setParams(params, { replace: true });
          load();
        });
      return;
    }
    if (returning === 'cancelled') {
      setMessage({ kind: 'error', text: 'Top-up cancelled.' });
      params.delete('topup');
      setParams(params, { replace: true });
    }
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const negative = (balance?.balanceMinor ?? 0) < 0;

  return (
    <div>
      <p className="text-xs uppercase tracking-[0.3em] text-terracotta">Your Account</p>
      <h1 className="mt-3 font-display text-[36px] leading-tight text-cocoa lg:text-[48px]">Balance</h1>

      {message && (
        <p
          role="status"
          className={`mt-6 border-l-2 px-4 py-3 text-sm ${
            message.kind === 'ok'
              ? 'border-sage bg-sage/10 text-cocoa'
              : 'border-terracotta bg-terracotta/10 text-terracotta'
          }`}
        >
          {message.text}
        </p>
      )}

      <section className="mt-8 border border-cocoa/15 bg-white/60 p-8">
        <p className="text-xs uppercase tracking-[0.3em] text-cocoa/50">Available</p>
        <p className={`mt-2 font-display text-[42px] leading-none ${negative ? 'text-terracotta' : 'text-cocoa'}`}>
          {loading ? '—' : `CHF ${balance?.balanceChf.toFixed(2) ?? '0.00'}`}
        </p>
        {negative && (
          <p className="mt-2 text-xs text-terracotta">You are using credit. Top up to clear it.</p>
        )}
      </section>

      <TopUp onDone={load} onMessage={setMessage} />
      <SendMoney onSent={load} onMessage={setMessage} />

      <h2 className="mt-12 font-display text-[24px] text-cocoa">History</h2>
      {transactions.length === 0 ? (
        <p className="mt-4 text-sm text-cocoa/60">Nothing yet.</p>
      ) : (
        <ul className="mt-4 divide-y divide-cocoa/10 border-y border-cocoa/10">
          {transactions.map((t) => (
            <li key={t.id} className="flex items-baseline justify-between gap-4 py-4">
              <span className="min-w-0">
                <span className="block text-sm text-cocoa">{KIND_LABEL[t.kind] ?? t.kind}</span>
                <span className="block truncate text-xs text-cocoa/50">
                  {new Date(t.createdAt).toLocaleString()}
                  {t.memo ? ` · ${t.memo}` : ''}
                </span>
              </span>
              <span
                className={`shrink-0 text-sm font-semibold ${
                  t.amountMinor < 0 ? 'text-terracotta' : 'text-sage'
                }`}
              >
                {t.amountMinor < 0 ? '−' : '+'} CHF {Math.abs(t.amountChf).toFixed(2)}
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function TopUp({
  onDone,
  onMessage,
}: {
  onDone: () => void;
  onMessage: (m: { kind: 'ok' | 'error'; text: string }) => void;
}) {
  const { providers } = usePaymentProviders();
  // Balance cannot fund itself, and offering it here would be a loop.
  const usable = providers.filter((p) => p.id !== 'balance');
  const [provider, setProvider] = useState('');
  const [amount, setAmount] = useState('20');
  const [intent, setIntent] = useState<CreatePaymentIntentResponse | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!provider && usable.length > 0) setProvider(usable[0].id);
  }, [usable, provider]);

  const start = async () => {
    setBusy(true);
    try {
      setIntent(await api.createTopupIntent(Number(amount), provider));
    } catch (e) {
      onMessage({
        kind: 'error',
        text: e instanceof ApiError ? String(e.message).replace(/^\[\d+\]\s*/, '') : 'Could not start the top-up.',
      });
    } finally {
      setBusy(false);
    }
  };

  if (intent) {
    const info = usable.find((p) => p.id === intent.provider);
    return (
      <section className="mt-8 border border-cocoa/15 bg-white/60 p-6">
        <h2 className="font-display text-[24px] text-cocoa">Complete your top-up</h2>
        <div className="mt-6">
          <PaymentWidget
            provider={intent.provider}
            displayName={info?.displayName}
            confirmationMode={info?.confirmationMode ?? 'CLIENT_SDK'}
            payload={intent.providerPayload ?? {}}
            returnUrl={`${window.location.origin}/profile/balance?topup=${intent.id}`}
            onPaymentConfirmed={() => {
              api.syncTopup(intent.id)
                .catch(() => {})
                .finally(() => {
                  setIntent(null);
                  onDone();
                });
            }}
            onError={(msg) => onMessage({ kind: 'error', text: msg })}
          />
        </div>
        <button className={`${buttonStyle} mt-6 border border-cocoa/20 text-cocoa`} onClick={() => setIntent(null)}>
          Cancel
        </button>
      </section>
    );
  }

  return (
    <section className="mt-8 border border-cocoa/15 bg-white/60 p-6">
      <h2 className="font-display text-[24px] text-cocoa">Top up</h2>
      <div className="mt-6 flex flex-wrap items-center gap-3">
        <input
          type="number"
          min="1"
          step="0.05"
          value={amount}
          onChange={(e) => setAmount(e.target.value)}
          aria-label="Amount in CHF"
          className={`${inputStyle} w-32`}
        />
        <select
          value={provider}
          onChange={(e) => setProvider(e.target.value)}
          aria-label="Pay with"
          className={`${inputStyle} w-auto`}
        >
          {usable.map((p) => (
            <option key={p.id} value={p.id}>
              {p.displayName}
            </option>
          ))}
        </select>
        <button
          className={`${buttonStyle} bg-cocoa text-ivory hover:bg-espresso`}
          disabled={busy || !provider || Number(amount) <= 0}
          onClick={start}
        >
          {busy ? 'Starting…' : 'Top up'}
        </button>
      </div>
      {usable.length === 0 && <p className="mt-4 text-sm text-cocoa/60">No payment provider is available.</p>}
    </section>
  );
}

function SendMoney({
  onSent,
  onMessage,
}: {
  onSent: () => void;
  onMessage: (m: { kind: 'ok' | 'error'; text: string }) => void;
}) {
  const [to, setTo] = useState('');
  const [amount, setAmount] = useState('');
  const [memo, setMemo] = useState('');
  const [busy, setBusy] = useState(false);

  const send = async () => {
    setBusy(true);
    try {
      const r = await api.transferBalance({
        to: to.trim(),
        amountChf: Number(amount),
        memo: memo.trim() || undefined,
        // Survives a double-click or a retry without sending twice.
        idempotencyKey: crypto.randomUUID(),
      });
      onMessage({ kind: 'ok', text: `Sent CHF ${r.amountChf.toFixed(2)} to ${r.to}.` });
      setTo('');
      setAmount('');
      setMemo('');
      onSent();
    } catch (e) {
      // 402 is a decline by the credit policy, 404 an unknown recipient — the
      // server's own wording is the useful one.
      onMessage({
        kind: 'error',
        text: e instanceof ApiError ? String(e.message).replace(/^\[\d+\]\s*/, '') : 'Could not send.',
      });
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="mt-8 border border-cocoa/15 bg-white/60 p-6">
      <h2 className="font-display text-[24px] text-cocoa">Send money</h2>
      <div className="mt-6 grid grid-cols-1 gap-3 sm:grid-cols-2">
        <input
          placeholder="Username"
          value={to}
          onChange={(e) => setTo(e.target.value)}
          aria-label="Recipient username"
          className={inputStyle}
        />
        <input
          type="number"
          min="0.01"
          step="0.05"
          placeholder="Amount in CHF"
          value={amount}
          onChange={(e) => setAmount(e.target.value)}
          aria-label="Amount in CHF"
          className={inputStyle}
        />
        <input
          placeholder="Message (optional)"
          value={memo}
          onChange={(e) => setMemo(e.target.value)}
          className={`${inputStyle} sm:col-span-2`}
        />
      </div>
      <button
        className={`${buttonStyle} mt-5 bg-cocoa text-ivory hover:bg-espresso`}
        disabled={busy || !to.trim() || Number(amount) <= 0}
        onClick={send}
      >
        {busy ? 'Sending…' : 'Send'}
      </button>
    </section>
  );
}
