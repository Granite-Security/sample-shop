# `accounting` — the books

Status: **planned, nothing built.** Recommended, but *after* the cash-view report in
`docs/finance/reports.md` §6 steps 1–4, and it takes two sections of that plan with it.

- `finance.md` — how money moves. `balance` is the only writer.
- `reports.md` — what moved, aggregated. Queries over `shop` and `balance`.
- **this** — what we *earned*, booked as journal entries that never change after the fact.

## 1. Why a service, and not another query

Everything in `reports.md` is a `GROUP BY` over live tables. That is right for the cash view
and structurally wrong for accounting, for four reasons:

**Books must not restate themselves.** A query re-derives history on every page load. Orders
walk backwards (`REIMBURSED → RETURNED` is a legal transition), migrations backfill columns,
statuses change months later. So March's revenue changes in June, silently, and nothing
anywhere records that it changed or why. A journal entry is a fact with a posting date: it is
written once and corrected only by another entry. That is not bureaucracy, it is the entire
reason accounting is built the way it is.

**You cannot close a period you compute on demand.** Closing March means March is frozen and a
late fact posts to April as a prior-period adjustment. There is no way to express that in a
`WHERE` clause.

**Policy is currently smeared across three codebases.** `reports.md` §4.9 has the *browser*
subtracting a `balance` figure from a `shop` figure to get contra-revenue. Accounting policy,
executed in React, across two API responses that could have been fetched from different
windows. That is the smell that says "service".

**Estimates are postings, not queries.** The ECL allowance and the return provision are
entries *made on a date under an assumption set*. Recomputing last year's provision with
today's rates is the same disease as above — it silently rewrites history and looks like a
feature.

### 1.1 What it is not

**It never moves money.** `balance` keeps the mandate from `finance.md`: single writer, one
ledger, two doors. `accounting` is a **projection** — it books entries *about* money that has
already moved. Two components that both believe they are the ledger is the failure mode this
sentence exists to prevent. `accounting` has no endpoint that changes a balance, and it is not
in any payment path.

Nor is it a general ledger for the company. It has one entity, one currency and no journals a
human can type by hand.

## 2. Is it easier? No — it is cheaper here than it looks, and it is correct

Honestly: a new service, a new database, a new overlay entry, a CI matrix row and a backfill
story is not *easier* than two SQL queries. What makes it worth it is that the accrual half of
`reports.md` cannot be made correct as queries, only approximate.

What makes it **cheaper than expected in this repo** is that the event backbone already
exists. Four of the five facts the books need are already published, by services already using
the transactional outbox, with an idempotent-consumer precedent in `notification`:

| Topic | Producer | Facts accounting needs |
|---|---|---|
| `orders.events` | shop | `OrderPlaced`, `RefundRequested` |
| `payments.events` | payment | `PaymentSucceeded` (`purpose=ORDER\|TOPUP`), `PaymentFailed`, `PaymentRefunded`, `PaymentRefundFailed` |
| `delivery.events` | delivery | delivery completion — **the accrual recognition point** (`reports.md` §3.1) |
| `shipments.events` | delivery | despatch, if control ever passes at despatch instead |
| **`balance.events`** | **balance — does not exist** | `GiftIssued`, `Spent` with its funding split, `Transferred` |

**One new producer.** `balance` publishes nothing today, and it owns the two facts with no
other source: gift issuance (contra-revenue, §4) and the gift/backed/credit funding split
(`reports.md` §4.7). It needs an outbox — the same pattern shop, payment and delivery already
run, and a good fit: the outbox row commits in the same transaction as the ledger rows, so a
movement and its announcement cannot diverge. It also does not violate "no `.block()` in
`balance`" (`finance.md` §7.2), because the relay is a separate scheduled read.

That is the whole cost of admission. Everything else is a consumer.

## 3. Shape

```
 orders.events ─┐
 payments.events┤
 delivery.events┼──► accounting :8068  ──► journal (append-only)
 balance.events ┘      posting rules          period
   (new outbox)        estimates (scheduled)  chart of accounts
                                │
                                ▼
                    GET /api/accounting/** (ROLE_ADMIN, read-only)
                                │
                        ui-shop /profile/revenues
```

WebFlux + R2DBC + Liquibase, own Postgres, resource server, functional routing — same as
`shop`/`payment`/`balance`. Port **8068** (8060–8067 are taken).

## 4. Chart of accounts

Deliberately tiny. Every line traces to something in `reports.md` §3.

