import { useEffect, useState } from 'react';
import { api } from '../api';
import type { AccountView, LedgerEntryView, ReconcileReport } from '../types';

const KIND_LABEL: Record<LedgerEntryView['kind'], string> = {
  TOPUP: 'Top-up',
  GIFT: 'Gift',
  TRANSFER: 'Transfer',
  SPEND: 'Purchase',
  REFUND: 'Refund',
};

const chf = (v: number) => `CHF ${v.toFixed(2)}`;

/**
 * The central bank's own books (docs/finance/finance.md §3), for admins.
 *
 * Exists so nobody has to port-forward psql to answer "does the ledger balance
 * and where did the money come from". `manager` carries ROLE_ADMIN as well as
 * ROLE_USER, so the single admin gate covers both roles.
 */
export function TreasuryPage() {
  const [report, setReport] = useState<ReconcileReport | null>(null);
  const [accounts, setAccounts] = useState<AccountView[]>([]);
  const [ledger, setLedger] = useState<LedgerEntryView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = () => {
    setLoading(true);
    Promise.all([api.reconcileBalances(), api.getBalanceAccounts(), api.getBalanceLedger()])
      .then(([r, a, l]) => {
        setReport(r);
        setAccounts(a);
        setLedger(l);
        setError(null);
      })
      .catch((e) => setError(e instanceof Error ? e.message : String(e)))
      .finally(() => setLoading(false));
  };

  useEffect(load, []);

  // The feed carries account ids, not usernames — the account list is small, so
  // it is resolved here rather than joined server-side.
  const nameOf = (id: number) => accounts.find((a) => a.id === id)?.username ?? `#${id}`;

  const house = accounts.filter((a) => a.kind === 'HOUSE');
  const users = accounts.filter((a) => a.kind === 'USER');

  return (
    <div>
      <p className="text-xs uppercase tracking-[0.3em] text-terracotta">Administration</p>
      <h1 className="mt-3 font-display text-[36px] leading-tight text-cocoa lg:text-[48px]">Treasury</h1>
      <p className="mt-4 max-w-2xl text-sm leading-relaxed text-cocoa/70">
        The platform's own books. Every movement is a debit and an equal credit, so the ledger
        must always sum to zero.
      </p>

      {error && (
        <p role="status" className="mt-6 border-l-2 border-terracotta bg-terracotta/10 px-4 py-3 text-sm text-terracotta">
          {error}
        </p>
      )}

      {loading && <p className="mt-8 text-sm text-cocoa/60">Loading…</p>}

      {report && (
        <section
          className={`mt-8 border-l-2 bg-white/60 p-6 ${
            report.balanced ? 'border-sage' : 'border-terracotta'
          }`}
        >
          <p className={`font-display text-[24px] ${report.balanced ? 'text-cocoa' : 'text-terracotta'}`}>
            {report.balanced ? 'The books balance.' : 'The books do not balance'}
          </p>
          {!report.balanced && (
            <p className="mt-2 text-sm text-terracotta">
              Money was created or destroyed outside top-up and gifting. This is a bug that has
              already moved money — do not gift or transfer until it is understood.
            </p>
          )}

          <dl className="mt-6 grid grid-cols-2 gap-6 sm:grid-cols-3">
            <Stat label="Ledger sum" value={chf(report.ledgerSumMinor / 100)} bad={report.ledgerSumMinor !== 0} />
            <Stat label="Held by users" value={chf(report.userTotalMinor / 100)} />
            <Stat label="Issued — backed" value={chf(report.backedIssuedMinor / 100)} />
            <Stat label="Issued — conjured" value={chf(report.unbackedIssuedMinor / 100)} />
            <Stat label="Spent on orders" value={chf(report.redeemedMinor / 100)} />
            <Stat
              label="Credit outstanding"
              value={chf(report.creditOutstandingMinor / 100)}
              bad={report.creditOutstandingMinor > 0}
            />
          </dl>

          {report.drift.length > 0 && (
            <p className="mt-6 text-sm text-terracotta">
              {report.drift.length} account(s) drifted from their entries:{' '}
              {report.drift
                .map((d) => `${d.username} (cached ${d.cachedMinor}, ledger ${d.ledgerSumMinor})`)
                .join('; ')}
            </p>
          )}

          <button
            onClick={load}
            className="mt-6 px-6 py-3 text-xs uppercase tracking-[0.14em] border border-cocoa/20 text-cocoa transition-colors hover:bg-cocoa/10"
          >
            Re-check
          </button>
        </section>
      )}

      <h2 className="mt-12 font-display text-[24px] text-cocoa">House accounts</h2>
      <p className="mt-2 text-sm text-cocoa/60">
        A house account's negative balance is what it has issued. <code>house:gift</code> is
        credit conjured out of nothing — the number worth watching.
      </p>
      <AccountList rows={house} />

      <h2 className="mt-12 font-display text-[24px] text-cocoa">User accounts</h2>
      <AccountList rows={users} />

      <h2 className="mt-12 font-display text-[24px] text-cocoa">Every movement</h2>
      {ledger.length === 0 ? (
        <p className="mt-4 text-sm text-cocoa/60">Nothing has moved yet.</p>
      ) : (
        <ul className="mt-4 divide-y divide-cocoa/10 border-y border-cocoa/10">
          {ledger.map((e) => (
            <li key={e.id} className="flex items-baseline justify-between gap-4 py-4">
              <span className="min-w-0">
                <span className="block text-sm text-cocoa">
                  {nameOf(e.accountId)} · {KIND_LABEL[e.kind] ?? e.kind}
                </span>
                <span className="block truncate text-xs text-cocoa/50">
                  {new Date(e.createdAt).toLocaleString()}
                  {e.memo ? ` · ${e.memo}` : e.reference ? ` · ${e.reference}` : ''}
                </span>
              </span>
              <span
                className={`shrink-0 text-sm font-semibold ${
                  e.amountMinor < 0 ? 'text-terracotta' : 'text-sage'
                }`}
              >
                {e.amountMinor < 0 ? '−' : '+'} {chf(Math.abs(e.amountChf))}
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function Stat({ label, value, bad }: { label: string; value: string; bad?: boolean }) {
  return (
    <div>
      <dt className="text-xs uppercase tracking-[0.14em] text-cocoa/50">{label}</dt>
      <dd className={`mt-1 text-sm font-semibold ${bad ? 'text-terracotta' : 'text-cocoa'}`}>{value}</dd>
    </div>
  );
}

function AccountList({ rows }: { rows: AccountView[] }) {
  if (rows.length === 0) return <p className="mt-4 text-sm text-cocoa/60">None.</p>;
  return (
    <ul className="mt-4 divide-y divide-cocoa/10 border-y border-cocoa/10">
      {rows.map((a) => (
        <li key={a.id} className="flex items-baseline justify-between gap-4 py-3">
          <span className="truncate text-sm text-cocoa">{a.username}</span>
          <span
            className={`shrink-0 text-sm font-semibold ${
              a.balanceMinor < 0 ? 'text-terracotta' : 'text-cocoa'
            }`}
          >
            {chf(a.balanceChf)}
          </span>
        </li>
      ))}
    </ul>
  );
}
