# `accounting` — the books, and the revenue reports on top of them

Status: **planned, nothing built.** Supersedes and absorbs the former `docs/finance/reports.md`.

- `finance.md` — how money moves. `balance` is the only writer, one append-only ledger, two doors.
- **this** — what we *earned*, booked as journal entries that never change after the fact, and
  the reports served from them.

Read `finance.md` §2 and §4.2 first. Every number here is either an aggregation of the
movements described there, or a journal entry about one.

**Part I** builds the books. **Part II** puts endpoints and a page on top. Part II is useless
without Part I for anything accrual, but the *cash* half of it stands alone and is worth
shipping first (§11).

---

## 1. What this is for

Four questions, and nothing else belongs on the page:

1. **Did we sell?** Gross sales, refunds, net — per **year / month / week**.
2. **How much money did we conjure?** `house:gift` issuance over the same buckets.
3. **Was the conjured money actually spent?** Of everything spent on orders, how much was
   funded by gifts, how much by real top-ups, how much by credit we extended.
4. **What did we actually earn?** The same orders on an accrual basis: recognised on delivery,
   net of expected returns and of gifted credit, less the expected loss on credit extended.

Questions 1–3 are aggregations of rows that already exist, or nearly. Question 3 has no answer
in today's data until we invent a rule (§5) — and that rule turns out to be the input question
4 depends on (§2.4).

### 1.1 Why a service, and not another query

**Books must not restate themselves.** A query re-derives history on every page load. Orders
walk backwards (`REIMBURSED → RETURNED` is a legal transition in `OrderStatus`), migrations
backfill columns, statuses change months later. So March's revenue changes in June, silently,
and nothing anywhere records that it changed or why. A journal entry is a fact with a posting
date: written once, corrected only by another entry. That is not bureaucracy, it is the entire
reason accounting is built the way it is.

**You cannot close a period you compute on demand.** Closing March means March is frozen and a
late fact posts to April as a prior-period adjustment. There is no way to express that in a
`WHERE` clause.

**Policy would otherwise smear across three codebases.** The natural shortcut is to have the
*browser* subtract a `balance` figure from a `shop` figure to get contra-revenue — accounting
policy executed in React, across two API responses that could have been fetched from different
windows. That is the smell that says "service".

**Estimates are postings, not queries.** The ECL allowance and the return provision are entries
*made on a date under an assumption set*. Recomputing last year's provision with today's rates
is the same disease as above: it silently rewrites history and looks like a feature.

### 1.2 What it is not

**It never moves money.** `balance` keeps its mandate from `finance.md`: single writer, one
ledger, two doors. `accounting` is a **projection** — it books entries *about* money that has
already moved. Two components that each believe they are the ledger is the failure mode this
sentence exists to prevent. `accounting` has no endpoint that changes a balance and sits in no
payment path.

Nor is it a general ledger for a company. One entity, one currency, no journals a human can
type by hand, no tax, no inventory.

---

## 2. The IFRS model

Two views of the same orders, and the page shows both because they answer different questions:

- **Cash view** — what moved, and when. What an operator asks first, and the only view that
  reconciles against the ledger.
- **Accrual view** — what we *earned*. Different numbers on different dates, and the one that
  is defensible as accounting.

Building only the cash view and calling it "revenue" is the mistake this section prevents.
Building only the accrual view means nothing reconciles to `house:shop` and nobody trusts it.
Both, side by side, labelled.

### 2.1 The recognition point is delivery, not the order

**Recording the sale when the order arrives is not IFRS 15** — the point of the standard that
most often surprises people. Revenue is recognised when **control of the goods transfers to the
customer** (IFRS 15.31, indicators in .38), which for shipped consumer goods is delivery: not
order placement, and not payment.

Placing an order creates a *contract* (step 1). Taking the money creates a **contract
liability** — we owe goods, not revenue. Only delivery satisfies the performance obligation.

| `OrderStatus` | IFRS position |
|---|---|
| `PENDING` | Contract exists. Nothing recognised. |
| `PAID` | Cash in, goods not delivered → **contract liability** (deferred revenue). |
| `SHIPPED` | Still a liability. For consumer sales, control passes on delivery, not despatch. |
| `DELIVERED` | Performance obligation satisfied → **revenue recognised**. |
| `RETURNED` | Refund liability recognised; revenue reversed. |
| `REIMBURSED` | Liability settled in cash. |
| `PAYMENT_FAILED`, `CANCELLED` | Never anything. |

If you would rather recognise on despatch, that is defensible *only* if your terms pass control
at despatch. It is a terms-of-sale question, not a reporting preference, and ours do not say
so. Hence: delivery, and hence `shipments.events` is consumed but not recognised on (§4.2).

### 2.2 Expected returns reduce revenue now, not later

IFRS 15.55 and B20–B27: where the customer has a right of return, recognise revenue only for
the amount you **expect to be entitled to keep**. Expected returns become a **refund liability**
at the point of sale, plus a **return asset** for goods you expect back. Returns do not surprise
a later month — they are provided for in the month of the sale and trued up as actuals land.

That needs one estimate: a historical return rate, computed off the same table (`REIMBURSED`
orders ÷ delivered orders over a trailing window). One number, one query, no new data.

**The cash view behaves the opposite way and that is correct**: there, a refund lands in the
bucket where the money actually moved, so a cash month can go negative. An accrual month should
not, because it already provided. If the two disagree in direction, that is the provision rate
being wrong — information, not a bug to suppress.

### 2.3 A top-up is not revenue

Money loaded into a balance is a **contract liability** — the stored-value case. We owe goods or
a refund; we have earned nothing. Revenue arrives only when the credit is **redeemed** against a
delivered order.

So `|house:topup|` must never appear on a revenue line. It is the size of what we owe, and it
belongs on the money-supply panel (§9.3).

**Breakage** (IFRS 15.B44–B47) — credit that will never be redeemed is income, recognised in
proportion to the redemption pattern if we expect to be entitled to it. **Out of scope**: it
needs a redemption history this system does not have, and guessing it is worse than omitting it.

