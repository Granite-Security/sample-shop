import { useEffect, useMemo, useState } from 'react';
import { api } from '../api';
import type { AccrualReport, MoneySupplyReport, RevenueReport } from '../types';

const chf = (minor: number) => `CHF ${(minor / 100).toFixed(2)}`;
const pct = (rate: number) => `${(rate * 100).toFixed(0)}%`;

type Granularity = 'year' | 'month' | 'week';

/**
 * The revenue reports (docs/finance/accounting.md, Part II), for admins.
 *
 * Four questions and nothing else: did we sell, how much money did we conjure,
 * was the conjured money spent, and what did we actually earn.
 *
 * Three sources, kept in visually distinct sections on purpose. The cash panel is
 * every order whichever provider paid; the money-supply panel is balance-paid
 * orders only. They overlap and neither contains the other, so adding them
 * together is meaningless — and it is the most likely bug on this page, which is
 * why the copy says so rather than trusting a future reader to remember.
 *
 * No chart library. Bars are a div with a percentage width, matching Treasury's
 * existing table-and-`--primary` styling: adding recharts for six bars is 200 kB
 * for a rounding of the visual.
 *
 * Every table reads newest-first. The services return buckets ascending (three
 * `ORDER BY 1`s), which put the current month at the bottom of a twelve-row
 * table — the one row anybody opens this page to read. Reversed here rather than
 * in SQL: it is a presentation choice, the reports are small, and the three
 * backends have other consumers.
 */
