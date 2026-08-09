# Revenue reports — plan

Status: **planned, nothing built.** This is the design for a `Revenues` page under the
profile, admin-only, sitting beside `Balance` and `Treasury`.

Its companion is `docs/finance/accounting.md` — the accrual half, which outgrew a query and
became a service (D19). This document is the **cash view**: what moved, and when.

`docs/finance/finance.md` describes the ledger — how money is created, moved and destroyed.
This document is the other half: **what the business made**. Read finance.md §2 first; every
number here is an aggregation of the movements described there, or of orders in `shop`.

## 1. The three questions

The page answers exactly three things, and nothing else belongs on it:

1. **Did we sell?** Gross sales, refunds, net — aggregated per **year / month / week**.
2. **How much money did we conjure?** `house:gift` issuance, over the same buckets.
3. **Was the conjured money actually spent?** Of everything spent on orders, how much was
   funded by gifts, how much by real top-ups, and how much by credit we extended.
4. **What did we actually earn?** The same orders on an accrual basis (§3): recognised on
   delivery, net of expected returns and of gifted credit, less the expected loss on credit
   extended.

Question 3 is the one with no answer in today's data (§4.7), and it turns out to be the input
question 4 depends on (§3.4). Questions 1 and 2 are
aggregations of rows that already exist; question 3 needs a rule invented and recorded at the
moment money is spent, because **money is fungible and the ledger does not remember which
francs were which**.

## 2. Where the numbers come from

Two owners, two endpoints, one page. No cross-service query, no shared database, no new
service.

```
                      ui-shop  /profile/revenues   (ROLE_ADMIN)
                              │
                  ┌───────────┴────────────┐
                  ▼                        ▼
  GET /api/shop/admin/revenue     GET /api/balance/admin/money-supply
        (sales & refunds)              (issuance & funded-spend split)
                  │                        │
             shop :8061                balance :8067
       customer_order + order_item      ledger_entry + account
```

**`shop` owns sales.** Every order is in `customer_order` regardless of which provider paid
for it, so it is the only place that can answer "what did we sell". `balance` sees only the
subset of orders paid *with balance*.

**`balance` owns money creation.** Gifts, top-ups and the funding split exist nowhere else.

**These two must never be added together.** `|house:shop|` is *balance-paid orders only*; the
revenue figure is *all orders*. Putting them in one total is the single most likely bug on
this page, so the UI keeps them in two visually distinct sections with that stated in the
copy.

## 3. Revenue, under IFRS

Two views of the same orders, and the page shows both because they answer different questions:

- **Cash view** — what moved, and when. What an operator asks first, and the only view that
  reconciles to the ledger.
- **Accrual view (IFRS)** — what we *earned*. Different numbers on different dates, and the
  one that is defensible as accounting.

Building only the cash view and calling it "revenue" is the mistake this section exists to
prevent. Building only the accrual view means the page cannot be reconciled against
`house:shop` and nobody trusts it. Both, side by side, labelled.

### 3.1 The recognition point is delivery, not the order

**Recording the sale when the order arrives is not IFRS 15** — it is the one point in the
standard that most often surprises people. Revenue is recognised when **control of the goods
transfers to the customer** (IFRS 15.31, indicators in .38), which for shipped consumer goods
is delivery, not order placement and not payment.

Placing an order creates a *contract* (step 1). Taking the money creates a **contract
liability** — we owe goods, not revenue. Only delivery satisfies the performance obligation.

| `OrderStatus` | IFRS position |
|---|---|
| `PENDING` | Contract exists. Nothing recognised. |
| `PAID` | Cash in, goods not delivered → **contract liability** (deferred revenue). |
| `SHIPPED` | Still a liability. For consumer sales, risk and control pass on delivery, not despatch. |
| `DELIVERED` | Performance obligation satisfied → **revenue recognised**. |
| `RETURNED` | Refund liability recognised; revenue reversed. |
| `REIMBURSED` | Liability settled in cash. |
| `PAYMENT_FAILED`, `CANCELLED` | Never anything. |

**Plan consequence:** `delivered_at` joins `paid_at` and `refunded_at` in migration `010`
(§4.1). The cash view buckets on `paid_at`; the accrual view buckets on `delivered_at`. Two
columns, two series, one migration.

If you would rather recognise on despatch, that is a defensible policy *only* if your terms
pass control at despatch — it is a terms-of-sale question, not a reporting preference. Ours
do not say so, so: delivery.

### 3.2 Expected returns reduce revenue now, not later

My earlier §3 said a refund lands in the bucket where the money moved, so a month can go
negative. That is right for the cash view and **wrong for the accrual view**.

IFRS 15.55 and B20–B27: where the customer has a right of return, you recognise revenue only
for the amount you **expect to be entitled to keep**. The expected returns become a **refund
liability** at the point of sale, plus a **return asset** for the goods you expect back
(at former carrying amount less recovery cost). Returns do not surprise a later month —
they are provided for in the month of the sale, and the provision is trued up as actuals
land.