### 2.4 Gifted credit is contra-revenue — policy (b), decided

Gifted credit is **consideration payable to a customer** (IFRS 15.70) and reduces the
transaction price. It is *not* expensed at grant.

**Why the timing argument decides it.** IFRS 15.72 recognises the reduction of revenue at the
**later** of (a) recognising revenue for the goods and (b) promising the consideration. The
promise is the grant; the revenue is the delivery; the later of the two is therefore always the
delivery. The standard defers it for exactly the reason we chose it: a gift may sit unspent for
months, and the discount belongs against the sale it discounts, not against a period in which
nothing was sold.

**The consequence is the good part: there is no grant-date journal at all.** The alternative
policy would book Dr marketing / Cr gift liability at grant. Here that debit has nowhere to go —
it cannot reduce revenue yet (IFRS 15.72), and it cannot be an asset, because an asset arising
from *our own promise* is not a resource we control that will produce inflows; it is the
opposite. So nothing is booked, and outstanding gift credit is a **memo figure**, not a booked
liability (§4.3).

**A gift that is never redeemed therefore costs nothing and appears in no period's P&L** —
economically true. We gave away a promise, not goods.

This is also what makes §5 load-bearing: the gift/backed/credit split is not dashboard colour,
it is the input to the contra-revenue line. Without it the accrual view overstates revenue by
exactly the amount of gifted credit redeemed — a number that is, by construction, whatever an
admin felt like gifting that month.

### 2.5 Credit extended: the collectability gate comes *before* the loss allowance

Take a user with CHF 10 who buys CHF 100 of goods, landing at CHF −90, where we expect a 90%
chance of never being repaid. That case does not reach the expected-credit-loss machinery. It
fails a step earlier.

**IFRS 15.9(e): a contract exists only if it is *probable* that we will collect the
consideration.** "Probable" in IFRS is more likely than not — over 50%. At a 90% expected
default, collection is *not* probable, so **there is no contract to recognise revenue against**.
IFRS 15.15–16 then say: recognise the consideration received as a **liability**, and recognise
revenue only once there are no remaining obligations and the cash received is non-refundable, or
the contract is terminated.

So the correct treatment is **CHF 10 of revenue-relevant cash and no receivable at all** — not
CHF 100 of revenue plus a CHF 90 expense.

That is a finding about the product, not a bookkeeping detail. `AnyPositiveBalancePolicy`
(`finance.md` §4.2) lets anyone holding one rappen buy anything, so if the default expectation
is genuinely that high, the platform is not selling on credit — it is giving stock away and
recording a receivable to feel better about it. **The fix is a credit limit in `CreditPolicy`,
not an accounting entry.**

When collection *is* probable — which is what a real credit limit produces — §2.6 applies.

### 2.6 IFRS 9: expected credit losses on the receivable

A negative user balance arising from buying goods is a **trade receivable**. That matters,
because trade receivables get the **simplified approach** (IFRS 9 5.5.15): always carry a
**lifetime** expected credit loss allowance, from day one, with no staging and no "significant
increase in credit risk" assessment to track. Structured as lending instead, you would be in the
general three-stage model — considerably more work, and one more reason to keep this a trade
receivable.

| Rule | What it means here |
|---|---|
| **Lifetime ECL, day one** | The allowance is recognised the moment the receivable exists, not when it goes overdue. A sale on credit books its expected loss in the same month. |
| **Unbiased, probability-weighted** | Not a worst case, not management's preferred number. A weighted outcome across at least two scenarios. |
| **Forward-looking** | Historical loss rates adjusted for what we expect, not only what happened. |
| **Discounted** | At the effective interest rate. For short-dated non-interest-bearing receivables the effect is immaterial and ignoring it is standard practice — say so rather than silently dropping it. |
| **Presented as an expense** | IAS 1.82(ba): *impairment losses on financial assets*, its own line. **Never** a reduction of revenue. |
| **Write-off ≠ provision** | IFRS 9 5.4.4: derecognise only when there is no reasonable expectation of recovery. Until then the receivable stays gross with an allowance against it. |

**The measurement.** `ECL = EAD × PD × LGD`:

- `EAD` — exposure at default: CHF 90.
- `PD` — probability of default: 90%.
- `LGD` — loss given default: how much of the 90 is gone *when* they default; 100% if nothing is
  recoverable.

So the allowance on that example is **CHF 81, not CHF 90** (`90 × 0.9 × 1.0`). Booking the full
90 assumes default is certain — a different and stronger claim than "90% likely".

**The practical form is a provision matrix** (IFRS 9 B5.5.35): bucket receivables by age, apply
a historical loss rate per bucket, adjust forward. A `GROUP BY` over ageing bands, not a credit
model:

```
 age of negative balance   loss rate   exposure     allowance
 current (< 30d)              5%       CHF   200    CHF    10
 30–60d                      20%       CHF    90    CHF    18
 60–90d                      50%       CHF     0    CHF     0
 > 90d                      100%       CHF    40    CHF    40
                                                    ─────────
                                                    CHF    68
```

Until there is repayment history the rates are assumptions, and every figure derived from them
must be **labelled as an assumption with the date it was set**. A provision matrix with invented
rates presented as a measured figure is worse than no provision at all.

### 2.7 Proportionality — what to build and what to skip

This is demo credit in test mode, not customer funds (`finance.md`, "three scope decisions").
Nobody is filing these accounts. So take the parts that change what the numbers *mean* and skip
the parts that only matter to an auditor.

**Build:** delivery-based recognition (§2.1), gifted credit as contra-revenue (§2.4), top-ups
excluded from revenue (§2.3), the provision matrix as one configurable line (§2.6), and period
close (§1.1). Each is a query, a column or a scheduled job.

**Skip, with the reason written down:** return-asset measurement at carrying cost (we track no
COGS at all), breakage (§2.3), discounting (immaterial, §2.6), tax, multi-entity, and any
attempt at a human-operable general ledger. `balance`'s ledger is double-entry for *money*;
turning it into a general ledger for *accounting* is a different system and it is not this one.

