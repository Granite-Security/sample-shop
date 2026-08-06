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
 * This exists so nobody has to port-forward psql to answer "does the ledger
 * balance and where did the money come from". `manager` carries ROLE_ADMIN as
 * well as ROLE_USER, so the single admin gate covers both roles.
 */
export default function Treasury() {
  const [report, setReport] = useState<ReconcileReport | null>(null);
  const [accounts, setAccounts] = useState<AccountView[]>([]);
  const [ledger, setLedger] = useState<LedgerEntryView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = () => {
    setLoading(true);
    Promise.all([api.balance.reconcile(), api.balance.accounts(), api.balance.ledger()])
      .then(([r, a, l]) => {
        setReport(r);
        setAccounts(a);
        setLedger(l);
        setError(null);
      })
      .catch(e => setError(e instanceof Error ? e.message : String(e)))
      .finally(() => setLoading(false));
  };

  useEffect(load, []);

  // The feed carries account ids, not usernames — the account list is small, so
  // it is resolved here rather than joined server-side.
  const nameOf = (id: number) => accounts.find(a => a.id === id)?.username ?? `#${id}`;

  const house = accounts.filter(a => a.kind === 'HOUSE');
  const users = accounts.filter(a => a.kind === 'USER');

  if (loading) return <div className="spinner" style={{ margin: '0 auto' }} />;

  return (
    <div>
      <h1 style={{ marginBottom: 4 }}>Treasury</h1>
      <p style={{ color: 'var(--text-secondary)', marginBottom: 16 }}>
        The platform's own books. Every movement is a debit and an equal credit, so the
        ledger must always sum to zero.
      </p>

      {error && <p className="error" style={{ marginBottom: 12 }}>{error}</p>}

      {report && (
        <div
          style={{
            border: `1px solid ${report.balanced ? 'var(--border)' : 'var(--danger)'}`,
            borderLeft: `4px solid ${report.balanced ? 'var(--primary)' : 'var(--danger)'}`,
            borderRadius: 'var(--radius)',
            padding: '1rem',
            marginBottom: '1.5rem',
          }}
        >
          <strong style={{ color: report.balanced ? 'var(--primary)' : 'var(--danger)' }}>
            {report.balanced ? 'The books balance.' : 'THE BOOKS DO NOT BALANCE'}
          </strong>
          {!report.balanced && (
            <p style={{ color: 'var(--danger)', margin: '4px 0' }}>
              Money was created or destroyed outside top-up and gifting. This is a bug that
              has already moved money — do not gift or transfer until it is understood.
            </p>
          )}
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))',
              gap: 12,
              marginTop: 12,
            }}
          >
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
          </div>

          {report.drift.length > 0 && (
            <p className="error" style={{ marginTop: 12 }}>
              {report.drift.length} account(s) drifted from their entries:{' '}
              {report.drift.map(d => `${d.username} (cached ${d.cachedMinor}, ledger ${d.ledgerSumMinor})`).join('; ')}
            </p>
          )}

          <button className="btn" style={{ marginTop: 12 }} onClick={load}>
            Re-check
          </button>
        </div>
      )}

      <h2 style={{ margin: '0 0 0.5rem' }}>House accounts</h2>
      <p style={{ color: 'var(--text-secondary)', fontSize: '0.85rem', marginBottom: 8 }}>
        A house account's negative balance is what it has issued. <code>house:gift</code> is
        credit conjured out of nothing — the number worth watching.
      </p>
      <AccountTable rows={house} />

      <h2 style={{ margin: '1.5rem 0 0.5rem' }}>User accounts</h2>
      <AccountTable rows={users} />

      <h2 style={{ margin: '1.5rem 0 0.5rem' }}>Every movement</h2>
      {ledger.length === 0 ? (
        <p style={{ color: 'var(--text-secondary)' }}>Nothing has moved yet.</p>
      ) : (
        <table className="orders-table" style={{ width: '100%' }}>
          <thead>
            <tr>
              <th>When</th>
              <th>Account</th>
              <th>What</th>
              <th>Details</th>
              <th style={{ textAlign: 'right' }}>Amount</th>
            </tr>
          </thead>
          <tbody>
            {ledger.map(e => (
              <tr key={e.id}>
                <td style={{ whiteSpace: 'nowrap' }}>{new Date(e.createdAt).toLocaleString()}</td>
                <td>{nameOf(e.accountId)}</td>
                <td>{KIND_LABEL[e.kind] ?? e.kind}</td>
                <td style={{ color: 'var(--text-secondary)' }}>{e.memo ?? e.reference ?? ''}</td>
                <td
                  style={{
                    textAlign: 'right',
                    fontWeight: 600,
                    whiteSpace: 'nowrap',
                    color: e.amountMinor < 0 ? 'var(--danger)' : 'var(--primary)',
                  }}
                >
                  {e.amountMinor < 0 ? '−' : '+'} {chf(Math.abs(e.amountChf))}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

function Stat({ label, value, bad }: { label: string; value: string; bad?: boolean }) {
  return (
    <div>
      <div style={{ fontSize: '0.75rem', color: 'var(--text-secondary)' }}>{label}</div>
      <div style={{ fontWeight: 600, color: bad ? 'var(--danger)' : 'var(--text)' }}>{value}</div>
    </div>
  );
}

function AccountTable({ rows }: { rows: AccountView[] }) {
  if (rows.length === 0) return <p style={{ color: 'var(--text-secondary)' }}>None.</p>;
  return (
    <table className="orders-table" style={{ width: '100%' }}>
      <thead>
        <tr>
          <th>Account</th>
          <th style={{ textAlign: 'right' }}>Balance</th>
        </tr>
      </thead>
      <tbody>
        {rows.map(a => (
          <tr key={a.id}>
            <td>{a.username}</td>
            <td
              style={{
                textAlign: 'right',
                fontWeight: 600,
                color: a.balanceMinor < 0 ? 'var(--danger)' : 'var(--text)',
              }}
            >
              {chf(a.balanceChf)}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