That needs one estimate: a historical return rate, computed off the same table
(`REIMBURSED` orders ÷ delivered orders over a trailing window). One number, one query, no
new data.

### 3.3 A top-up is not revenue

Money loaded into a balance is a **contract liability** — the stored-value / gift-card case.
We owe goods or a refund; we have earned nothing. Revenue arrives only when the credit is
**redeemed** against a delivered order.

This is why `|house:topup|` must never appear on a revenue line. It is the size of what we
owe, and it belongs on the money-supply panel (§4.6), which is exactly where §2 already puts
it.

**Breakage** (IFRS 15.B44–B47): credit that will never be redeemed is income. If we expect to
be entitled to it, it is recognised *in proportion to the redemption pattern*; otherwise only
when the likelihood of redemption becomes remote. **Out of scope** — it needs a redemption
history this system does not have yet, and guessing it is worse than omitting it.

### 3.4 Gifted credit is contra-revenue, not income

You said we should not care where the money came from. Under IFRS we must — and it lands the
opposite way round from the intuition.

IFRS 15.70–72: **consideration payable to a customer** is a **reduction of the transaction
price**, unless it buys a distinct good or service from them. Gifted credit buys nothing; it
is a discount handed out early. So when a customer pays for a CHF 60 order entirely with
gifted credit, the accrual view books **roughly nil net revenue** — the CHF 60 "sale" is
offset by the CHF 60 of consideration we paid them to make it. The cost lands as marketing
spend when granted.

**Decided: the strict reading, policy (b)** — see `accounting.md` §5.1, which settles it and
works through the entries. One consequence reaches back into this document: **gift-first
drawdown (§4.7) is now a revenue policy**, because it decides how much of a sale is discount.
It stays as it is — that is the conservative direction — but changing it now changes reported
revenue, not just a dashboard split.

**This is what makes §4.7 load-bearing.** The gift-funded / backed / credit-funded split is
not decoration on a dashboard: it is the input to the contra-revenue line. Without it, the
accrual view overstates revenue by exactly the amount of gifted credit redeemed — a number
that is, by construction, whatever the admin felt like gifting that month.

### 3.5 Credit extended: the collectability gate comes *before* the loss allowance

Your example — CHF 10 balance, CHF 100 of goods, CHF −90, and a 90% chance it is never
repaid — does not reach the expected-credit-loss machinery. It fails a step earlier.

**IFRS 15.9(e): a contract exists only if it is *probable* that we will collect the
consideration.** "Probable" in IFRS is more likely than not — over 50%. At a 90% expected
default, collection is *not* probable, so **there is no contract to recognise revenue
against**. IFRS 15.15–16 then say: recognise the consideration received as a **liability**,
and recognise revenue only once there are no remaining obligations and the cash received is
non-refundable, or the contract is terminated.

So the correct treatment of your example is not "CHF 100 revenue and a CHF 90 expense". It is
**CHF 10 of revenue-relevant cash and no receivable at all** — we gave away goods on terms we
do not expect to be paid for.

That is a real finding about the business, not a bookkeeping detail: `AnyPositiveBalancePolicy`
(finance.md §4.2) lets anyone with one rappen buy anything, so if the default expectation is
genuinely 90%, the platform is not selling on credit — it is giving stock away and recording
a receivable to feel better about it. **The fix is a credit limit, not an accounting entry.**

**When collection *is* probable** — which is the case a real credit limit produces — the
machinery below applies.

### 3.6 IFRS 9: expected credit losses on the receivable

A negative user balance arising from buying goods is a **trade receivable**. That matters,
because trade receivables get the **simplified approach** (IFRS 9 5.5.15): always carry a
**lifetime** expected credit loss allowance, from day one, with no staging and no
"significant increase in credit risk" assessment to track. If it were structured as lending,
you would be in the general three-stage model instead — considerably more work, and one more
reason to keep this a trade receivable.

The rules that bind:

| Rule | What it means here |
|---|---|
| **Lifetime ECL, day one** | The allowance is recognised the moment the receivable exists, not when it goes overdue. A sale on credit books its expected loss in the same month. |
| **Unbiased, probability-weighted** | Not a worst case and not management's preferred number. A weighted outcome across at least two scenarios. |
| **Forward-looking** | Historical loss rates adjusted for what you expect, not just what happened. |
| **Discounted** | At the effective interest rate. For short-dated, non-interest-bearing receivables the effect is immaterial and ignoring it is standard practice — say so rather than silently dropping it. |
| **Presented as an expense** | IAS 1.82(ba): *impairment losses on financial assets*, its own line. **Not** a reduction of revenue. Your instinct to call it a "credit expense" is exactly right, and so is keeping it away from the revenue line. |
| **Write-off ≠ provision** | IFRS 9 5.4.4: derecognise only when there is no reasonable expectation of recovery. Until then the receivable stays gross with an allowance against it. |