| Code | Account | Why it exists |
|---|---|---|
| `1000` | Cash / provider clearing | Real money in from Stripe & PayPal |
| `1100` | Trade receivables | Negative user balances (§3.5) |
| `1150` | Allowance for expected credit losses | Contra-asset, IFRS 9 (§3.6) |
| `2000` | Contract liability — stored value | Top-ups. **Never revenue** (§3.3) |
| `2010` | Contract liability — deferred revenue | Paid but not delivered (§3.1) |
| `2100` | Refund liability | Expected and requested returns (§3.2) |
| `4000` | Revenue | Gross, recognised on delivery |
| `4100` | Contra-revenue — gift credit redeemed | The §3.4 line. Policy (b), §5.1 |
| `4200` | Contra-revenue — expected returns | The §3.2 provision |
| `6500` | Impairment loss on receivables | ECL expense, IAS 1.82(ba) — **never nets against 4000** |

Policy (b) (§5.1) **removes** two accounts that (a) would have needed — a gift-credit
liability and a marketing expense. Outstanding gift credit is a **memo figure, not a booked
liability**: `|house:gift|` less redeemed, which `balance` already measures exactly and
`reports.md`'s `giftedOutstanding` already returns. Booking it as a liability would require
estimating redemption probability, and `reports.md` §3.3 already scoped breakage out.

No COGS, no inventory, no tax. `reports.md` §3.7 says why, and each omission is written down
rather than quietly absent.

## 5. Posting rules

The heart of the service, and the reason it is a service: **one table of rules, in one place,
that can be changed without touching shop, payment, balance or the UI.**

| Event | Journal |
|---|---|
| `OrderPlaced` | *None.* Contract inception is not a transaction. The fact is stored. |
| `PaymentSucceeded` `purpose=TOPUP` | Dr `1000` Cash · Cr `2000` Stored value |
| `PaymentSucceeded` `purpose=ORDER`, card/PayPal | Dr `1000` Cash · Cr `2010` Deferred revenue |
| `PaymentSucceeded` `purpose=ORDER`, balance-funded (backed part) | Dr `2000` Stored value · Cr `2010` Deferred revenue — **no new cash**, a liability converts |
| ⟶ (gift-funded part) | **Nothing.** Under (b) it is a discount, not consideration — it never enters deferred revenue (§5.1) |
| ⟶ (credit-funded part) | Dr `1100` Receivable · Cr `2010` Deferred revenue |
| `GiftIssued` | **No journal.** Memo only (§4, §5.1) |
| **delivery completed** | Dr `2010` Deferred revenue · Dr `4100` Contra-revenue *(gift-funded part)* · Cr `4000` **Revenue** *(gross)* ← the recognition point |
| `RefundRequested` | Dr `4000`/`4200` · Cr `2100` Refund liability |
| `PaymentRefunded` | Dr `2100` Refund liability · Cr `1000`/`2000` |
| `PaymentRefundFailed` | Reverse the settlement, keep the liability — the order walks back to `RETURNED` and the money is still owed |
| *period end* | Dr `4200` · Cr `2100` — expected-return provision (§3.2) |
| *period end* | Dr `6500` · Cr `1150` — ECL movement (§3.6) |

The balance-funded rows are why `balance.events` must carry the **funding split** and not just
an amount: three different debits, decided by a rule that only `balance` can evaluate
atomically (`reports.md` §4.7).

### 5.1 Gifted credit: policy (b), contra-revenue — **decided**

Gifted credit is treated as **consideration payable to a customer** (IFRS 15.70), reducing the
transaction price. Not expensed at grant.

**Why the timing argument is the decisive one.** IFRS 15.72 recognises the reduction of revenue
at the **later** of (a) recognising revenue for the goods, and (b) promising the consideration.
The promise happens at grant; the revenue happens at delivery — so the later of the two is
always the delivery. The standard defers it for exactly the reason the policy was chosen: a
gift may sit unspent for months, and the discount belongs against the sale it discounts, not
against a period in which nothing was sold.

**The consequence, which is the good part: there is no grant-date journal at all.** Under (a)
the entry would be Dr marketing / Cr gift liability. Under (b) that debit has nowhere to go —
it cannot reduce revenue yet (IFRS 15.72), and it cannot be an asset, because an asset arising
from *our own promise* is not a resource we control that will produce inflows. It is the
opposite. So nothing is booked, and the outstanding credit is a memo figure (§4).

**A gift that is never redeemed therefore costs nothing and appears in no period's P&L** —
which is economically true. We gave away a promise, not goods. Under (a) it would have burned
marketing expense in the grant month against a sale that never came.

**The whole treatment is one journal, at delivery:**

```
 CHF 60 order, CHF 50 paid with gifted credit, CHF 10 from a top-up

 on payment      Dr 2000 Stored value            10
                     Cr 2010 Deferred revenue        10      ← net transaction price only
                 (the gifted 50 is a discount; it never enters the books)

 on delivery     Dr 2010 Deferred revenue        10
                 Dr 4100 Contra-revenue          50
                     Cr 4000 Revenue                 60      ← gross, so the discount is visible

                 net revenue = 10
```