**Never claim more than we compute.** The page says "management view, IFRS-informed", not "IFRS
financial statements", and every estimated line carries its assumption inline. If these numbers
ever face a statutory filing they need a real accountant; nothing here is an audit opinion.

---

## 3. Decisions

| # | Decision |
|---|---|
| D1 | **`balance` remains the only writer of money.** `accounting` projects; it never moves a franc (§1.2). |
| D2 | **The accrual books are a service, not a query** (§1.1). |
| D3 | **Revenue is recognised on delivery** — control transfer, IFRS 15.31/.38 (§2.1). |
| D4 | **Bucket by recorded timestamps: `paid_at`, `delivered_at`, `refunded_at`.** `created_at` is submission and `updated_at` moves on every transition; neither can bucket (§7 step 1). |
| D5 | **Cash view: the event is *reaching* `PAID`, once.** A later return is a separate negative line, never a deletion of the sale. |
| D6 | **Cash view: refunds count at `REIMBURSED`, not `RETURNED`.** `RETURNED` is a request; only `REIMBURSED` means money left. |
| D7 | **Accrual view: expected returns are provided at the point of sale** (IFRS 15.55, §2.2). |
| D8 | **A top-up is a contract liability, never revenue** (§2.3). `\|house:topup\|` may not appear on any revenue line. |
| D9 | **Gifted credit is contra-revenue (IFRS 15.70), policy (b): no grant-date journal** (§2.4, §4.5). |
| D10 | **The collectability gate is applied before the loss allowance** (IFRS 15.9(e), §2.5). A high expected default means *no revenue*, not revenue plus a provision. |
| D11 | **The loss allowance is an expense line, never a deduction from revenue** (IAS 1.82(ba), §2.6). |
| D12 | **Gift-first drawdown, recorded at spend time** (§5). Not derived later, not estimated proportionally. |
| D13 | **Credit-funded spend is its own bucket** (§5.3). `gift + backed + credit = spend` is an invariant. |
| D14 | **Never sum across currencies.** No FX anywhere in this system (§9.1). |
| D15 | **Buckets are `Europe/Zurich`, weeks are ISO (Monday)** (§9.2). |
| D16 | **Amounts cross the wire as `minor` integers**, matching `balance`. Conversion happens at the DTO edge, never in the browser. |
| D17 | **Aggregate in SQL, not in Java.** One `GROUP BY` per endpoint. |
| D18 | **Empty buckets are zero rows, not missing rows** (`generate_series`), so a gap in sales is visible as a gap. |
| D19 | **No materialised view, no warehouse.** The tables are small; §12 says when that stops being true. |
| D20 | **Every reporting endpoint is read-only and admin-only.** Adding a report must not add a way to change money. |
| D21 | **Estimates are labelled as estimates**, with the date their rates were set. An assumed number rendered like a measured one is this whole design's failure mode. |
| D22 | **The books start on a date.** An opening-balance journal, not a reconstructed past (§6). |
| D23 | **Never drop a stale event** — the deliberate inverse of `notification`'s `DROPPED_STALE` (§6). |

---

# Part I — the books

## 4. The service

### 4.1 Shape

```
 orders.events ─┐
 payments.events┤
 delivery.events┼──► accounting :8068  ──► journal (append-only)
 balance.events ┘      posting rules          period
   (new outbox)        estimates (scheduled)  chart of accounts
                                │
                                ▼
                    GET /api/accounting/** (ROLE_ADMIN, read-only)
```

WebFlux + R2DBC + Liquibase, own Postgres, OAuth2 resource server, functional routing — same as
`shop`, `payment` and `balance`. Port **8068** (8060–8067 are taken).

### 4.2 The event backbone

Four of the five facts the books need are already published, by services already running the
transactional outbox, with an idempotent-consumer precedent in `notification`:

| Topic | Producer | Facts accounting needs |
|---|---|---|
| `orders.events` | shop | `OrderPlaced`, `RefundRequested` |
| `payments.events` | payment | `PaymentSucceeded` (`purpose=ORDER\|TOPUP`), `PaymentFailed`, `PaymentRefunded`, `PaymentRefundFailed` |
| `delivery.events` | delivery | delivery completion — **the accrual recognition point** (§2.1) |
| `shipments.events` | delivery | despatch; consumed for context, not recognised on (§2.1) |
| **`balance.events`** | **balance — does not exist** | `GiftIssued`, `Spent` with its funding split, `Refunded` with the same split, `Transferred` |

**One new producer.** `balance` publishes nothing today and owns the two facts with no other
source: gift issuance and the funding split (§5). It needs an outbox — the same pattern the
other three run, and a good fit, because the outbox row commits in the same transaction as the
ledger rows, so a movement and its announcement cannot diverge. It does not violate "no
`.block()` in `balance`" (`finance.md` §7.2): the relay is a separate scheduled read.

That is the entire cost of admission. Everything else is a consumer.

### 4.3 Chart of accounts

Deliberately tiny. Every line traces to something in §2.

| Code | Account | Why it exists |
|---|---|---|
| `1000` | Cash / provider clearing | Real money in from Stripe & PayPal |
| `1100` | Trade receivables | Negative user balances (§2.5) |
| `1150` | Allowance for expected credit losses | Contra-asset, IFRS 9 (§2.6) |
| `2000` | Contract liability — stored value | Top-ups. **Never revenue** (§2.3) |
| `2010` | Contract liability — deferred revenue | Paid but not delivered (§2.1) |
| `2100` | Refund liability | Expected and requested returns (§2.2) |
| `4000` | Revenue | Gross, recognised on delivery |
| `4100` | Contra-revenue — gift credit redeemed | The §2.4 line |
| `4200` | Contra-revenue — expected returns | The §2.2 provision |
| `6500` | Impairment loss on receivables | ECL expense, IAS 1.82(ba) — **never nets against 4000** |