**The measurement.** `ECL = EAD × PD × LGD`, and the shorthand is worth stating because it is
where your example loses money that is not there:

- `EAD` — exposure at default: CHF 90.
- `PD` — probability of default: 90%.
- `LGD` — loss given default: how much of the 90 is gone *when* they default. If nothing is
  recoverable, 100%.

So the allowance is **CHF 81, not CHF 90** (`90 × 0.9 × 1.0`). Booking the full CHF 90 assumes
default is certain — which is a different, and stronger, claim than "90% likely".

**The practical form** is a **provision matrix** (IFRS 9 B5.5.35): bucket receivables by age,
apply a historical loss rate per bucket, adjust forward. That is a `GROUP BY` over ageing
bands, not a credit model:

```
 age of negative balance   loss rate   exposure     allowance
 current (< 30d)              5%       CHF   200    CHF    10
 30–60d                      20%       CHF    90    CHF    18
 60–90d                      50%       CHF     0    CHF     0
 > 90d                      100%       CHF    40    CHF    40
                                                    ─────────
                                                    CHF    68
```

Until there is any repayment history the rates are assumptions, and the page must **label them
as assumptions with the date they were set**. A provision matrix with invented rates presented
as a measured figure is worse than no provision at all.

### 3.7 Proportionality — what to actually build

This is demo credit in test mode, not customer funds (finance.md, "three scope decisions").
Nobody is filing these accounts. So: take the parts that change what the numbers *mean*, skip
the parts that only matter to an auditor.

**Build:** the delivery-based accrual series (§3.1), gifted credit as contra-revenue (§3.4),
top-ups excluded from revenue (§3.3), and the provision matrix as one configurable line
(§3.6). Each is a query or a column.

**Skip, with the reason written down:** return-asset measurement at carrying cost (we do not
track COGS at all), breakage (§3.3), discounting (immaterial, §3.6), and any attempt at a
trial balance or double-entry general ledger. `balance`'s ledger is double-entry for *money*;
turning it into a general ledger for *accounting* is a different system and it is not this one.

**Never claim more than we compute.** The page says "management view, IFRS-informed", not
"IFRS financial statements", and every estimated line carries its assumption inline. I am not
an accountant and this is not an audit opinion; if these numbers ever face a statutory filing,
they need a real one.

## 4. Decisions

| # | Decision |
|---|---|
| D1 | **Bucket by `paid_at` / `refunded_at`, two new columns.** `created_at` is when the basket was submitted, `updated_at` moves on every status change — neither can bucket revenue (§4.1). |
| D2 | **Cash view: the event is *reaching* `PAID`, once.** A later return is a separate negative line, never a deletion of the sale. |
| D3 | **Cash view: refunds count at `REIMBURSED`, not `RETURNED`.** `RETURNED` is a request; only `REIMBURSED` means money left. |
| D4 | **Never sum across currencies.** No FX in this system. The report is per-currency, defaulting to CHF, with legacy USD orders (`shop/008`) in their own series. |
| D5 | **Buckets are `Europe/Zurich`, weeks are ISO (Monday).** Bucketing `TIMESTAMPTZ` without a stated zone silently reports UTC months. |
| D6 | **Amounts cross the wire as `minor` (rappen) integers,** matching `balance`. `shop` converts from `NUMERIC(10,2)` at the DTO edge, never in the browser. |
| D7 | **Gift-funded spend is decided by a deterministic drawdown rule, recorded at spend time** (§4.7). Not derived later, not estimated proportionally. |
| D8 | **Aggregate in SQL, not in Java.** One `GROUP BY` per endpoint; do not stream rows into a `HashMap`. |
| D9 | **Empty buckets are zero rows, not missing rows** (`generate_series`), so a gap in sales is visible as a gap. |
| D10 | **No materialised view, no reporting service, no warehouse.** The tables are small (§7.2 says when that stops being true). |
| D11 | **Read-only, admin-only, on both services.** Adding a report must not add a way to change money. |
| D12 | **Two views, both shown, both labelled** — cash (what moved) and accrual (what we earned). Neither is called "revenue" on its own (§3). |
| D13 | **Accrual revenue is recognised on `delivered_at`** — control transfer, IFRS 15.31/.38 — not on order placement and not on payment (§3.1). |
| D14 | **A top-up is a contract liability, never revenue** (§3.3). `\|house:topup\|` may not appear on any revenue line. |
| D15 | **Gifted credit redeemed is contra-revenue** (IFRS 15.70), so §4.7's funding split is an input to the accounts, not a dashboard curiosity (§3.4). |
| D16 | **Credit extended faces the collectability gate first** (IFRS 15.9(e)) and only then the loss allowance (§3.5). A high expected default means *no revenue*, not revenue plus a provision. |
| D17 | **The loss allowance is an expense line, never a deduction from revenue** (IAS 1.82(ba)), computed as a provision matrix with its assumptions shown inline (§3.6). |
| D19 | **The accrual half moves to an `accounting` service** (`accounting.md`). This document keeps the cash view; steps 2b and 4b below are superseded. |
| D18 | **Estimates are labelled as estimates**, with the date their rates were set. An assumed number rendered like a measured one is the failure mode of this whole page (§3.7). |