export default function Revenues() {
  const [granularity, setGranularity] = useState<Granularity>('month');
  const [currency, setCurrency] = useState('CHF');
  const [currencies, setCurrencies] = useState<string[]>([]);
  const [cash, setCash] = useState<RevenueReport | null>(null);
  const [supply, setSupply] = useState<MoneySupplyReport | null>(null);
  const [accrual, setAccrual] = useState<AccrualReport | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.reports.currencies()
      .then(r => setCurrencies(r.currencies))
      // A missing currency list is not worth failing the page for: the selector
      // simply stays hidden and CHF is assumed.
      .catch(() => setCurrencies([]));
  }, []);

  useEffect(() => {
    setLoading(true);
    Promise.all([
      api.reports.cash(granularity, currency),
      api.reports.moneySupply(granularity),
      // The books may not be up yet, or may hold nothing. That is not a page
      // error — the cash view stands alone and is worth showing without it.
      api.reports.accrual(granularity).catch(() => null),
    ])
      .then(([c, s, a]) => {
        setCash(c);
        setSupply(s);
        setAccrual(a);
        setError(null);
      })
      .catch(e => setError(e instanceof Error ? e.message : String(e)))
      .finally(() => setLoading(false));
  }, [granularity, currency]);

  // Newest first. Every bucket key is an ISO date — a week bucket is its Monday,
  // not '2026-W32' — so a lexical compare is a chronological one.
  const cashBuckets = useMemo(() => newestFirst(cash?.buckets), [cash]);
  const accrualBuckets = useMemo(() => newestFirst(accrual?.buckets), [accrual]);
  const supplyBuckets = useMemo(() => newestFirst(supply?.buckets), [supply]);

  const maxNet = Math.max(1, ...cashBuckets.map(b => Math.abs(b.netMinor)));
  const maxSupply = Math.max(1, ...supplyBuckets.map(b => b.giftedMinor + b.toppedUpMinor));

  return (
    <div>
      <h1 style={{ marginBottom: 4 }}>Revenues</h1>
      <p style={{ color: 'var(--text-secondary)', marginBottom: 16 }}>
        Management view, IFRS-informed — not IFRS financial statements. Estimated lines say so.
      </p>

      <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 20 }}>
        {(['year', 'month', 'week'] as Granularity[]).map(g => (
          <button
            key={g}
            className="btn"
            onClick={() => setGranularity(g)}
            style={{
              background: granularity === g ? 'var(--primary)' : 'transparent',
              color: granularity === g ? 'white' : 'var(--text)',
              border: '1px solid var(--border)',
              textTransform: 'capitalize',
            }}
          >
            {g}
          </button>
        ))}
        {/* Only when more than one currency has orders: shop priced in USD before
            the 2026-08-01 cutover, and the two must never appear in one total. */}
        {currencies.length > 1 && (
          <select value={currency} onChange={e => setCurrency(e.target.value)} style={{ marginLeft: 8 }}>
            {currencies.map(c => <option key={c} value={c}>{c}</option>)}
          </select>
        )}
      </div>

      {error && <p className="error">{error}</p>}
      {loading && <p style={{ color: 'var(--text-secondary)' }}>Loading…</p>}

      {cash && (
        <Section
          title="Cash — what moved"
          note="Every order, whichever provider paid. Gross counts when the order was paid; a refund
                counts when the money actually went back, so a month can be net-negative and that is
                correct."
        >
          <table className="orders-table" style={{ width: '100%' }}>
            <thead>
              <tr><th>Bucket</th><th>Gross</th><th>Refunded</th><th>Net</th><th>Orders</th><th /></tr>
            </thead>
            <tbody>
              {cashBuckets.map((b, i) => (
                <tr key={b.bucket}>
                  <td>{b.label}{i === 0 && <Current />}</td>
                  <td>{chf(b.grossMinor)}</td>
                  <td>{b.refundedMinor ? `−${chf(b.refundedMinor)}` : '—'}</td>
                  <td style={{ color: b.netMinor < 0 ? 'var(--danger)' : undefined }}>{chf(b.netMinor)}</td>
                  <td>{b.orderCount}</td>
                  <td style={{ width: '30%' }}><Bar value={Math.abs(b.netMinor)} max={maxNet} /></td>
                </tr>
              ))}
            </tbody>
            <tfoot>
              <tr style={{ fontWeight: 600 }}>
                <td>Total</td>
                <td>{chf(cash.totals.grossMinor)}</td>
                <td>{chf(cash.totals.refundedMinor)}</td>
                <td>{chf(cash.totals.netMinor)}</td>
                <td>{cash.totals.orderCount}</td>
                <td />
              </tr>
            </tfoot>
          </table>
          {cash.totals.returnsPendingMinor > 0 && (
            <p style={{ color: 'var(--text-secondary)', fontSize: '0.85rem', marginTop: 8 }}>
              {chf(cash.totals.returnsPendingMinor)} of returns requested but not yet settled. Shown
              here rather than subtracted: until the money moves, nothing has moved.
            </p>
          )}
          <p style={{ color: 'var(--text-secondary)', fontSize: '0.8rem', marginTop: 8 }}>
            Orders placed before 2026-08-01 were priced in USD, and timestamps for orders predating
            the money-date migration are approximations.
          </p>
        </Section>
      )}

      {accrual && (
        <Section
          title="Earned — accrual"
          note="What we earned, as booked: recognised on delivery, not on payment. Gifted credit and
                expected returns are shown as deductions rather than netted away, so the discount
                stays visible."
        >
          <table className="orders-table" style={{ width: '100%' }}>
            <thead>
              <tr>
                <th>Bucket</th><th>Revenue (gross)</th><th>Gift credit</th>
                <th>Expected returns</th><th>Net</th><th>Delivered</th>
              </tr>
            </thead>
            <tbody>
              {accrualBuckets.map((b, i) => {
                const beforeBooks = b.bucket < accrual.booksOpenedOn;
                return (
                  <tr key={b.bucket} style={{ opacity: beforeBooks ? 0.5 : 1 }}>
                    <td>
                      {b.label}
                      {i === 0 && <Current />}
                      {b.periodStatus === 'CLOSED' && (
                        <span title="This period is closed; a late fact posts to the open one"
                              style={{ marginLeft: 6, fontSize: '0.75rem', color: 'var(--text-secondary)' }}>
                          frozen
                        </span>
                      )}
                    </td>
                    {beforeBooks ? (
                      <td colSpan={5} style={{ fontStyle: 'italic', color: 'var(--text-secondary)' }}>
                        not yet booked — the books open on {accrual.booksOpenedOn}
                      </td>
                    ) : (
                      <>
                        <td>{chf(b.revenueGrossMinor)}</td>
                        <td>{b.contraGiftMinor ? `−${chf(b.contraGiftMinor)}` : '—'}</td>
                        <td>{b.contraReturnsMinor ? `−${chf(b.contraReturnsMinor)}` : '—'}</td>
                        <td style={{ fontWeight: 600 }}>{chf(b.netRevenueMinor)}</td>
                        <td>{b.deliveredCount}</td>
                      </>
                    )}
                  </tr>
                );
              })}
            </tbody>
            <tfoot>
              <tr style={{ fontWeight: 600 }}>
                <td>Total</td>
                <td>{chf(accrual.totals.revenueGrossMinor)}</td>
                <td>{chf(accrual.totals.contraGiftMinor)}</td>
                <td>{chf(accrual.totals.contraReturnsMinor)}</td>
                <td>{chf(accrual.totals.netRevenueMinor)}</td>
                <td>{accrual.totals.deliveredCount}</td>
              </tr>
            </tfoot>
          </table>
        </Section>
      )}

      {supply && (
        <Section
          title="Money we created"
          note="Gifted credit is conjured from nothing; a top-up is backed by a real payment. Neither
                is revenue — a top-up is money we owe in goods or a refund."
        >
          <table className="orders-table" style={{ width: '100%' }}>
            <thead>
              <tr><th>Bucket</th><th>Gifted</th><th>Topped up</th><th /></tr>
            </thead>
            <tbody>
              {supplyBuckets.map((b, i) => (
                <tr key={b.bucket}>
                  <td>{b.label}{i === 0 && <Current />}</td>
                  <td>{chf(b.giftedMinor)}</td>
                  <td>{chf(b.toppedUpMinor)}</td>
                  <td style={{ width: '40%' }}>
                    <Bar value={b.giftedMinor + b.toppedUpMinor} max={maxSupply} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>

          <h3 style={{ margin: '20px 0 4px' }}>Was it spent?</h3>
          <p style={{ color: 'var(--text-secondary)', fontSize: '0.85rem', marginBottom: 8 }}>
            Of everything spent on orders from a balance. Gift-first: a spend draws conjured francs
            before it touches real ones. Gift + backed + credit always equals the spend.
          </p>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', gap: 12 }}>
            <Stat label="Gifted, unspent" value={chf(supply.totals.giftedOutstandingMinor)} strong />
            <Stat label="Spent — conjured" value={chf(supply.totals.spentFromGiftMinor)} />
            <Stat label="Spent — backed" value={chf(supply.totals.spentFromBackedMinor)} />
            <Stat label="Spent — on credit" value={chf(supply.totals.spentFromCreditMinor)} />
          </div>
          <p style={{ color: 'var(--text-secondary)', fontSize: '0.8rem', marginTop: 10 }}>
            <strong>Do not add this section to the cash table above.</strong> That one is every order,
            whichever provider paid; this one is balance-paid orders only. They overlap and neither
            contains the other.
          </p>
        </Section>
      )}

      {accrual?.creditLoss && (
        <Section
          title="Expected credit loss"
          note={`An estimate, not a measurement — loss rates set on ${accrual.creditLoss.asOf}. A position
                 as of today rather than a figure for a month, and never subtracted from revenue.`}
          estimated
        >
          <table className="orders-table" style={{ width: '100%' }}>
            <thead>
              <tr><th>Age of receivable</th><th>Loss rate</th><th>Exposure</th><th>Allowance</th></tr>
            </thead>
            <tbody>
              {accrual.creditLoss.bands.map((band, i) => (
                <tr key={i}>
                  <td>{band.maxAgeDays === null ? 'over 90 days' : `under ${band.maxAgeDays} days`}</td>
                  <td>{pct(band.lossRate)}</td>
                  <td>{chf(band.exposureMinor)}</td>
                  <td>{chf(band.allowanceMinor)}</td>
                </tr>
              ))}
            </tbody>
            <tfoot>
              <tr style={{ fontWeight: 600 }}>
                <td>Total</td><td />
                <td>{chf(accrual.creditLoss.exposureMinor)}</td>
                <td>{chf(accrual.creditLoss.allowanceMinor)}</td>
              </tr>
            </tfoot>
          </table>
          {accrual.creditLoss.allowanceMinor > 0 && (
            <p style={{ color: 'var(--text-secondary)', fontSize: '0.8rem', marginTop: 8 }}>
              A large allowance is a product finding, not a reporting one: it means credit is being
              extended to people who will not repay it, and the fix is a credit limit rather than a
              change to these rates.
            </p>
          )}
        </Section>
      )}
    </div>
  );
}

/**
 * Newest bucket first. The window always ends at today (`to` defaults to
 * tomorrow, exclusive) and gaps are filled server-side with zero rows, so the
 * first row after this sort is the period in progress — which is what <Current />
 * labels, without this page having to work out where Zurich's month boundary is.
 */
function newestFirst<T extends { bucket: string }>(buckets: T[] | undefined): T[] {
  return [...(buckets ?? [])].sort((a, b) => (a.bucket < b.bucket ? 1 : a.bucket > b.bucket ? -1 : 0));
}

/** Marks the period in progress: its numbers are partial and will still move. */
function Current() {
  return (
    <span
      title="The period in progress — these numbers are still moving."
      style={{ marginLeft: 6, fontSize: '0.7rem', textTransform: 'uppercase',
               letterSpacing: '0.05em', color: 'var(--primary)' }}
    >
      current
    </span>
  );
}

function Section({ title, note, estimated, children }: {
  title: string;
  note: string;
  estimated?: boolean;
  children: React.ReactNode;
}) {
  return (
    <section
      style={{
        border: '1px solid var(--border)',
        // Estimated sections are visually distinct from measured ones. An assumed
        // number rendered like a measured one is this design's stated failure mode.
        borderLeft: `4px solid ${estimated ? 'var(--text-secondary)' : 'var(--primary)'}`,
        borderRadius: 'var(--radius)',
        padding: '1rem',
        marginBottom: '1.5rem',
      }}
    >
      <h2 style={{ margin: '0 0 4px' }}>
        {title}
        {estimated && (
          <span style={{ marginLeft: 8, fontSize: '0.7rem', textTransform: 'uppercase',
                         color: 'var(--text-secondary)', letterSpacing: '0.05em' }}>
            estimate
          </span>
        )}
      </h2>
      <p style={{ color: 'var(--text-secondary)', fontSize: '0.85rem', marginBottom: 12 }}>{note}</p>
      {children}
    </section>
  );
}

/** A div with a percentage width. This page needs no more than that. */
function Bar({ value, max }: { value: number; max: number }) {
  return (
    <div style={{ background: 'var(--border)', borderRadius: 3, height: 8, width: '100%' }}>
      <div style={{
        background: 'var(--primary)',
        borderRadius: 3,
        height: '100%',
        width: `${Math.min(100, (value / max) * 100)}%`,
      }} />
    </div>
  );
}

function Stat({ label, value, strong }: { label: string; value: string; strong?: boolean }) {
  return (
    <div style={{ border: '1px solid var(--border)', borderRadius: 'var(--radius)', padding: '0.6rem' }}>
      <div style={{ fontSize: '0.75rem', color: 'var(--text-secondary)' }}>{label}</div>
      <div style={{ fontSize: '1.1rem', fontWeight: strong ? 700 : 500 }}>{value}</div>
    </div>
  );
}
