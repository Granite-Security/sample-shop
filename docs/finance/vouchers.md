# `vouchers` — percentage discount codes

Status: **built.** Every step of §12: the `shop` schema, pricing, preview endpoint and admin CRUD;
the `4300` contra-revenue account and the delivery/refund posting rules in `accounting`; and both
storefronts. What remains unbuilt is §13's out-of-scope list, on purpose, and §14's open items.

The architecture and the accounting treatment were fixed before the first line, for the same
reason `accounting.md` §4.5 was settled before the first posting: changing the treatment
afterwards means reversing and reposting history.

A voucher is a **code, with an expiry date, that takes a percentage off an order**. That is the
whole feature. It is not a gift card, not stored value, and not money — see §2.2, which is the
most important paragraph here.

Read alongside:

- `finance.md` — how money moves. `balance` is the only writer, one append-only ledger, two doors.
- `accounting.md` — what we earned. §2.4 and §4.5 (gifted credit as contra-revenue) are the
  pattern this design deliberately mirrors, and §5 (the funding split) is what it must not disturb.

---

## 1. What this is for

An admin creates `SPRING25`, 10% off, valid until 31 March. A shopper types it at checkout, sees
the discount, and pays less. The books show that we sold CHF 60 of chocolate and discounted CHF 6
of it, rather than quietly showing a CHF 54 sale.

Four things follow from that sentence, and everything below is one of them:

1. Somebody has to **price** the order — §2.
2. The price has to stay **frozen** once placed — D5.
3. The discount has to be **visible** in the books, not netted away — §3.
4. A public code has to be **bounded** — §7.

### 1.1 What it is not

| Not this | Because |
|---|---|
| A gift card | Stored value is `balance`'s mandate, and it is *money* — issued, spent, refunded, counted in the money supply. A voucher issues nothing (§2.2). |
| A fixed-amount discount (CHF 5 off) | Out of scope (§13). It is a small extension of this schema, but a different rounding story and a different zero-total edge. |
| A campaign engine | No stacking, no per-product rules, no auto-apply, no budgets (§13). |
| A referral or loyalty scheme | Those *earn* the shopper something, which is stored value again — `balance`. |

---

## 2. Where it lives

**In `shop`.** Not `balance`, not a new service.

```
   ui-shop  ──POST /api/shop/vouchers/preview──►  shop :8061   (prices, stores nothing)
      │                                              │
      └────────POST /api/shop/orders────────────────►│  voucher validated + discount frozen
       { items, packaging, provider, voucherCode }   │  onto customer_order, redemption row
                                                     │  written in the SAME transaction
                                                     │
                                          orders.events (OrderPlaced)
                                            + discountTotal, voucherCode, discountPercent
                                                     │
                    ┌────────────────────────────────┼──────────────────────┐
                    ▼                                ▼                      ▼
              payment :8062                    delivery :8063        accounting :8068
              charges order.total              unchanged            grosses up at delivery
              — UNCHANGED                                           Dr 4300 (§4)
                    │
                    ▼
              balance :8067
              spends order.total — UNCHANGED, and never learns a voucher exists
```

**The payoff, and the test of the whole design: `customer_order.total` keeps meaning "the amount
payable".** It is simply computed differently. `payment`, `balance` and `delivery` need *zero*
changes, because none of them ever asks why the total is what it is. Any design that makes them
ask is worse than this one.

### 2.1 Why `shop`