### 4.1 The recognition timestamp (D1)

`customer_order` has `created_at` and `updated_at` and nothing else. Neither works:

- `created_at` is when the order was **placed**. An order placed on 31 March and paid on
  1 April is April's revenue, not March's.
- `updated_at` is rewritten by every subsequent transition — ship, deliver, return. Bucketing
  by it means a delivery in June silently moves a March sale into June.

So: `shop` migration `010-add-order-money-dates.sql`

```sql
ALTER TABLE customer_order
    ADD COLUMN paid_at      TIMESTAMPTZ,
    ADD COLUMN delivered_at TIMESTAMPTZ,   -- the accrual recognition point (§3.1)
    ADD COLUMN refunded_at  TIMESTAMPTZ;

-- Backfill: the best available approximation, and knowingly wrong for orders
-- whose payment landed in a different month from their placement. Documented
-- rather than hidden, exactly as 008 documented its USD backfill.
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

The `delivered_at` backfill is the weakest of the three — `updated_at` for a returned order is
the return, not the delivery — and it is the reason step 1 of §6 says to get the columns live
before trusting any accrual number.

All three are set **once, on first entry** to the state, in `OrderService.updateOrderStatus`:
`paid_at` when the transition target is `PAID` and `paid_at IS NULL`, `delivered_at` on
`DELIVERED`, `refunded_at` on `REIMBURSED` — each under its own `IS NULL` guard. The `IS NULL` guard matters — `REIMBURSED`
can walk back to `RETURNED` and forward again (`OrderStatus.TRANSITIONS`), and a retried
refund must not move the sale into a later month.

### 4.2 Which orders count as sold

Reaching `PAID` is the event. Statuses reachable **only** through `PAID` therefore all count
as sold: `PAID`, `SHIPPED`, `DELIVERED`, `RETURNED`, `REIMBURSED`. `PENDING`,
`PAYMENT_FAILED` and `CANCELLED` never do.

With D1 in place this needs no status list at all: **`paid_at IS NOT NULL` is the predicate**
for the cash view, and `delivered_at IS NOT NULL` for the accrual view. Both stay correct if
the status graph grows. That is the reason the timestamp is worth a
migration rather than deriving revenue from `status`.

### 4.3 The queries

Gross, per bucket (`shop`). `:trunc` is one of `year|month|week`, validated against that
allow-list in Java before it reaches SQL — it is the one part of the query that is not a bind
parameter.

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

Refunds are the same query over `refunded_at`. They are **two separate aggregations joined by
bucket**, not one query with a `CASE` — an order contributes to a sales bucket and a refund
bucket independently, and trying to do both in one pass over `customer_order` produces a
`FULL OUTER JOIN` on `date_trunc` for no gain.

`date_trunc('week', …)` is ISO: weeks start Monday. Buckets are labelled by their start date
(`2026-W32` renders from `2026-08-03`).

### 4.4 Currency (D4)

`customer_order.currency` is `USD` for everything before the 2026-08-01 cutover and `CHF`
after. Summing them is meaningless and there is no FX anywhere in the system.

The endpoint takes `?currency=CHF` (default) and returns one series. The UI shows a currency
selector only when more than one currency has orders — which, for this dataset, means it
appears once and then never again as USD ages out of the window.

### 4.5 Timezone (D5)

`paid_at` is `TIMESTAMPTZ`. `date_trunc('month', paid_at)` buckets in the session timezone —
UTC on the pods — so a Zurich sale at 00:30 CEST on 1 August lands in July. Every bucket
expression carries `AT TIME ZONE 'Europe/Zurich'` explicitly. The same constant appears in the
`balance` query, and both cite this section, because the two panels sit side by side on one
page and must cut time the same way.

### 4.6 Money creation, per bucket (`balance`)

Straight aggregation over `ledger_entry`, no schema change needed for this half. The **house
leg** is the one to sum: a `GIFT` movement writes `-N` on `house:gift` and `+N` on the user, so
summing the credit legs of every user account is the same number by a longer route.

```sql
SELECT date_trunc(:trunc, e.created_at AT TIME ZONE 'Europe/Zurich') AS bucket,
       -SUM(e.amount_minor) FILTER (WHERE a.username = 'house:gift')  AS gifted_minor,
       -SUM(e.amount_minor) FILTER (WHERE a.username = 'house:topup') AS topped_up_minor,
        SUM(e.amount_minor) FILTER (WHERE a.username = 'house:shop')  AS spent_minor,
       -SUM(e.amount_minor) FILTER (WHERE a.username = 'house:refund') AS refunded_minor
  FROM ledger_entry e JOIN account a ON a.id = e.account_id
 WHERE a.kind = 'HOUSE'
   AND e.created_at >= :from AND e.created_at < :to
 GROUP BY 1 ORDER BY 1;