Policy (b) (§2.4) **removes** two accounts that the alternative would have needed: a gift-credit
liability and a marketing expense. Outstanding gift credit is a **memo figure, not a booked
liability** — `|house:gift|` less redeemed, which `balance` already measures exactly. Booking it
as a liability would require estimating redemption probability, and §2.3 already scoped breakage
out.

No COGS, no inventory, no tax (§2.7).

### 4.4 Posting rules

The heart of the service, and the reason it *is* a service: **one table of rules, in one place,
changeable without touching shop, payment, balance or the UI.**

| Event | Journal |
|---|---|
| `OrderPlaced` | *None.* Contract inception is not a transaction. The fact is stored. |
| `PaymentSucceeded` `purpose=TOPUP` | Dr `1000` Cash · Cr `2000` Stored value |
| `PaymentSucceeded` `purpose=ORDER`, card/PayPal | Dr `1000` Cash · Cr `2010` Deferred revenue |
| ⟶ balance-funded, **backed** part | Dr `2000` Stored value · Cr `2010` Deferred revenue — **no new cash**, a liability converts |
| ⟶ balance-funded, **gift** part | **Nothing.** Under policy (b) it is a discount, not consideration — it never enters deferred revenue (§4.5) |
| ⟶ balance-funded, **credit** part | Dr `1100` Receivable · Cr `2010` Deferred revenue |
| `GiftIssued` | **No journal.** Memo only (§2.4, §4.3) |
| **delivery completed** | Dr `2010` Deferred revenue · Dr `4100` Contra-revenue *(gift part)* · Cr `4000` **Revenue** *(gross)* ← the recognition point |
| `RefundRequested` | Dr `4000`/`4200` · Cr `2100` Refund liability |
| `PaymentRefunded` | Dr `2100` Refund liability · Cr `1000`/`2000`, reversing the gift and credit legs in their recorded proportion |
| `PaymentRefundFailed` | Reverse the settlement, keep the liability — the order walks back to `RETURNED` and the money is still owed |
| *period end* | Dr `4200` · Cr `2100` — expected-return provision (§2.2) |
| *period end* | Dr `6500` · Cr `1150` — ECL movement (§2.6) |

The three balance-funded rows are why `balance.events` must carry the **funding split** and not
just an amount: three different debits, decided by a rule only `balance` can evaluate atomically
(§5).

### 4.5 The gift journal, worked

```
 CHF 60 order, CHF 50 paid with gifted credit, CHF 10 from a top-up

 on payment      Dr 2000 Stored value            10
                     Cr 2010 Deferred revenue        10     ← net transaction price only
                 (the gifted 50 is a discount; it never enters the books)

 on delivery     Dr 2010 Deferred revenue        10
                 Dr 4100 Contra-revenue          50
                     Cr 4000 Revenue                 60     ← gross, so the discount stays visible

                 net revenue = 10
```

It balances without a liability, without an asset and without touching marketing.

**Three consequences to hold onto:**

- **Refunds reverse both legs.** A refunded gift-funded order reverses revenue *and*
  contra-revenue in the recorded proportion — so `balance.events` must carry the split on the
  refund as well as on the spend (§4.2).
- **Gift-first drawdown is now a revenue policy** (§5), not just a reporting one: it decides how
  much of a sale is discount. It stays as it is — the conservative direction — but changing it
  changes reported revenue.
- **The balance sheet shows no obligation for unredeemed gift credit.** The honest cost of
  policy (b) without a breakage estimate. Mitigated by displaying `giftedOutstanding` on the
  page as a disclosed, not booked, figure.

Settled **before** the first posting, as it had to be: changing it later means reversing and
reposting history.

## 5. The funding split — which spend was gift money

**The problem.** A user is gifted CHF 50, tops up CHF 50, then buys a CHF 60 order. The ledger
records one debit of CHF 60. Nothing in it says whether that was the gifted francs, the
topped-up francs, or a mix — because there is no such fact. Money is fungible; the answer does
not exist until we **choose a rule** and record its result.

| | How | Verdict |
|---|---|---|
| **(a) Platform pro-rata** | `spend × gifted / (gifted + toppedUp)`, at report time | Free, no migration — and wrong for every individual user. Someone who never received a gift still shows gift-funded spend. **Rejected**: a number wrong per-user is wrong. |
| **(b) Per-user pro-rata at spend time** | Split by the user's current funding mix | Correct-ish, but the mix is itself a running estimate, so errors compound and nothing reconciles. **Rejected**. |
| **(c) Gift-first drawdown, recorded** | Each account carries a **gift pool**; a spend draws from it first and the split is written on the ledger entry | **Chosen** (D12). Deterministic, auditable, reconcilable — and it is what the posting rules consume. |

### 5.1 The rule

Each account carries `gift_pool_minor` — how much of its balance is conjured money. It grows on
`GIFT`, and every debit draws from it **first**:

```
gift_drawn   = min(gift_pool_minor, amount)          -- conjured money, spent
credit_drawn = max(0, amount - max(balance_minor,0)) -- money we lent, not money we had
backed_drawn = amount - gift_drawn - credit_drawn    -- real top-up money
```

Gift-first is the conservative choice: it maximises contra-revenue and minimises recognised
revenue (§4.5). It is also the only ordering that needs no per-franc history.

### 5.2 Schema and the atomic write

`balance` migration `003-spend-funding.sql`:

```sql
ALTER TABLE account      ADD COLUMN gift_pool_minor BIGINT NOT NULL DEFAULT 0,
                         ADD COLUMN negative_since  TIMESTAMPTZ;   -- ECL ageing, §2.6
ALTER TABLE ledger_entry ADD COLUMN gift_funded_minor   BIGINT NOT NULL DEFAULT 0,
                         ADD COLUMN credit_funded_minor BIGINT NOT NULL DEFAULT 0;
-- backed_funded is not stored: it is amount − gift − credit, and a stored third
-- column is a third thing that can disagree with the other two.
```

The split is written on the **debit leg only**, inside `BalanceService.move`'s existing
transaction — the same statement that already carries the credit policy, so no new race and no
read-then-write:

```sql
UPDATE account
   SET balance_minor   = balance_minor - :amt,
       gift_pool_minor = GREATEST(0, gift_pool_minor - :amt),
       negative_since  = CASE WHEN balance_minor - :amt < 0 AND negative_since IS NULL
                              THEN now() ELSE negative_since END,
       updated_at      = now()
 WHERE id = :id AND balance_minor > 0;
```

`negative_since` is cleared in the credit path when a balance returns to `>= 0`. It exists
because §2.6 needs the **age** of each receivable and nothing records it; deriving it by
replaying the ledger is cheap now and wrong to build on.

**Movement by movement:**

| Movement | Gift pool |
|---|---|
| `GIFT` → user | `+amount` |
| `TOPUP` → user | unchanged (backed money) |
| `SPEND`, user → `house:shop` | drawn down gift-first; split recorded on the entry |
| `REFUND` → user | restored **to the pool it was drawn from**, read off the original `SPEND` entry via `reference` — a refunded gift-funded order must not turn conjured money into backed money |
| `TRANSFER` a → b | sender's pool drawn down gift-first; recipient's pool credited by **exactly that amount** |

That last row is load-bearing. If a transfer moved money without carrying its funding, one
`user → user` hop would launder every gifted franc into apparently-backed money and both the
report and the contra-revenue line would quietly read zero.

### 5.3 Why credit is its own bucket

`AnyPositiveBalancePolicy` lets a user holding CHF 10 buy a CHF 200 order. Of that CHF 200,
CHF 190 was never gifted and never topped up — it was **lent**. Folding it into either bucket
makes both wrong, so spend splits three ways: **gift + backed + credit = spend**, which is a
reconcile check (§12.1) and three separate debits in the posting rules (§4.4).

### 5.4 Backfill

Existing entries have no split. Because the rule is deterministic and the table is tiny, the
migration **replays the ledger in `id` order** through the rule above and writes the historical
splits — a `DO $$ … $$` block in `003`, not application code, so a fresh database and an
existing one converge on the same numbers. If the replay ever becomes too slow to run in a
migration, that is the signal from §12.2, and the honest alternative is to zero the historical
splits and report "from <date>" rather than to guess.

## 6. The hard parts

**Out-of-order is guaranteed.** Kafka orders within a partition, and these are four topics. A
`PaymentSucceeded` can arrive before its `OrderPlaced`. So: **store the fact, then derive the
journal.** A fact whose prerequisites have not arrived stays unposted and is retried, rather
than crashing the consumer or posting half a movement.

**Business date, not consumption date.** Post by the event's own timestamp. If that period is
closed, post to the open one and flag it as a prior-period adjustment. Bucketing by when the
consumer happened to run is how a rebalanced consumer group rewrites your books.

**Never drop a stale event (D23).** `notification` drops events past a per-type age as
`DROPPED_STALE`, and that is right for email — nobody wants a week-old password reset.
**Accounting is the opposite**: a late fact is still a fact and must be booked. Copy
`notification`'s `processed_event`-before-acting idempotency and explicitly *not* its staleness
rule. Worth a comment at the consumer, because the pattern will be copied from there.

**The books start on a date (D22).** Kafka retention has already deleted history, so there is
nothing to replay. That is normal and has a normal answer: an **opening balance** posted as one
journal on day zero, derived from the current state of `shop` and `balance`, with every report
before that date saying "cash view only". Do not build a historical replay from four databases
to fake a past that was never booked.

**Estimates are scheduled, not consumed.** A period-end job posts the return provision and the
ECL movement, recording the assumption set and `asOf` on the entry (D21). It runs once per
period and is idempotent per period, so a re-run does not double-provide.

## 7. Implementation steps — the books

Each step is deployable and verifiable on its own.

### Step 1 — record the facts (`shop`)
1. Migration `010-add-order-money-dates.sql`:

```sql
ALTER TABLE customer_order
    ADD COLUMN paid_at      TIMESTAMPTZ,
    ADD COLUMN delivered_at TIMESTAMPTZ,   -- the accrual recognition point (§2.1)
    ADD COLUMN refunded_at  TIMESTAMPTZ;

-- Backfill: the best available approximation, knowingly wrong where payment landed
-- in a different month from placement. Documented rather than hidden, exactly as
-- 008 documented its USD backfill.
UPDATE customer_order SET paid_at = created_at
    WHERE paid_at IS NULL
      AND status IN ('PAID','SHIPPED','DELIVERED','RETURNED','REIMBURSED');
UPDATE customer_order SET delivered_at = updated_at
    WHERE delivered_at IS NULL AND status IN ('DELIVERED','RETURNED','REIMBURSED');
UPDATE customer_order SET refunded_at = updated_at
    WHERE refunded_at IS NULL AND status = 'REIMBURSED';

CREATE INDEX idx_customer_order_paid_at      ON customer_order(paid_at)      WHERE paid_at      IS NOT NULL;
CREATE INDEX idx_customer_order_delivered_at ON customer_order(delivered_at) WHERE delivered_at IS NOT NULL;
CREATE INDEX idx_customer_order_refunded_at  ON customer_order(refunded_at)  WHERE refunded_at  IS NOT NULL;
```

   The `delivered_at` backfill is the weakest of the three — `updated_at` for a returned order
   is the return, not the delivery — which is why step 1 lands before anything trusts it.
2. The three columns on `CustomerOrder`, each set **once on first entry** in
   `OrderService.updateOrderStatus`, under its own `IS NULL` guard. The guard matters:
   `REIMBURSED` can walk back to `RETURNED` and forward again, and a retried refund must not
   move the sale into a later month.
3. Deploy and run one order all the way to `DELIVERED` before continuing. From here the data is
   right; everything earlier is a documented approximation.

### Step 2 — the funding split (`balance`)
4. Migration `003-spend-funding.sql` (§5.2), including the deterministic replay backfill (§5.4).
5. `BalanceService.move` maintains `gift_pool_minor` and `negative_since` and writes the split on
   the debit leg — inside the existing transaction, in the existing conditional `UPDATE`. All
   five movement types per §5.2; the transfer and refund rows are the ones with teeth.
