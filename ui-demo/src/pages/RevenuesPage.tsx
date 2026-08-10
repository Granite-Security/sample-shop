import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { api } from '../api';
import type { AccrualReport, MoneySupplyReport, RevenueReport } from '../types';

const chf = (minor: number) => `CHF ${(minor / 100).toFixed(2)}`;
const pct = (rate: number) => `${(rate * 100).toFixed(0)}%`;

type Granularity = 'year' | 'month' | 'week';

/**
 * The revenue reports (docs/finance/accounting.md, Part II), for admins — the
 * ui-demo counterpart of ui-shop/src/pages/Revenues.tsx, same three calls and
 * the same warnings, in the boutique's design language.
 *
 * Three sources, kept in visually distinct sections on purpose. The cash panel
 * is every order whichever provider paid; the money-supply panel is balance-paid
 * orders only. They overlap and neither contains the other, so adding them
 * together is meaningless — the copy says so rather than trusting a future
 * reader to remember.
 *
 * Every table reads newest-first: the services return buckets ascending, which
 * would bury the period in progress at the bottom of a twelve-row table.
 */
export function RevenuesPage() {
  const [granularity, setGranularity] = useState<Granularity>('month');
  const [currency, setCurrency] = useState('CHF');
  const [currencies, setCurrencies] = useState<string[]>([]);
  const [cash, setCash] = useState<RevenueReport | null>(null);
  const [supply, setSupply] = useState<MoneySupplyReport | null>(null);
  const [accrual, setAccrual] = useState<AccrualReport | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api
      .revenueCurrencies()
      .then((r) => setCurrencies(r.currencies))
      // A missing currency list is not worth failing the page for: the selector
      // simply stays hidden and CHF is assumed.
      .catch(() => setCurrencies([]));
  }, []);

  useEffect(() => {
    setLoading(true);
    Promise.all([
      api.revenueCash(granularity, currency),
      api.moneySupply(granularity),
      // The books may not be up yet, or may hold nothing. That is not a page
      // error — the cash view stands alone and is worth showing without it.
      api.revenueAccrual(granularity).catch(() => null),
    ])
      .then(([c, s, a]) => {
        setCash(c);
        setSupply(s);
        setAccrual(a);
        setError(null);
      })
      .catch((e) => setError(e instanceof Error ? e.message : String(e)))
      .finally(() => setLoading(false));
  }, [granularity, currency]);

  const cashBuckets = useMemo(() => newestFirst(cash?.buckets), [cash]);
  const accrualBuckets = useMemo(() => newestFirst(accrual?.buckets), [accrual]);
  const supplyBuckets = useMemo(() => newestFirst(supply?.buckets), [supply]);

  const maxNet = Math.max(1, ...cashBuckets.map((b) => Math.abs(b.netMinor)));
  const maxSupply = Math.max(1, ...supplyBuckets.map((b) => b.giftedMinor + b.toppedUpMinor));

  return (
    <div>
      <p className="text-xs uppercase tracking-[0.3em] text-terracotta">Administration</p>
      <h1 className="mt-3 font-display text-[36px] leading-tight text-cocoa lg:text-[48px]">
        Revenues
      </h1>
      <p className="mt-4 max-w-2xl text-sm leading-relaxed text-cocoa/70">
        Management view, IFRS-informed — not IFRS financial statements. Estimated lines say so.
        Newest period first.
      </p>

      <div className="mt-6 flex flex-wrap items-center gap-2">
        {(['year', 'month', 'week'] as Granularity[]).map((g) => (
          <button
            key={g}
            onClick={() => setGranularity(g)}
            className={`px-6 py-2.5 text-xs uppercase tracking-[0.18em] transition-colors duration-300 ${
              granularity === g
                ? 'bg-cocoa text-ivory'
                : 'border border-cocoa/30 text-cocoa hover:border-cocoa'
            }`}
          >
            {g}
          </button>
        ))}
        {/* Only when more than one currency has orders: shop priced in USD before
            the 2026-08-01 cutover, and the two must never appear in one total. */}
        {currencies.length > 1 && (
          <select
            value={currency}
            onChange={(e) => setCurrency(e.target.value)}
            className="ml-2 border border-cocoa/20 bg-white/70 px-3 py-2 text-sm text-cocoa focus:border-gold focus:outline-none"
          >
            {currencies.map((c) => (
              <option key={c} value={c}>
                {c}
              </option>
            ))}
          </select>
        )}
      </div>

      {error && (
        <p
          role="status"
          className="mt-6 border-l-2 border-terracotta bg-terracotta/10 px-4 py-3 text-sm text-terracotta"
        >
          {error}
        </p>
      )}
      {loading && <p className="mt-6 text-sm text-cocoa/50">Loading…</p>}

      {cash && (
        <Section
          title="Cash — what moved"
          note="Every order, whichever provider paid. Gross counts when the order was paid; a refund
                counts when the money actually went back, so a month can be net-negative and that is
                correct."
        >
          <Table head={['Period', 'Gross', 'Refunded', 'Net', 'Orders', '']}>
            {cashBuckets.map((b, i) => (
              <tr key={b.bucket} className="text-cocoa">
                <Td>
                  {b.label}
                  {i === 0 && <Current />}
                </Td>
                <Td>{chf(b.grossMinor)}</Td>
                <Td>{b.refundedMinor ? `−${chf(b.refundedMinor)}` : '—'}</Td>
                <Td className={b.netMinor < 0 ? 'text-terracotta' : undefined}>{chf(b.netMinor)}</Td>
                <Td>{b.orderCount}</Td>
                <Td className="w-[28%]">
                  <Bar value={Math.abs(b.netMinor)} max={maxNet} />
                </Td>
              </tr>
            ))}
            <Total
              cells={[
                'Total',
                chf(cash.totals.grossMinor),
                chf(cash.totals.refundedMinor),
                chf(cash.totals.netMinor),
                cash.totals.orderCount,
                '',
              ]}
            />
          </Table>
          {cash.totals.returnsPendingMinor > 0 && (
            <p className="mt-3 text-sm text-cocoa/60">
              {chf(cash.totals.returnsPendingMinor)} of returns requested but not yet settled. Shown
              here rather than subtracted: until the money moves, nothing has moved.
            </p>
          )}
          <p className="mt-2 text-xs text-cocoa/50">
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
          <Table
            head={['Period', 'Revenue (gross)', 'Gift credit', 'Expected returns', 'Net', 'Delivered']}
          >
            {accrualBuckets.map((b, i) => {
              const beforeBooks = b.bucket < accrual.booksOpenedOn;
              return (
                <tr key={b.bucket} className={`text-cocoa ${beforeBooks ? 'opacity-50' : ''}`}>
                  <Td>
                    {b.label}
                    {i === 0 && <Current />}
                    {b.periodStatus === 'CLOSED' && (
                      <span
                        title="This period is closed; a late fact posts to the open one"
                        className="ml-2 text-[10px] uppercase tracking-[0.12em] text-cocoa/40"
                      >
                        frozen
                      </span>
                    )}
                  </Td>
                  {beforeBooks ? (
                    <td colSpan={5} className="py-3 pr-4 text-sm italic text-cocoa/50">
                      not yet booked — the books open on {accrual.booksOpenedOn}
                    </td>
                  ) : (
                    <>
                      <Td>{chf(b.revenueGrossMinor)}</Td>
                      <Td>{b.contraGiftMinor ? `−${chf(b.contraGiftMinor)}` : '—'}</Td>
                      <Td>{b.contraReturnsMinor ? `−${chf(b.contraReturnsMinor)}` : '—'}</Td>
                      <Td className="font-semibold">{chf(b.netRevenueMinor)}</Td>
                      <Td>{b.deliveredCount}</Td>
                    </>
                  )}
                </tr>
              );
            })}
            <Total
              cells={[
                'Total',
                chf(accrual.totals.revenueGrossMinor),
                chf(accrual.totals.contraGiftMinor),
                chf(accrual.totals.contraReturnsMinor),
                chf(accrual.totals.netRevenueMinor),
                accrual.totals.deliveredCount,
              ]}
            />
          </Table>
        </Section>
      )}

      {supply && (
        <Section
          title="Money we created"
          note="Gifted credit is conjured from nothing; a top-up is backed by a real payment. Neither
                is revenue — a top-up is money we owe in goods or a refund."
        >
          <Table head={['Period', 'Gifted', 'Topped up', '']}>
            {supplyBuckets.map((b, i) => (
              <tr key={b.bucket} className="text-cocoa">
                <Td>
                  {b.label}
                  {i === 0 && <Current />}
                </Td>
                <Td>{chf(b.giftedMinor)}</Td>
                <Td>{chf(b.toppedUpMinor)}</Td>
                <Td className="w-[40%]">
                  <Bar value={b.giftedMinor + b.toppedUpMinor} max={maxSupply} />
                </Td>
              </tr>
            ))}
          </Table>

          <h3 className="mt-8 font-display text-[20px] text-cocoa">Was it spent?</h3>
          <p className="mt-1 text-sm text-cocoa/60">
            Of everything spent on orders from a balance. Gift-first: a spend draws conjured francs
            before it touches real ones. Gift + backed + credit always equals the spend.
          </p>
          <div className="mt-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <Stat label="Gifted, unspent" value={chf(supply.totals.giftedOutstandingMinor)} strong />
            <Stat label="Spent — conjured" value={chf(supply.totals.spentFromGiftMinor)} />
            <Stat label="Spent — backed" value={chf(supply.totals.spentFromBackedMinor)} />
            <Stat label="Spent — on credit" value={chf(supply.totals.spentFromCreditMinor)} />
          </div>
          <p className="mt-4 text-xs text-cocoa/50">
            <strong className="text-cocoa/70">Do not add this section to the cash table above.</strong>{' '}
            That one is every order, whichever provider paid; this one is balance-paid orders only.
            They overlap and neither contains the other.
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
          <Table head={['Age of receivable', 'Loss rate', 'Exposure', 'Allowance']}>
            {accrual.creditLoss.bands.map((band, i) => (
              <tr key={i} className="text-cocoa">
                <Td>{band.maxAgeDays === null ? 'over 90 days' : `under ${band.maxAgeDays} days`}</Td>
                <Td>{pct(band.lossRate)}</Td>
                <Td>{chf(band.exposureMinor)}</Td>
                <Td>{chf(band.allowanceMinor)}</Td>
              </tr>
            ))}
            <Total
              cells={[
                'Total',
                '',
                chf(accrual.creditLoss.exposureMinor),
                chf(accrual.creditLoss.allowanceMinor),
              ]}
            />
          </Table>
          {accrual.creditLoss.allowanceMinor > 0 && (
            <p className="mt-3 text-xs text-cocoa/50">
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
 * Every bucket key is an ISO date — a week bucket is its Monday, not '2026-W32'
 * — so a lexical compare is a chronological one.
 */
function newestFirst<T extends { bucket: string }>(buckets: T[] | undefined): T[] {
  return [...(buckets ?? [])].sort((a, b) => (a.bucket < b.bucket ? 1 : a.bucket > b.bucket ? -1 : 0));
}

/** Marks the period in progress: its numbers are partial and will still move. */
function Current() {
  return (
    <span
      title="The period in progress — these numbers are still moving."
      className="ml-2 text-[10px] uppercase tracking-[0.12em] text-terracotta"
    >
      current
    </span>
  );
}

function Section({ title, note, estimated, children }: {
  title: string;
  note: string;
  estimated?: boolean;
  children: ReactNode;
}) {
  return (
    <section
      // Estimated sections are visually distinct from measured ones. An assumed
      // number rendered like a measured one is this design's stated failure mode.
      className={`mt-10 border-l-2 pl-5 ${estimated ? 'border-cocoa/30' : 'border-gold'}`}
    >
      <h2 className="font-display text-[24px] text-cocoa">
        {title}
        {estimated && (
          <span className="ml-3 text-[10px] uppercase tracking-[0.16em] text-cocoa/40">estimate</span>
        )}
      </h2>
      <p className="mt-1 max-w-3xl text-sm text-cocoa/60">{note}</p>
      <div className="mt-4">{children}</div>
    </section>
  );
}

function Table({ head, children }: { head: string[]; children: ReactNode }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[640px] text-left text-sm">
        <thead>
          <tr className="border-b border-cocoa/15 text-xs uppercase tracking-[0.16em] text-cocoa/50">
            {head.map((h, i) => (
              <th key={i} className="py-3 pr-4 font-normal">
                {h}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-cocoa/10">{children}</tbody>
      </table>
    </div>
  );
}

function Td({ children, className }: { children?: ReactNode; className?: string }) {
  return <td className={`py-3 pr-4 ${className ?? ''}`}>{children}</td>;
}

function Total({ cells }: { cells: (string | number)[] }) {
  return (
    <tr className="border-t border-cocoa/20 font-semibold text-cocoa">
      {cells.map((c, i) => (
        <td key={i} className="py-3 pr-4">
          {c}
        </td>
      ))}
    </tr>
  );
}

/** A div with a percentage width. This page needs no more than that. */
function Bar({ value, max }: { value: number; max: number }) {
  return (
    <div className="h-2 w-full rounded-full bg-cocoa/10">
      <div
        className="h-full rounded-full bg-gold"
        style={{ width: `${Math.min(100, (value / max) * 100)}%` }}
      />
    </div>
  );
}

function Stat({ label, value, strong }: { label: string; value: string; strong?: boolean }) {
  return (
    <div className="border border-cocoa/15 px-4 py-3">
      <div className="text-[10px] uppercase tracking-[0.16em] text-cocoa/50">{label}</div>
      <div className={`mt-1 text-cocoa ${strong ? 'font-display text-xl' : 'text-base'}`}>{value}</div>
    </div>
  );
}