```

Signs are flipped where issuance is concerned so the report reads in positives: "we conjured
CHF 400 in August", not "house:gift went to −40000". Same convention as `ReconcileReport`.

### 4.7 The hard one: which spend was gift money

**The problem.** A user is gifted CHF 50, tops up CHF 50, then buys a CHF 60 order. The ledger
records one debit of CHF 60 against their account. Nothing in it says whether that was the
gifted francs, the topped-up francs, or a mix — because there is no such fact. Money is
fungible; the answer does not exist until we **choose a rule** and write down its result.

Three ways to get an answer, only one of which is worth building:

| | How | Verdict |
|---|---|---|
| **(a) Platform pro-rata** | `spend × gifted / (gifted + toppedUp)`, computed at report time | Free, no migration — and wrong for every individual user. A user who never received a gift still shows gift-funded spend. **Rejected**: a number that is wrong per-user is wrong. |
| **(b) Per-user pro-rata at spend time** | At each spend, split by the user's current funding mix | Correct-ish, but the mix is itself derived from a running estimate, so errors compound and nothing reconciles. **Rejected**. |
| **(c) Gift-first drawdown, recorded** | Each account carries a **gift pool**; a spend draws from it first, and the split is written on the ledger entry | **Chosen** (D7). Deterministic, auditable, reconcilable, and it answers the question the user actually asks: *was the free money used?* |

**The rule.** Each account carries `gift_pool_minor` — how much of its balance is conjured
money. It grows on `GIFT`, and every debit draws from it **first**:

```
gift_drawn   = min(gift_pool_minor, amount)          -- conjured money, spent
credit_drawn = max(0, amount - max(balance_minor,0)) -- money we lent, not money we had
backed_drawn = amount - gift_drawn - credit_drawn    -- real top-up money
```

Gift-first is the conservative choice: it maximises the reported figure for *"the conjured
money got spent"*, which is the number an operator is nervous about. It is also the only
ordering that needs no per-francs history.

**Schema** — `balance` migration `003-spend-funding.sql`:

```sql
ALTER TABLE account      ADD COLUMN gift_pool_minor BIGINT NOT NULL DEFAULT 0;
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
       updated_at      = now()
 WHERE id = :id AND balance_minor > 0;
```

**Movement by movement:**

| Movement | Gift pool |
|---|---|
| `GIFT` → user | `+amount` |
| `TOPUP` → user | unchanged (backed money) |
| `SPEND`, user → `house:shop` | drawn down gift-first; split recorded on the entry |
| `REFUND` → user | restored **to the pool it was drawn from**, read off the original `SPEND` entry via `reference` — a refunded gift-funded order must not turn conjured money into backed money |
| `TRANSFER` a → b | sender's pool drawn down gift-first; recipient's pool credited by **exactly that amount** |

That last row is load-bearing. If a transfer moved money without carrying its funding, one
`user → user` hop would launder every gifted franc into apparently-backed money and the whole
report would quietly read zero.

**Backfill.** Existing entries have no split. Because the rule is deterministic and the table
is tiny, the migration **replays the ledger in `id` order** through the rule above and writes
the historical splits — a `DO $$ … $$` block in `003`, not application code, so a fresh
database and an existing one converge on the same numbers. If the replay ever becomes too slow
to run in a migration, that is the signal from §7.2, and the honest alternative is to zero the
historical splits and report "from <date>" rather than to guess.

**Reported figure.** "Conjured money spent" is `SUM(gift_funded_minor)` over `SPEND` entries in
the bucket. Against `|house:gift|` it gives the number the question was really about: **of the
money we created, what fraction has been redeemed** — the rest is a liability still sitting in
user balances.

### 4.8 Why credit is its own bucket

`AnyPositiveBalancePolicy` (finance.md §4.2) lets a user holding CHF 10 buy a CHF 200 order.
Of that CHF 200, CHF 190 was never gifted and never topped up — it was **lent**. Folding it
into either bucket makes both wrong, so spend splits three ways: **gift-funded + backed +
credit-funded = spend**, and that identity is a reconcile check (§7.1).

### 4.9 The accrual series — *superseded by `accounting.md`*

> **Superseded (D19).** Computing accrual revenue as a query over `customer_order` means the
> books restate themselves whenever a row changes, and the contra-revenue subtraction below
> ends up executed in the browser — which is the smell that argued for a service. Kept here
> because it documents *what* must be computed; `accounting.md` §5 says where.

Same shape as §4.3, three changes: bucket on `delivered_at`, subtract the redeemed-gift
contra-revenue, and provide for expected returns in the month of the sale rather than the
month of the refund.

```sql
SELECT date_trunc(:trunc, delivered_at AT TIME ZONE 'Europe/Zurich') AS bucket,
       COUNT(*)   AS delivered_count,
       SUM(total) AS recognised
  FROM customer_order
 WHERE delivered_at IS NOT NULL
   AND currency = :currency
   AND delivered_at >= :from AND delivered_at < :to
 GROUP BY 1 ORDER BY 1;