6. `ReconcileService` gains the two new invariants (§12.1); `ReconcileReport` gains the fields,
   so Treasury shows them for free.

### Step 3 — `balance` starts publishing
7. Outbox table + `OutboxRelay` in `balance` → topic **`balance.events`**, copied from
   `payment`'s. The outbox row is written in the same transaction as the ledger rows.
8. Events: `GiftIssued`, `Spent` and `Refunded` (both carrying the gift/backed/credit split),
   `Transferred`. This is the only new producer the books need (§4.2).

### Step 4 — the service scaffold (`accounting`)
9. New module on port 8068: WebFlux, R2DBC, Liquibase, resource server, `Dockerfile` copied from
   `profile`, k8s deployment + Postgres + `app-multi` overlay entry, CI matrix row, gateway
   route `/api/accounting/**`. Copy `balance`'s `build.gradle.kts` — and note `finance.md` §6
   step 0, where the missing resource-server dependency left every endpoint open.
10. Schema: `account` (chart, §4.3), `journal` + `journal_line` (append-only, debits = credits),
    `period` (open/closed), `processed_event` (idempotency), `fact` (raw events, §6).

### Step 5 — consume and post
11. Four consumers, `processed_event` inserted **before** posting, and **no staleness rule**
    (D23). Store the fact first; derive the journal second (§6).
12. `PostingRules` — one class, the §4.4 table, nothing else. This is where policy lives (§1.1).
13. `POST`-free API: `GET /api/accounting/journals`, `GET /api/accounting/trial-balance`.
    Debits = credits per journal; the trial balance sums to zero per period.

### Step 6 — periods
14. `period` open/close, the opening-balance journal (D22), and prior-period adjustment routing
    for late facts (§6).

### Step 7 — the estimates
15. Scheduled period-end job: the expected-return provision (§2.2) from the trailing return rate.
16. The ECL provision matrix (§2.6), bands in config so revising an assumption is not a
    migration:

```yaml
accounting:
  ecl:
    as-of: 2026-08-09          # rendered beside every derived figure (D21)
    bands:
      - { max-age-days: 30,  loss-rate: 0.05 }
      - { max-age-days: 60,  loss-rate: 0.20 }
      - { max-age-days: 90,  loss-rate: 0.50 }
      - { max-age-days: null, loss-rate: 1.00 }
```

   Exposure and ageing come from `balance` (`negative_since`, step 2). The endpoint returns the
   bands, the exposure in each and the resulting allowance, so the page can show the working — a
   bare "expected credit loss: CHF 68" is not reviewable.

---

# Part II — the reports

## 8. Where each number comes from

Three owners, three endpoints, one page. No cross-service query and no shared database.

```
                      ui-shop  /profile/revenues   (ROLE_ADMIN)
                              │
        ┌─────────────────────┼──────────────────────┐
        ▼                     ▼                      ▼
 /api/shop/admin/revenue   /api/balance/admin/     /api/accounting/**
   (cash: sales & refunds)   money-supply            (accrual, trial
                             (issuance & split)       balance, ECL)
        │                     │                      │
   shop :8061            balance :8067          accounting :8068
```

**`shop` owns sales.** Every order is in `customer_order` regardless of which provider paid for
it, so it is the only place that can answer "what did we sell". `balance` sees only the subset
paid *with balance*.

**`balance` owns money creation.** Gifts, top-ups and the funding split exist nowhere else.

**`accounting` owns what we earned.** It is the only place revenue is a booked fact.

**Never add the cash panel to the money-supply panel.** `|house:shop|` is *balance-paid orders
only*; the sales figure is *all orders*. They overlap and neither contains the other. Putting
them in one total is the most likely bug on this page, so the UI keeps them in visually distinct
sections and says so in the copy.

## 9. Query mechanics

### 9.1 The cash view (`shop`)

`:trunc` is one of `year|month|week`, validated against that allow-list in Java before it reaches
SQL — it is the one part of the query that is not a bind parameter.

```sql
SELECT date_trunc(:trunc, paid_at AT TIME ZONE 'Europe/Zurich') AS bucket,
       COUNT(*)                                                 AS order_count,
       SUM(total)                                               AS gross
  FROM customer_order
 WHERE paid_at IS NOT NULL
   AND currency = :currency
   AND paid_at >= :from AND paid_at < :to
 GROUP BY 1
 ORDER BY 1;
```

Refunds are the same query over `refunded_at`. They are **two aggregations joined by bucket**,
not one query with a `CASE`: an order contributes to a sales bucket and a refund bucket
independently, and doing both in one pass produces a `FULL OUTER JOIN` on `date_trunc` for no
gain.

With D4 in place this needs no status list at all — `paid_at IS NOT NULL` is the predicate, and
it stays correct if the status graph grows.

**Currency (D14).** `customer_order.currency` is `USD` before the 2026-08-01 cutover and `CHF`
after (`shop/008`). Summing them is meaningless and there is no FX. The endpoint takes
`?currency=CHF` and returns one series; the UI shows a selector only when more than one currency
has orders.

### 9.2 Timezone (D15)

`paid_at` is `TIMESTAMPTZ`. `date_trunc('month', paid_at)` buckets in the session timezone — UTC
on the pods — so a Zurich sale at 00:30 CEST on 1 August lands in July. Every bucket expression
carries `AT TIME ZONE 'Europe/Zurich'` explicitly, in all three services, because the panels sit
side by side and must cut time the same way. `date_trunc('week', …)` is ISO: weeks start Monday,
and buckets are labelled by their start date.

### 9.3 Money creation (`balance`)

Straight aggregation over `ledger_entry`; the **house leg** is the one to sum.