It balances without a liability, without an asset, and without touching marketing.

**Three consequences to hold onto:**

- **Refunds must reverse both legs.** A refunded gift-funded order reverses revenue *and*
  contra-revenue in the recorded proportion — so the refund path needs the funding split too,
  not just the amount. `balance.events` must carry it on the refund as well as the spend.
- **Gift-first drawdown is now a revenue policy, not just a reporting one.** `reports.md` §4.7
  draws from the gift pool first, which maximises contra-revenue and minimises recognised
  revenue. That is the conservative direction and it should stay — but it is no longer an
  internal detail, so changing it changes reported revenue.
- **The balance sheet shows no obligation for unredeemed gift credit.** That is the honest cost
  of (b) without a breakage estimate. Mitigated by displaying `giftedOutstanding` on the page
  as a disclosed figure, and by saying here that it is disclosed rather than booked.

Settled **before** the first posting, as required: changing it later means reversing and
reposting history.

## 6. The hard parts

**Out-of-order is guaranteed.** Kafka orders within a partition, and these are four topics. A
`PaymentSucceeded` can arrive before its `OrderPlaced`. So: **store the fact, then derive the
journal.** A fact whose prerequisites have not arrived stays unposted and is retried, rather
than crashing the consumer or posting half a movement.

**Business date, not consumption date.** Post by the event's own timestamp. If that period is
closed, post to the open one and flag it as a prior-period adjustment. Bucketing by when the
consumer happened to run is how a rebalanced consumer group rewrites your books.

**Never drop a stale event.** `notification` drops events past a per-type age as
`DROPPED_STALE` and that is right for email — nobody wants a week-old password reset.
**Accounting is the opposite**: a late fact is still a fact and must be booked. Copy
`notification`'s `processed_event`-before-acting idempotency, and explicitly *not* its
staleness rule. Worth a comment at the consumer, because the pattern will be copied from there.

**Backfill: the books start on a date.** Kafka retention has already deleted history, so there
is nothing to replay. That is normal and has a normal answer — an **opening balance** posted
as one journal on day zero, derived from the current state of `shop` and `balance`, and every
report before that date says "cash view only". Do not build a historical replay from four
databases to fake a past that was never booked.

**Estimates are scheduled, not consumed.** A period-end job posts the return provision and the
ECL movement, recording the assumption set and `asOf` on the entry (`reports.md` D18). It runs
once per period and is idempotent per period, so a re-run does not double-provide.

## 7. What this changes in `reports.md`

- **Steps 1–4 stand** — `paid_at`/`delivered_at`/`refunded_at`, the cash view, the funding
  split. All are facts services own, all still needed, none of it rework.
- **Step 2b (accrual series in `shop`) is deleted.** Revenue recognition moves here.
- **Step 4b (ECL in `balance`) is deleted.** `account.negative_since` still lands in `balance`
  (only it can maintain that), but the provision matrix, the rates and the `asOf` move here.
- **The UI stops doing arithmetic.** `reports.md` §4.9 has the browser computing net revenue
  across two responses; instead the page reads finished figures from `/api/accounting/**` and
  the cash panel from `shop`.

## 8. Verify

Real cluster, admin login — not unit tests.

1. Top up CHF 100 → `1000`/`2000` posted, **no revenue anywhere** (§3.3).
2. Order paid, not yet delivered → `2010` rises, revenue still zero.
3. Deliver it → `2010` falls, `4000` rises, in the **delivery** period, not the payment one.
4. Pay across a month boundary → payment in one period, revenue in the next, both immutable.
5. Gift CHF 50 → **no journal at all**, and `giftedOutstanding` rises by 50 (§5.1).
5b. Spend it on a CHF 60 delivered order → `4000` 60, `4100` 50, net revenue **10**. Refund
   that order → both legs reverse, net revenue back to 0.
5c. Gift CHF 50 and never spend it → it appears in **no** period's P&L, ever.
6. Buy on credit → `1100` rises, and the ECL posting at period end hits `6500`/`1150` and
   **never** touches `4000`.
7. Refund fails at the bank (`PaymentRefundFailed`) → refund liability survives, order back to
   `RETURNED`, books still balance.
8. Replay any event twice → one journal (idempotency), same as `balance`'s rule 3.
9. Feed an event dated inside a closed period → it posts to the open period, flagged.
10. Every journal: debits = credits. Every period: the trial balance sums to zero. This is the
    same discipline as `finance.md` §7.1 and it is checked the same way — a `/reconcile`
    endpoint you run after every deploy.