```

Then, per bucket:

```
expectedReturnRate = REIMBURSED orders / delivered orders, trailing 12 months
refundProvision    = recognised × expectedReturnRate            -- §3.2
contraRevenue      = gift-funded spend in the bucket            -- §3.4, from balance
netRecognised      = recognised − refundProvision − contraRevenue
```

`contraRevenue` is the one figure `shop` cannot compute — it lives in `balance` (§4.7). The
**UI composes it**, exactly as it composes the two panels, rather than `shop` calling
`balance` at report time. A revenue report must not fail because another service is down; a
missing contra-revenue figure renders as "unavailable", and the accrual total is withheld
rather than silently overstated.

### 4.10 The provision matrix — *`negative_since` stays, the matrix moves*

> **Partly superseded (D19).** `account.negative_since` still belongs in `balance` — only it
> can maintain it atomically. The bands, the rates and the `asOf` move to `accounting`, because
> a provision is a posting made on a date, not a figure recomputed with today's rates over last
> year's exposure.


§3.6 needs the **age** of each negative balance, and nothing records it. Deriving it means
replaying the ledger to find when each account last crossed below zero — cheap now, wrong to
build on. One column, maintained in the same conditional `UPDATE` as everything else in
`BalanceService.move`:

```sql
ALTER TABLE account ADD COLUMN negative_since TIMESTAMPTZ;
-- set when a balance crosses from >= 0 to < 0; cleared when it returns to >= 0.
```

The matrix is then a `GROUP BY` over ageing bands with the rates in config, not in code and
not in the database:

```yaml
balance:
  ecl:
    as-of: 2026-08-08          # rendered on the page beside every figure (D18)
    bands:
      - { max-age-days: 30,  loss-rate: 0.05 }
      - { max-age-days: 60,  loss-rate: 0.20 }
      - { max-age-days: 90,  loss-rate: 0.50 }
      - { max-age-days: null, loss-rate: 1.00 }