```sql
SELECT date_trunc(:trunc, e.created_at AT TIME ZONE 'Europe/Zurich') AS bucket,
       -SUM(e.amount_minor) FILTER (WHERE a.username = 'house:gift')   AS gifted_minor,
       -SUM(e.amount_minor) FILTER (WHERE a.username = 'house:topup')  AS topped_up_minor,
        SUM(e.amount_minor) FILTER (WHERE a.username = 'house:shop')   AS spent_minor,
       -SUM(e.amount_minor) FILTER (WHERE a.username = 'house:refund') AS refunded_minor
  FROM ledger_entry e JOIN account a ON a.id = e.account_id
 WHERE a.kind = 'HOUSE'
   AND e.created_at >= :from AND e.created_at < :to
 GROUP BY 1 ORDER BY 1;
```

Signs are flipped for issuance so the report reads in positives — "we conjured CHF 400 in
August", not "house:gift went to −40000". Same convention as `ReconcileReport`.

The funding split per bucket is `SUM(gift_funded_minor)` and `SUM(credit_funded_minor)` over
`SPEND` entries, with backed as the remainder (§5.2).

## 10. Endpoints

All three: `ROLE_ADMIN`, read-only, no side effects (D20). Shared parameters:
`granularity=year|month|week` (default `month`), `from` / `to` (ISO date, `to` exclusive,
default last 12 months).

### `GET /api/shop/admin/revenue` — cash view

```jsonc
{
  "granularity": "month", "currency": "CHF",
  "buckets": [
    { "bucket": "2026-07-01", "label": "Jul 2026",
      "grossMinor": 128450, "refundedMinor": 4800, "netMinor": 123650,
      "orderCount": 17, "refundCount": 1, "returnsPendingMinor": 0 }
  ],
  "totals": { "grossMinor": 128450, "refundedMinor": 4800, "netMinor": 123650 }
}
```

`totals` is computed server-side over the same window. The browser must not re-sum the buckets
to get it — that is how a rounding story starts.

### `GET /api/balance/admin/money-supply`

No `currency`: balance is CHF-only (`finance.md` D4).

```jsonc
{
  "granularity": "month",
  "buckets": [
    { "bucket": "2026-07-01", "label": "Jul 2026",
      "giftedMinor": 40000, "toppedUpMinor": 25000,
      "spentMinor": 51000, "refundedMinor": 0,
      "spentFromGiftMinor": 31000, "spentFromBackedMinor": 15000, "spentFromCreditMinor": 5000 }
  ],
  "totals": { "…": "same fields, plus:", "giftedOutstandingMinor": 9000 }
}
```

`giftedOutstandingMinor` = conjured − conjured-spent: free money still sitting in user balances,
and the **disclosed** figure standing in for the liability policy (b) does not book (§2.4).

### `GET /api/accounting/revenue` — accrual view

```jsonc
{
  "granularity": "month", "currency": "CHF", "booksOpenedOn": "2026-09-01",
  "buckets": [
    { "bucket": "2026-07-01", "label": "Jul 2026", "periodStatus": "CLOSED",
      "revenueGrossMinor": 119900,
      "contraGiftMinor": 31000, "contraReturnsMinor": 3600,
      "netRevenueMinor": 85300,
      "deliveredCount": 15 }
  ],
  "creditLoss": {
    "asOf": "2026-08-09", "estimated": true, "allowanceMinor": 6800,
    "bands": [ { "maxAgeDays": 30, "lossRate": 0.05, "exposureMinor": 20000, "allowanceMinor": 1000 } ]
  },
  "totals": { "revenueGrossMinor": 119900, "netRevenueMinor": 85300 }
}
```

Three things this shape enforces. `creditLoss` is **point-in-time, not bucketed** — an allowance
is a balance-sheet position as of a date, not a flow through a month — and it is deliberately
outside `totals` so nothing can net it against revenue (D11). `estimated: true` is not
decoration: the UI keys its "assumption, not measurement" styling off it (D21). `periodStatus`
tells the page which buckets are frozen.

Plus `GET /api/accounting/trial-balance` and `GET /api/accounting/journals` — the audit trail
that makes any of the above checkable.

### Security

`/api/balance/admin/**` is already `ROLE_ADMIN` in `BalanceSec` — the route needs no new rule,
which is the point of the prefix. `accounting` gets the same prefix convention from day one.
`ShopSec` gates path by path and has **no** `/api/shop/admin/**` rule, so one must be added:

```java
.pathMatchers(HttpMethod.GET, "/api/shop/admin/**").hasAnyRole("ADMIN", "MANAGER")
```

placed **before** the general `/api/shop/orders/**` authenticated rule, matching the ordering
comment already in `ShopSec` about `/internal/**` preceding `{username}`. `ADMIN` + `MANAGER`
mirrors `/api/shop/orders/all`: whoever can already see every order can see their sum.

All new routes need `@RouterOperation` entries or they are live but invisible to Swagger — the
warning at the top of `BalanceRoute` applies equally here.

## 11. Implementation steps — the reports

### Step 8 — the cash view (`shop`)
17. `repository/RevenueRepository` — the two aggregations of §9.1, `granularity` mapped through
    an enum (never string-concatenated into SQL).
18. `service/RevenueService` — join the two series by bucket, fill gaps (D18), convert to minor
    units at the boundary (D16), compute `totals`.
19. `handler` + route + `ShopSec` rule + `@RouterOperation` (§10).

**This step is worth shipping before Part I finishes.** It answers question 1 on its own, it
reconciles against the ledger, and it needs nothing from `accounting`.

### Step 9 — money supply (`balance`)
20. The §9.3 query + `service/MoneySupplyService` + `GET /api/balance/admin/money-supply`.
    Funding-split fields report zero until step 2 lands; ship it anyway, since gifted/topped-up/
    spent already answer question 2.

### Step 10 — accrual endpoints (`accounting`)
21. `GET /api/accounting/revenue`, `/trial-balance`, `/journals` over the journal tables.

### Step 11 — the page (`ui-shop`)
22. `types.ts`: `RevenueReport`, `MoneySupplyReport`, `AccrualReport`. `api/reports.ts` with the
    three calls — one module, since one page owns all three.