`shop` is already the only service that prices an order. `OrderService.validateAndBuild` reads
the catalogue, reprices every line server-side, adds packaging, and writes `total` — and the SPA
is already forbidden from computing money (`Checkout.tsx`: *"Read off the quote the server sent,
never computed here"*). A discount is a pricing rule. Putting it anywhere else means two services
hold an opinion about what an order costs, and the moment they disagree the shopper is charged
one number and told another.

Packaging is the precedent to copy wholesale: quote endpoint, server reprices at placement,
amount frozen onto the order, a dedicated column so the total still reconciles (`packaging.md`
D42/D43). Vouchers are the same shape with the sign flipped.

### 2.2 Why not `balance` — the load-bearing distinction

It is tempting, because `balance` already discounts orders: gifted credit does exactly that
economically (`accounting.md` §2.4). The difference is that **gifted credit is CHF that exists**.
It is issued through the `house:gift` door, it sits in a ledger, it can be spent on anything or
never spent, and `GET /api/balance/admin/money-supply` counts it.

A voucher issues nothing. It cannot be held, cannot be transferred, has no CHF amount until an
order exists to apply it to, and evaporates on its expiry date having cost nothing.

Routing a voucher through `balance` would mean minting CHF at redemption to immediately destroy
it. That breaks three things at once:

- **`balance` is the only writer of money** (`finance.md`, D1) — and money it did not intend to
  create would enter the ledger.
- **The money supply becomes a lie.** `|house:gift|` would include discounts nobody was ever
  entitled to hold.
- **The funding split** (`accounting.md` §5) — `gift + backed + credit = spend` — would either
  break as an invariant or need a fourth bucket, and gift-first drawdown is *already* a revenue
  policy (§4.5). Vouchers would silently change reported revenue through a mechanism designed for
  something else.

**D1 below is therefore an invariant, not a preference: a voucher never reaches `balance`.**

### 2.3 Why not a `promotions` service

A real one would own campaigns, budgets, eligibility rules across storefronts, and A/B splits.
We have one rule: percent off, until a date. Against that, a separate service costs a synchronous
call in the checkout hot path (or a distributed reservation protocol, since validate-then-place
is two-phase), a ninth database, and a second place that knows what an order costs — the exact
thing §2.1 exists to prevent.

**Revisit when** any of these lands: per-product or per-category restrictions, stacking rules,
campaign budgets in CHF, or vouchers that must apply across `shop` *and* something that is not
`shop`. Until then the seam is `VoucherService` inside `shop`, which is where it would be lifted
from anyway.

---

## 3. The IFRS treatment

### 3.1 A voucher discount reduces the transaction price at inception

IFRS 15.47: the transaction price is the consideration we *expect to be entitled to*. With a 10%
code applied, that is CHF 54 on a CHF 60 basket. There is no variable consideration to estimate
(the discount is certain and known at placement), no consideration payable to a customer, and no
timing question — which makes this **strictly simpler than the gift case**. Gifted credit needed
IFRS 15.70–72 and an argument about *when* the reduction lands; a voucher has nothing to defer.

The practical consequence: **the net amount is the transaction price**, and revenue recognition
still happens on delivery (D3), unchanged.

### 3.2 We nevertheless present gross, with a contra-revenue line — D2

Under §3.1 the literal entry at delivery would be Dr 2010 / Cr 4000 for CHF 54, and the CHF 6
would appear nowhere in the books at all.

We gross it up instead: **Cr 4000 with CHF 60, Dr 4300 with CHF 6**. A new contra-revenue
account, `4300 — Contra-revenue: voucher discounts`, beside `4100` (gift credit) and `4200`
(expected returns).

Two reasons, and one honest caveat.

- **Question 1 of `accounting.md` §1 is "did we sell?"** Gross sales and discounts are two
  different management questions, and netting destroys the second one irrecoverably — no report
  can reconstruct a discount that was never booked.
- **It makes vouchers and gifts read the same way** on the P&L. Both are discounts; a reader
  should not have to know which mechanism produced one to find it.
- **The caveat, which belongs in the disclosure:** this is a *presentation gross-up*, not a
  measurement position. IFRS revenue is CHF 54 either way — the two accounts always net to it.
  `4300` is never a receivable, never an expense, and must never appear on a cash line.

### 3.3 A voucher and gifted credit on the same order

Both can apply. **The voucher goes first**, because it changes the price; the gift then funds
whatever is left to pay:

```
 CHF 60 basket, 10% voucher, CHF 30 of gifted credit, CHF 24 from a top-up

   transaction price = 60 − 6 = 54          ← the voucher reduces the price (§3.1)
   funding of the 54: gift 30, backed 24    ← balance's split, on the NET amount (§5)

 on payment    Dr 2000 Stored value        24
                   Cr 2010 Deferred revenue    24      ← backed part only
               (the gifted 30 never enters the books — §4.5; the voucher's 6 never existed)

 on delivery   Dr 2010 Deferred revenue    24
               Dr 4100 Contra-revenue      30          ← gift
               Dr 4300 Contra-revenue       6          ← voucher
                   Cr 4000 Revenue              60      ← gross basket

               net revenue = 24
```

It balances, and each discount is separately visible. Note that `balance` splits the *net* 54 —
it is handed a payable total and never learns why it is 54. That is §2's payoff restated as a
journal.

### 3.4 Refunds reverse the voucher leg too

`accounting.md` §4.5's first consequence applies unchanged, with one more leg: a refunded order
reverses revenue, the gift contra **and** the voucher contra, in their recorded proportions.
`PostingRules.refundRequested` already reads the stored `OrderPlaced` fact to recover the gift
part; it recovers the discount from the same place (§9). No new event, no new consumer.

**A refunded order does not return the redemption.** The shopper used the code (D9).

---

## 4. Decisions

| # | Decision |
|---|---|
| V1 | **A voucher is never money and never reaches `balance`** (§2.2). No ledger row, no money-supply effect, no fourth funding bucket. |
| V2 | **Gross-up presentation: new contra-revenue account `4300`** (§3.2). Revenue is credited gross; the discount is a visible line. Disclosed as presentation, not measurement. |
| V3 | **Vouchers live in `shop`** (§2.1). `payment`, `balance` and `delivery` change not at all. |
| V4 | **`customer_order.total` remains the amount payable.** Everything downstream keeps its current meaning. |
| V5 | **Everything is frozen onto the order at placement** — code, percent and discount amount, exactly like `unitPrice`, `unitCost` and packaging (`accounting.md` D26). Editing or revoking a voucher never reaches back into a placed order. |
| V6 | **Expiry is evaluated at placement only.** An order placed one second before expiry keeps its discount however late it is paid, and a `RetryPayment` never re-prices. A placed order's total does not move. |
| V7 | **The discount applies to the items subtotal, not to packaging** (§6). Boxes are charged in full. |
| V8 | **One redemption per user per voucher**, enforced by a primary key, not by a check-then-act (§7). |
| V16 | **ADMIN *and* MANAGER may create and revoke vouchers** (§8.3), and both back offices have a page for it (§11.1). |
| V9 | **A refund does not return the redemption** (§3.4). |
| V10 | **`valid_until` is mandatory.** A voucher without an expiry is a permanent price cut wearing a code, and nothing in the system would ever retire it. |
| V11 | **The server prices; the SPA displays.** The preview endpoint is authoritative and non-binding; placement re-validates and re-prices from scratch (§8.1). |
| V12 | **Codes are stored and compared upper-case, trimmed.** `spring25` and `SPRING25` are the same voucher; two vouchers differing only by case cannot exist. |
| V13 | **Revoke, never delete.** Orders reference the voucher row. `DELETE` sets `revoked_at`. |
| V14 | **Buckets and display are `Europe/Zurich`, ISO weeks** — `accounting.md` D15, unchanged. Expiry is compared in UTC against the application clock — the same `Instant.now()` that already stamps `created_at`, rather than a second round trip to ask the database the time. |
| V15 | **No feature flag.** Vouchers are inert until an admin creates one; an empty table *is* the off switch. |

---

## 5. Data model (`shop`)

New changelog `020-add-vouchers.sql` (next free number after `019`).

```sql
--changeset moldo:020-add-voucher
CREATE TABLE voucher (
    id           BIGSERIAL PRIMARY KEY,
    code         TEXT        NOT NULL UNIQUE,      -- upper-case, trimmed (V12)
    percent_off  SMALLINT    NOT NULL CHECK (percent_off BETWEEN 1 AND 100),
    valid_from   TIMESTAMPTZ NOT NULL DEFAULT now(),
    valid_until  TIMESTAMPTZ NOT NULL,             -- mandatory (V10)
    revoked_at   TIMESTAMPTZ,                      -- V13
    description  TEXT,                             -- what this campaign was, for the admin list
    created_by   TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (valid_until > valid_from)
);

--changeset moldo:020-add-voucher-redemption
-- The primary key IS the once-per-user rule (V8). A check-then-act in Java loses the
-- race between two concurrent checkouts; a unique violation cannot.
CREATE TABLE voucher_redemption (
    voucher_id  BIGINT      NOT NULL REFERENCES voucher(id),
    username    TEXT        NOT NULL,
    order_id    BIGINT      NOT NULL REFERENCES customer_order(id),
    redeemed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (voucher_id, username)
);

--changeset moldo:020-add-order-discount
-- Snapshots, not a foreign key to the voucher's current state (V5). packaging_total's
-- comment applies verbatim: without a column of its own, a reconciliation of the line
-- items against the total stops balancing and nothing says why.
ALTER TABLE customer_order
    ADD COLUMN voucher_code     TEXT,
    ADD COLUMN discount_percent SMALLINT,
    ADD COLUMN discount_total   NUMERIC(19,2) NOT NULL DEFAULT 0;
```

`percent_off` is a whole percent. No currency column: a percentage is currency-free, which is one
of the reasons percentage vouchers are the right first feature in a shop that must never sum
across currencies (D14).

`discount_total` defaults to `0`, so every pre-existing order reads as an undiscounted one —
which is what it is. No backfill, no estimate, unlike `008` and `013`.

---

## 6. Pricing: the order of operations

One place, `VoucherService.price(...)`, called from `OrderService.validateAndBuild` after
packaging is planned and before the order is saved.

```
 itemsTotal      = Σ unitPrice × quantity            (already computed today)
 discountTotal   = round(itemsTotal × percent / 100, 2, HALF_UP)     ← V7: items only
 packagingTotal  = the packaging plan's total        (undiscounted)
 ─────────────────────────────────────────────────────────────────
 total           = itemsTotal − discountTotal + packagingTotal        ← what is payable
 grossTotal      = itemsTotal + packagingTotal        (derived: total + discountTotal)
```

- **`discountTotal` is the stored truth; the percentage is a label.** Nothing recomputes
  `itemsTotal × percent` later — one HALF_UP rounding happens once, at placement, and every
  consumer subtracts the stored amount. This is why the reconciliation can be an exact equality
  rather than a tolerance.
- **`grossTotal` is not stored**, because it is exactly `total + discount_total` and a stored
  copy is one more thing that can disagree.
- **Rounding is HALF_UP to 2 decimals**, matching the rest of `shop`'s `BigDecimal` money. The
  discount absorbs the rounding, never the total.

### 6.1 The zero-total edge — a real gotcha

`percent_off = 100` on a cart of bars (which need no box) produces `total = 0.00`. Stripe rejects
amounts below roughly CHF 0.50, PayPal likewise, and a zero-amount balance spend is a ledger row
that moves nothing.

**The guard:** placement rejects an order whose payable total is below
`shop.minimum-payable-total` (default `0.50`) with a 400 naming the voucher, and the admin form
warns above 90%. A free-order flow is a *different feature* (it would have to skip the payment
provider entirely) and is out of scope — the schema allows 100 so that the ceiling is a product
decision rather than a migration, but nothing today can charge the result.

---

## 7. Redemption and abuse

The chosen bound is **once per user, no global cap** (V8), which means the maximum exposure of a
leaked code is *(registered accounts) × (percent × basket)*. Registration is open. State that
plainly:

- **The residual risk is account creation, not voucher validation.** One person with ten accounts
  redeems ten times, and nothing here stops them. The cheap mitigation if a code does leak is
  `revoked_at` (V13) — one `DELETE`, effective immediately, and already in scope.
- **A global `max_redemptions` column is deliberately not built** (§13). It is a single column
  plus a conditional `UPDATE ... WHERE redeemed_count < max_redemptions RETURNING`, and it is the
  first thing to add if a campaign ever needs a budget.
- **Codes should be unguessable** where they are not meant to be public: generated from an
  unambiguous alphabet (no `O`/`0`, `I`/`1`), at least 8 characters. `SPRING25` is fine for a
  code printed on a poster; it is not fine for a per-customer apology voucher.
- **The preview endpoint is an oracle** for whether a code exists. That is acceptable — it must
  be, since checkout has to tell a shopper *why* their code was refused — but it means the code
  space needs to be large enough that guessing is uneconomic, and it argues for rate-limiting
  `preview` per user if abuse ever shows up.

---

## 8. The API

### 8.1 Preview — `POST /api/shop/vouchers/preview`

Modelled on `POST /api/shop/packaging/quote`: authenticated (a checkout step, and the cart is the
shopper's), reprices server-side, **stores nothing, reserves nothing**.

```jsonc
// request
{ "code": "spring25",
  "items": [{ "productId": 3, "quantity": 2 }],
  "packaging": [{ "groupId": 1, "optionId": 4 }] }

// 200 — valid
{ "code": "SPRING25", "percentOff": 10, "validUntil": "2026-03-31T21:59:59Z",
  "itemsTotal": 54.00, "discountTotal": 5.40,
  "packagingTotal": 6.00, "payableTotal": 54.60, "currency": "CHF" }

// 200 — refused, with a reason the UI can phrase
{ "code": "SPRING25", "valid": false, "reason": "EXPIRED" }
```

`reason` ∈ `NOT_FOUND | NOT_YET_VALID | EXPIRED | REVOKED | ALREADY_USED | BELOW_MINIMUM`.
A refusal is a 200 with `valid: false`, not a 404 — the request was well-formed and the answer is
information the checkout page needs to render, not an error.

**The preview is not a reservation** (V11). Between preview and placement a voucher can expire or
be revoked; placement re-validates from scratch and is the only thing that counts.

### 8.2 Placement — `POST /api/shop/orders`

`PlaceOrderRequest` gains a nullable `voucherCode`. Its existing compatibility comment applies
unchanged: a record's canonical constructor is fixed-arity but deserialisation is by name, so
every current caller keeps working and reads as an order with no voucher.

`OrderResponse` gains `voucherCode`, `discountPercent` and `discountTotal`, beside
`packagingTotal`, with a matching legacy constructor defaulting the discount to zero.

Failure at placement is a **400** naming the reason (the same enum), except `ALREADY_USED` on a
concurrent double-submit, which surfaces as **409**.

### 8.3 Admin — `/api/shop/admin/vouchers`

| Method | Path | Role |
|---|---|---|
| `GET` | `/api/shop/admin/vouchers` | ADMIN + MANAGER |
| `GET` | `/api/shop/admin/vouchers/{id}` | ADMIN + MANAGER (includes redemption count and who) |
| `POST` | `/api/shop/admin/vouchers` | ADMIN + MANAGER |
| `DELETE` | `/api/shop/admin/vouchers/{id}` | ADMIN + MANAGER — revokes (V13) |

**Deliberately looser than packaging maintenance, which stays ADMIN-only.** Running a discount
campaign is the manager's job, and the objection that creating a voucher decides what future
shoppers are charged proves less than it looks: a manager can already refund an order in full,
so they can already give money away. A percentage off is the smaller power, revoking is instant,
and a placed order keeps the discount it was charged (V5).

Retiring a box is not comparable — it takes a whole group of products off sale.

> **`ShopSec` must carry explicit `POST` and `DELETE` rules for `/api/shop/admin/vouchers/**`.**
> The existing admin rule is `GET`-only; without new rules these fall through to
> `anyExchange().authenticated()` and **any logged-in user could mint a 100% voucher**. This is
> the single highest-risk line in the whole change — CLAUDE.md's warning that a gateway route
> protects nothing, in its most expensive form.

---

## 9. Events

`OrderPlaced` gains three fields in `OrderService.buildPayload`:

```jsonc
{ "...": "unchanged", "total": 54.60, "packagingTotal": 6.00,
  "voucherCode": "SPRING25", "discountPercent": 10, "discountTotal": 5.40 }
```

Facts, never journal instructions (D24), and frozen at emit time (D26). `total` keeps its current
meaning, so **no existing consumer needs to change to keep working** — `payment` charges it,
`delivery` ships it, `balance` spends it.

No new topic and no new producer. `accounting` already stores every `OrderPlaced` fact and reads
it back at delivery, which is exactly where the extra fields are needed.

---

## 10. `accounting` changes

Four small edits, all inside the service — which is the point of `accounting.md` §4.4's "one
table of rules, in one place".

1. **`Accounts`**: `public static final String CONTRA_VOUCHER = "4300";`
2. **Chart seed** (new changelog): `4300 — Contra-revenue: voucher discounts`, contra-revenue,
   beside `4100` and `4200`.
3. **`PostingRules.delivery`** — read `discountTotal` from the stored `OrderPlaced` payload
   exactly as it reads the total today, then:

   ```
   Dr 2010 Deferred revenue   total − gift
   Dr 4100 Contra-revenue     gift              (if > 0, unchanged)
   Dr 4300 Contra-revenue     discount          (if > 0, new)
       Cr 4000 Revenue            total + discount
   ```

   Zero discount produces byte-identical journals to today's — the regression test writes itself.
4. **`PostingRules.refundRequested`** — reverse the voucher leg in the same proportion as the
   gift leg, in both the delivered and undelivered branches (§3.4).

The accrual endpoint (`GET /api/accounting/revenue`) gains a `voucherDiscounts` line beside the
gift and returns lines; gross revenue minus all three contra accounts is net revenue, as now.

**`balance`, `payment` and `delivery`: no changes.** If a step in the implementation appears to
need one, the design has been misread — go back to §2.

---

## 11. The storefronts

**Two SPAs, not one.** `ui-shop` and `ui-demo` are separate React applications serving different
domains (`k8s/hetzner` overlay `app-multi` runs both), each with its own checkout, order views and
revenue page. A voucher field in only one of them means a code printed on a sichocolate.com box
cannot be redeemed on sichocolate.com.

Shopper-facing work is therefore done **twice**, in parallel files:

| Concern | `ui-shop` | `ui-demo` |
|---|---|---|
| Voucher admin page | `pages/VouchersManagement.tsx` | `pages/VouchersPage.tsx` |
| Code field, preview call, discount line | `pages/Checkout.tsx` | `pages/CheckoutPage.tsx` |
| Discount on the order list | `pages/Orders.tsx` | `pages/OrdersPage.tsx` |
| Discount on the order detail | `pages/OrderDetail.tsx` | `pages/OrderDetailPage.tsx` |
| Voucher column on the accrual table | `pages/Revenues.tsx` | `pages/RevenuesPage.tsx` |
| API client + types | `src/api.ts` | `src/api.ts`, `src/types.ts` |

Rules that hold in both:

- **The discount shown is the server's number**, never computed in the browser — the existing
  packaging comment in both checkouts (*"Read off the quote the server sent, never computed
  here"*) applies unchanged. The order response's total stays authoritative for what was charged.
- **No voucher field in the cart** (`Cart.tsx`, `CartDrawer.tsx`). A discount needs the packaging
  plan to exist, and a half-priced number in the cart that changes at checkout is worse than no
  number.
- **The accrual table gains one column** between gift credit and expected returns, so gross,
  the three contra lines and net read left to right in the order they net.

### 11.1 Admin management, in both back offices

A Vouchers page in each SPA — `ui-shop/pages/VouchersManagement.tsx` and
`ui-demo/pages/VouchersPage.tsx`, both routed at `/admin/vouchers`. It was ui-shop-only at
first, on the argument that one back office is enough; that was wrong in practice, because
whoever runs sichocolate.com's campaigns works in sichocolate.com's admin panel.

**The link cannot live only on the admin panel.** Both panels are gated on `isAdmin`, so a link
inside one is invisible to exactly the MANAGERs the role rule was widened for. Vouchers is
therefore also in each `AccountNav` under `isAdmin || isManager` — the one back-office screen a
manager can reach. The panels themselves stay ADMIN-only; widening them would change who sees
products, orders and customers, which is a much larger authorization decision than this one.

Renders in both: create (code, percent, expiry, description) with a warning above 90% (§6.1),
and a list of every voucher including expired and revoked ones, with redemption counts and a
Withdraw button.

## 12. Implementation steps

Each step is independently deployable and leaves the system working.

| # | Step | Where |
|---|---|---|
| 1 | Schema: `020-add-vouchers.sql` — the two tables and three order columns (§5) | shop |
| 2 | `Voucher`, `VoucherRedemption` domain + repositories | shop |
| 3 | `VoucherService`: normalise (V12), validate the window (V6), price (§6) | shop |
| 4 | Wire into `OrderService`: discount before save, redemption row **in the same transaction** as the order, snapshots onto `customer_order`, three new `OrderPlaced` fields, `OrderResponse` fields | shop |
| 5 | `POST /api/shop/vouchers/preview` — handler, route, `ShopSec` rule (authenticated) | shop |
| 6 | Admin CRUD + **the `POST`/`DELETE` `ShopSec` rules from §8.3** | shop |
| 7 | `4300` in `Accounts`, chart changelog, `PostingRules.delivery` gross-up | accounting |
| 8 | `PostingRules.refundRequested` voucher reversal | accounting |
| 9 | `voucherDiscounts` on the accrual endpoint | accounting |
| 10 | Checkout field + order views + the `4300` line on Revenues | ui-shop |
| 11 | Admin vouchers page (§11.1) | ui-shop |
| 12 | Checkout field + order views, in the parallel files | ui-demo |
| 13 | The `4300` line on `RevenuesPage` | ui-demo |
| 14 | Admin vouchers page + the `AccountNav` link managers reach it by (§11.1) | ui-demo, ui-shop |

Steps 10–13 are two passes over the same ground, one per storefront (§11). Steps 1–6 ship a working feature on their own: orders are discounted and charged correctly, and
the books simply record the net sale until step 7 lands. Steps 7–9 are what make the discount
*visible*, and they are worth landing in the same release, because a discount that is netted for
three days and grossed up afterwards makes two adjacent periods incomparable.

### 12.1 Verification

The invariants below matter more than unit tests here, and most are cluster-checkable:

1. **Every order reconciles.** No row may fail
   `items + packaging − discount_total = total`.
   ```sql
   SELECT o.id FROM customer_order o
   WHERE o.total <> (SELECT COALESCE(SUM(i.unit_price * i.quantity), 0)
                     FROM order_item i WHERE i.order_id = o.id)
                  + COALESCE(o.packaging_total, 0) - o.discount_total;
   ```
2. **`discount_total > 0` ⟺ `voucher_code IS NOT NULL`.** Both directions.
3. **`4300`'s balance equals** `SUM(discount_total)` over delivered, unrefunded orders — the
   accounting-side mirror of (1), and the check that catches a posting rule that grossed up the
   wrong number.
4. **No voucher ever appears in `balance`.** `GET /api/balance/admin/money-supply` must be
   byte-identical before and after a discounted order is placed, paid and delivered (V1).
5. **Double-submit.** Place two orders with the same code as the same user concurrently: exactly
   one succeeds, the other is a 409, and the ledger and outbox show one order.
6. **Expiry at the boundary.** An order placed before `valid_until` and paid after it keeps its
   discount (V6).

The end-to-end walk in the cluster: create a 10% voucher as admin → apply it at checkout →
confirm the Stripe intent is for the *discounted* amount → deliver the order → confirm the
accrual report shows gross sales unreduced and a CHF discount line, and that they net to what was
charged.

---

## 13. Out of scope, on purpose

Fixed-amount vouchers · stacking more than one code · per-product, per-category or per-storefront
restrictions · global redemption caps and campaign budgets (§7) · auto-applied promotions ·
free-shipping vouchers (shipping is not charged — D30) · first-order-only rules · scheduled
campaign activation beyond `valid_from` · breakage or any accrual for unredeemed vouchers (there
is nothing to accrue — §2.2) · a promotions service (§2.3).

## 14. Open items

- **The zero-total guard** (§6.1) ships as `shop.minimum-payable-total`, defaulting to `0.50` in
  `shop/application.yaml` and overridable with `SHOP_MINIMUM_PAYABLE_TOTAL`. No k8s override is
  set, matching `SHOP_CURRENCY`, which is also left at its application default. **`0.50` is a
  guess at the strictest provider minimum, not a measured number** — worth checking against
  Stripe's and PayPal's actual CHF minimums before a campaign goes above 90%.
- **Rate-limiting `preview`** (§7) is unbuilt and probably unnecessary until a code is public.
- ~~Whether MANAGER should be able to create vouchers.~~ **Decided: yes** (§8.3). The
  counter-argument won — a manager who can already refund an order can already give money away.