```

Config, because these are assumptions that will be revised (§3.6) and revising an assumption
should not require a migration. The endpoint returns the bands, the exposure in each and the
resulting allowance, so the page can show the working — a single "expected credit loss: CHF
68" with no matrix behind it is not reviewable.

## 5. API

Both endpoints: `ROLE_ADMIN`, read-only, no side effects.

### `GET /api/shop/admin/revenue`

| Param | Values | Default |
|---|---|---|
| `granularity` | `year` \| `month` \| `week` | `month` |
| `from`, `to` | ISO date, `to` exclusive | last 12 months |
| `currency` | `CHF` \| `USD` | `CHF` |

```jsonc
{
  "granularity": "month",
  "currency": "CHF",
  "buckets": [
    { "bucket": "2026-07-01", "label": "Jul 2026",

      // cash view — bucketed on paid_at / refunded_at
      "grossMinor": 128450, "refundedMinor": 4800, "netMinor": 123650,
      "orderCount": 17, "refundCount": 1, "returnsPendingMinor": 0,

      // accrual view — bucketed on delivered_at (§3.1, §4.9)
      "recognisedMinor": 119900, "deliveredCount": 15,
      "refundProvisionMinor": 3600, "netRecognisedMinor": 116300 }
  ],
  "expectedReturnRate": 0.03,
  "totals": { "grossMinor": 128450, "refundedMinor": 4800, "netMinor": 123650,
              "recognisedMinor": 119900, "netRecognisedMinor": 116300 }
}
```

`netRecognisedMinor` is **before** contra-revenue: `shop` does not know what was paid for with
gifted credit (§4.9). The page subtracts it after joining the two responses, and labels the
result as the only figure that means "earned".

`totals` is computed server-side over the same window. The browser must not re-sum the
buckets to get it — that is how a rounding story starts.

### `GET /api/balance/admin/money-supply`

Same `granularity` / `from` / `to`. No `currency` — balance is CHF-only (finance.md D4).

```jsonc
{
  "granularity": "month",
  "buckets": [
    { "bucket": "2026-07-01", "label": "Jul 2026",
      "giftedMinor": 40000, "toppedUpMinor": 25000,
      "spentMinor": 51000, "refundedMinor": 0,
      "spentFromGiftMinor": 31000, "spentFromBackedMinor": 15000, "spentFromCreditMinor": 5000 }
  ],
  "creditLoss": {
    "asOf": "2026-08-08",
    "bands": [ { "maxAgeDays": 30, "lossRate": 0.05, "exposureMinor": 20000, "allowanceMinor": 1000 } ],
    "allowanceMinor": 6800,
    "estimated": true
  },
  "totals": { "…": "same bucket fields, plus:",
              "giftedOutstandingMinor": 9000 }
}
```

`creditLoss` is point-in-time, not bucketed: an allowance is a balance-sheet position as of a
date, not a flow through a month. `estimated: true` is not decoration — the UI keys the
"assumption, not measurement" styling off it (D18).

`giftedOutstandingMinor` = conjured − conjured-spent: free money still sitting in user
balances. It is the answer to "how much of what we created is still owed".

**Security.** `/api/balance/admin/**` is already `ROLE_ADMIN` in `BalanceSec` — the route needs
no new rule, which is the point of the prefix. `ShopSec` has **no** `/api/shop/admin/**` rule
today (it gates path by path), so one must be added:

```java
.pathMatchers(HttpMethod.GET, "/api/shop/admin/**").hasAnyRole("ADMIN", "MANAGER")
```

placed **before** the general `/api/shop/orders/**` authenticated rule, matching the ordering
comment already in `ShopSec` about `/internal/**` preceding `{username}`. `ADMIN` + `MANAGER`
mirrors `/api/shop/orders/all`, so whoever can already see every order can see their sum.

Both routes need `@RouterOperation` entries or they are live but invisible to Swagger — the
warning at the top of `BalanceRoute` applies equally to the new `ShopRoute` entry.

## 6. Build steps

Each step is deployable and verifiable on its own.

### Step 1 — recognition timestamps (`shop`)
1. Migration `010-add-order-money-dates.sql` (§4.1) + `db.changelog-master.yaml` entry.
2. `paid_at` / `delivered_at` / `refunded_at` on `CustomerOrder`, each set once in
   `OrderService.updateOrderStatus` under its own `IS NULL` guard.
3. Deploy and run one order all the way to `DELIVERED` **before** step 2 — from here on the
   data is right, and everything earlier is a documented approximation.

### Step 2 — sales aggregation (`shop`)
4. `repository/RevenueRepository` — the two `@Query` aggregations of §4.3, returning
   projections, `granularity` mapped through an enum (never string-concatenated).
5. `service/RevenueService` — join the two series by bucket, fill gaps (D9), convert to minor
   units at the boundary (D6), compute `totals`.
6. `handler/RevenueHandler` + route `GET /api/shop/admin/revenue`, `ShopSec` rule (§5),
   `@RouterOperation`.

### ~~Step 2b — the accrual series (`shop`)~~ — dropped (D19)
7. Superseded by `accounting.md`. Do not build the accrual series in `shop`; `delivered_at`
   from step 1 is still required, because `delivery.events` is what `accounting` recognises on
   and `shop` must record its own fact.

### Step 3 — money supply (`balance`)
8. `repository` query of §4.6 + `service/MoneySupplyService`, `GET /api/balance/admin/money-supply`.
   Funding-split fields report zero until step 4 — ship it anyway; gifted/topped-up/spent are
   already the answer to question 2.

### Step 4 — funding split (`balance`)
9. Migration `003-spend-funding.sql`: the two columns, and the deterministic replay backfill
   (§4.7).
10. `BalanceService.move` maintains `gift_pool_minor` and writes the split on the debit leg —
   inside the existing transaction, in the existing conditional `UPDATE`. All five movement
   types per the §4.7 table; the transfer and refund rows are the ones with teeth.
11. `ReconcileService` gains the two new invariants (§7.1) and `ReconcileReport` the fields.
    Treasury shows them for free.
12. Money-supply endpoint returns the real split, which is also the contra-revenue input the
    page needs for §3.4.

### Step 4b — exposure ageing (`balance`) — *trimmed by D19*
13. `account.negative_since`, maintained in the same conditional `UPDATE` (§4.10). Backfill by
    the same deterministic replay as step 4's, in the same migration. **This part stays.**
14. ~~`ecl` config + `CreditLossService`~~ — moved to `accounting` (`accounting.md` §4, §6).
15. **New:** `balance` gains an **outbox** → `balance.events`, publishing `GiftIssued`,
    `Spent` (with its funding split) and `Transferred`. Same pattern as shop/payment/delivery;
    the outbox row commits with the ledger rows. This is the only new *producer* the books
    need — see `accounting.md` §2.

### Step 5 — the page (`ui-shop`)
16. `types.ts`: `RevenueReport`, `MoneySupplyReport`. `api/reports.ts` with the two calls
    (`shop.revenue`, `balance.moneySupply`) — one module, since one page owns both.
17. `pages/Revenues.tsx`: granularity toggle (Year / Month / Week), range picker, then
    - **Cash — what moved** — bucket / gross / refunded / net + a bar per bucket;
    - **Earned — accrual** — read from `accounting` once it exists (D19); until then the
      section renders "not yet booked" rather than an approximation;
    - **Money we created** — gifted vs topped-up per bucket;
    - **Was it spent?** — gift-funded / backed / credit-funded split, plus
      `giftedOutstanding` as the headline stat;
    - **Expected credit loss** — from `accounting`: the provision matrix with its bands and
      its `asOf` date, styled as an estimate (D18), never merged into any revenue figure (D17).
18. Route `profile/revenues` inside `RequireAuth` → `AccountLayout`, and an admin-only
    `AccountNav` entry beside Treasury (same `isAdmin` pattern, same comment: the server
    enforces it, the nav only decides what is worth rendering).
19. **No chart library.** `ui-shop` has none, and this needs none: bars are a `div` with a
    percentage width, matching the existing table-and-`--primary` styling of `Treasury.tsx`.
    Adding `recharts` for six bars is 200 kB for a rounding of the visual.

`ui-demo` gets nothing. It has no treasury page either, and this is an operator tool.

## 7. Gotchas

### 7.1 New invariants to prove

`GET /api/balance/admin/reconcile` gains two, checked alongside the existing three:

```
gift_funded + credit_funded <= amount, per SPEND entry     -- a split cannot exceed its movement
SUM(gift_pool_minor) = |house:gift| − SUM(gift_funded)     -- conjured = still held + spent
```

The second is the whole report in one line: **every conjured franc is either still in someone's
balance or has been spent**, and if that fails, the funding split is lying and the page must
not be trusted.

### 7.2 The rest

- **Never sum the two panels.** §2. The shop figure is all providers; `|house:shop|` is
  balance-paid orders only. They overlap and neither contains the other.
- **A negative month is correct in the cash view**, not a bug: refunds land where the money
  moved. The accrual view should *not* go negative for the same reason — it provided for the
  return in the month of the sale (§3.2). If the two disagree in direction, that is the
  provision rate being wrong, which is information, not a bug to suppress.
- **Never net the credit loss against revenue** (D17). It is an expense line. The temptation
  is a single "real revenue" number; the result is a figure no accountant can reproduce.
- **A high default expectation is a product bug, not a reporting one** (§3.5). If the
  provision matrix starts reporting a large allowance, the fix is a credit limit in
  `CreditPolicy`, not a change to this page.
- **`granularity` is not a bind parameter.** `date_trunc(:trunc, …)` takes a string literal;
  it must come from an enum, never from the query string, or the report is an injection point.
- **The backfilled `paid_at` is a guess** for pre-migration orders, exactly as `008`'s USD
  backfill was. The UI states the cutover date under the sales table rather than pretending
  the old months are as solid as the new ones.
- **No `.block()` in `balance`**, including in the backfill path — finance.md §7.2. The replay
  is a SQL `DO` block in the migration precisely so it never becomes reactive code with a
  loop in it.
- **When this design runs out:** two `GROUP BY`s over a few thousand rows per request is
  nothing. Revisit at roughly 10⁶ orders or when the page takes >500 ms — the answer then is a
  nightly rollup table, not a bigger query, and not a new service.
- **Refunds outside `REIMBURSED` do exist.** `payment.refund` rows can succeed while the order
  walks back to `RETURNED` on a provider failure (`OrderStatus` comment). The report follows
  the **order**, not the provider record, so a refund that failed at the bank correctly stops
  counting until it completes.

## 8. Verify

Real cluster, admin login, `kubectl -n granite` — not unit tests.

1. Place and pay an order → it appears in the current month's **cash** gross, `paid_at` set,
   nothing in the accrual series yet, and `/admin/reconcile` still balances.
1b. Deliver it → `delivered_at` set, it now appears in the accrual series, and the cash
   series is unchanged. Paid in one month and delivered in the next → the two views land in
   **different buckets**, which is the whole point of §3.1.
2. Refund it → gross **unchanged** in its original bucket, refund appears in the bucket where
   the money went back, net drops.
3. Switch Year / Month / Week → the same total across all three granularities for one window.
4. A month with no orders renders a zero row, not a missing one (D9).
5. Gift CHF 50 → `gifted` rises in this bucket, `giftedOutstanding` rises by CHF 50,
   `spentFromGift` unchanged.
6. That user buys a CHF 30 order with balance → `spentFromGift` +30, `giftedOutstanding` −30,
   `spentFromBacked` 0.
7. Same user tops up CHF 50 and buys CHF 40 → gift pool is empty, so it lands in
   `spentFromBacked`, not `spentFromGift`.
8. Hold CHF 10, buy CHF 200 → `spentFromCredit` = 190, and gift + backed + credit = 200.
9. Gift CHF 20, transfer it to another user, they spend it → `spentFromGift` = 20. If it comes
   back as backed, the transfer rule (§4.7) is not wired.
10. Refund a gift-funded order → the credit returns to the gift pool; `giftedOutstanding`
    returns to its pre-purchase value.
11. A plain `ROLE_USER` token on both endpoints → `403`, and the nav entry is absent.
12. Legacy USD orders appear only under `currency=USD` and never in the CHF totals.
13. A gift-funded delivered order → cash gross rises, **accrual net does not** (§3.4). If it
    rises, contra-revenue is not wired and the page is overstating what we earned.
14. A top-up of CHF 100 → no revenue line moves anywhere (D14). It appears only on the
    money-supply panel.
15. Drive a user to −90 and age the row past a band boundary → the allowance moves to the next
    band, the page shows the band table and the `asOf` date, and the figure appears nowhere
    near a revenue total.