23. `pages/Revenues.tsx`: granularity toggle (Year / Month / Week), range picker, then
    - **Cash — what moved**: bucket / gross / refunded / net, plus a bar per bucket;
    - **Earned — accrual**: gross revenue, contra-revenue (gift and returns), net; frozen
      periods marked; "not yet booked" before `booksOpenedOn` (D22);
    - **Money we created**: gifted vs topped-up per bucket;
    - **Was it spent?**: gift / backed / credit split, with `giftedOutstanding` as the headline;
    - **Expected credit loss**: the band table with its `asOf`, styled as an estimate (D21) and
      never merged into any revenue figure (D11).
24. Route `profile/revenues` inside `RequireAuth` → `AccountLayout`, and an admin-only
    `AccountNav` entry beside Treasury — same `isAdmin` pattern, same comment: the server
    enforces it, the nav only decides what is worth rendering.
25. **No chart library.** `ui-shop` has none and this needs none: bars are a `div` with a
    percentage width, matching `Treasury.tsx`'s existing table-and-`--primary` styling. Adding
    `recharts` for six bars is 200 kB for a rounding of the visual.

`ui-demo` gets nothing. It has no Treasury page either, and this is an operator tool.

---

## 12. Gotchas

### 12.1 New invariants to prove

`GET /api/balance/admin/reconcile` gains two, checked alongside the existing three:

```
gift_funded + credit_funded <= amount, per SPEND entry     -- a split cannot exceed its movement
SUM(gift_pool_minor) = |house:gift| − SUM(gift_funded)     -- conjured = still held + spent
```

The second is the report in one line: **every conjured franc is either still in someone's
balance or has been spent.** If it fails, the funding split is lying — and since the split feeds
contra-revenue (§4.4), so is revenue.

`accounting` has its own: debits = credits per journal, and the trial balance sums to zero per
period. Same discipline as `finance.md` §7.1, checked the same way — a `/reconcile` endpoint run
after every deploy.

### 12.2 The rest

- **Never sum the cash and money-supply panels** (§8). All-provider vs balance-only.
- **A negative month is correct in the cash view**, not a bug: refunds land where the money
  moved. The accrual view should not go negative for the same reason — it provided in the month
  of the sale (§2.2).
- **Never net the credit loss against revenue** (D11). The temptation is a single "real revenue"
  number; the result is a figure no accountant can reproduce.
- **A high default expectation is a product bug, not a reporting one** (§2.5). If the provision
  matrix starts reporting a large allowance, the fix is a credit limit in `CreditPolicy`, not a
  change to this page.
- **`granularity` is not a bind parameter.** `date_trunc(:trunc, …)` takes a string literal, so
  it must come from an enum — never from the query string, or the report is an injection point.
- **The backfilled timestamps are guesses** for pre-migration orders, exactly as `008`'s USD
  backfill was. The UI states the cutover date under the cash table rather than pretending old
  months are as solid as new ones.
- **No `.block()` in `balance`**, including the backfill path (`finance.md` §7.2). The replay is
  a SQL `DO` block in the migration precisely so it never becomes reactive code with a loop.
- **When this design runs out:** two `GROUP BY`s over a few thousand rows is nothing. Revisit at
  roughly 10⁶ orders, or when the page takes >500 ms. The answer then is a nightly rollup table,
  not a bigger query and not another service.
- **Refunds outside `REIMBURSED` exist.** `payment.refund` rows can succeed while the order walks
  back to `RETURNED` on a provider failure (`OrderStatus`'s comment). The cash view follows the
  **order**, not the provider record, so a refund that failed at the bank correctly stops
  counting until it completes.

## 13. Verify

Real cluster, admin login, `kubectl -n granite` — not unit tests.

**Cash view**
1. Place and pay an order → it appears in the current month's cash gross, `paid_at` set, and
   `/admin/reconcile` still balances.
2. Refund it → gross **unchanged** in its original bucket, refund in the bucket where the money
   went back, net drops.
3. Switch Year / Month / Week → the same total across all three for one window.
4. A month with no orders renders a zero row, not a missing one (D18).
5. Legacy USD orders appear only under `currency=USD`, never in CHF totals.

**Money supply**
6. Gift CHF 50 → `gifted` rises, `giftedOutstanding` rises by 50, `spentFromGift` unchanged.
7. That user buys a CHF 30 order with balance → `spentFromGift` +30, `giftedOutstanding` −30,
   `spentFromBacked` 0.
8. Same user tops up CHF 50 and buys CHF 40 → the gift pool is empty, so it lands in
   `spentFromBacked`, not `spentFromGift`.
9. Hold CHF 10, buy CHF 200 → `spentFromCredit` = 190, and gift + backed + credit = 200.
10. Gift CHF 20, transfer it, the recipient spends it → `spentFromGift` = 20. If it comes back
    as backed, the transfer rule (§5.2) is not wired.

**The books**
11. Top up CHF 100 → `1000`/`2000` posted, **no revenue anywhere** (§2.3).
12. Order paid, not yet delivered → `2010` rises, revenue still zero.
13. Deliver it → `2010` falls, `4000` rises, in the **delivery** period, not the payment one.
14. Pay in one month and deliver in the next → the two land in different periods, both immutable.
15. Gift CHF 50 → **no journal at all**, `giftedOutstanding` rises by 50 (§2.4).
16. Spend it on a CHF 60 delivered order → `4000` 60, `4100` 50, net revenue **10** (§4.5).
17. Refund that order → both legs reverse; net revenue back to 0.
18. Gift CHF 50 and never spend it → it appears in **no** period's P&L, ever.
19. Buy on credit → `1100` rises; the period-end ECL posting hits `6500`/`1150` and **never**
    touches `4000`.
20. `PaymentRefundFailed` → the refund liability survives, the order returns to `RETURNED`, and
    the books still balance.
21. Replay any event twice → one journal (idempotency), as `finance.md` §8 rule 3 requires.
22. Feed an event dated inside a closed period → it posts to the open period, flagged.
23. Every journal: debits = credits. Every period: the trial balance sums to zero.

**Access**
24. A plain `ROLE_USER` token on all three endpoints → `403`, and the nav entry is absent.
