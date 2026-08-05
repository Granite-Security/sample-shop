# `balance` — the platform's central bank

Status: **Steps 0–5 built (backend complete).** Balance is enabled at checkout beside
Stripe and PayPal. The storefront UI — balance page, admin gift control, top-up screen — is
the remaining work.

`PAYMENT_PROVIDER_BALANCE_ENABLED` is **on** in `k8s/base/config.yaml`, so balance appears at
checkout beside Stripe and PayPal. The application default stays `false`, so a `payment` run
without a balance service alongside it does not advertise a provider it cannot reach.

Users hold a CHF balance: top it up with any payment provider, spend it on shop orders, send
some to another user, and admins can gift it.

**The mandate.** `balance` is the only component that may create, destroy or move CHF credit.
It is the single writer of one append-only ledger. Money enters through exactly two doors —
**backed** (a confirmed provider payment) and **unbacked** (an admin gift) — and the ledger
keeps them apart forever.

Three scope decisions that remove most of the hard problems. Flipping any of them later is a
redesign, not a setting:

- **Demo credit, not customer funds.** Providers in test mode. No KYC, no safeguarding.
- **All-or-nothing per order.** Balance pays an order in full or declines it. No split tender
  with a card. **Balance decides** — the UI never inspects a balance to decide what to offer
  (§4.2).
- **No cash-out.** Money leaves only by buying things.

## 1. How it fits

`balance` is a **third payment provider**, sitting in the same registry as Stripe and PayPal
behind the same SPI. It is also a service the shopper's own account pages talk to directly.

```
                            ui-shop / ui-demo
                                   │
                       checkout: Stripe │ PayPal │ Balance
                                   │
                                   ▼
                             gateway :8080
                  ┌────────────────┴──────────────────┐
        /api/balance/**                       /api/payments/**
                  │                                   ▼
                  │                 ┌─────────────────────────────────┐
                  │                 │  payment :8062                  │
                  │                 │  PaymentProviderRegistry        │
                  │                 │ ┌───────────┬─────────────────┐ │
                  │                 │ │ Stripe    │ PayPal          │ │
                  │                 │ │ CLIENT_SDK│ REDIRECT        │ │
                  │                 │ ├───────────┴─────────────────┤ │
                  │                 │ │ BalanceProvider   REDIRECT  │ │
                  │                 │ └─────────┬───────────────────┘ │
                  │                 └───────────┼─────────────────────┘
                  │      /internal/intents      │        │
                  ▼   ◄────────────────────────-┘        │ payments.events
      ┌───────────────────────────┐                      ▼
      │  balance :8067            │              ┌──────────────┐
      │  ledger + house accounts  │              │  shop :8061  │ ← unchanged
      │  CreditPolicy             │              └──────────────┘
      │  THE ONLY WRITER OF MONEY │                      ▲
      └───────────┬───────────────┘                      │
                  │ validate recipient    payments.events (SUCCEEDED)
                  ▼                       ─ consumed by shop AND balance
            profile :8064                   (balance takes purpose=TOPUP only)
```

`balance` never talks to Stripe or PayPal — `payment` owns acquiring. And because paying with
balance goes through the provider seam, **`shop` needs no changes at all**.

### 1.1 Paying an order with balance

Every arrow below already exists for PayPal. Nothing here is new machinery.

```
 shopper picks "Balance" at checkout
   │
   ├─ shop  places order → PENDING
   │
   ├─ payment.createIntent(provider=balance)
   │     └─► balance  POST /internal/intents
   │              intent CREATED · funds checked · NO ledger write
   │     ◄── redirectUrl = /api/payments/return/balance?orderId=N
   │
   ├─ browser follows it   (RedirectPaymentWidget — unchanged)
   │
   ├─ payment  GET /api/payments/return/balance → finalizePayment()
   │     └─► balance  POST /internal/intents/{id}/capture     ← idempotent
   │              CreditPolicy: balance_minor > 0 ?
   │                 accept → user ──► house:shop   (2 ledger rows)  → CAPTURED
   │                 decline→ nothing written                        → FAILED
   │
   ├─ payment  outbox → payments.events  (SUCCEEDED | FAILED)
   │
   └─ shop  consumes → PAID     (or PAYMENT_FAILED → retry, pick a card)
```

### 1.2 Topping up

The mirror image: an ordinary provider payment that credits balance instead of paying an
order. **This is the only way money enters from outside**, which is why it is confirmed by
the provider and never by the browser (D9).

```
 ui → payment  POST /api/payments/topup-intent   (purpose=TOPUP, order_id=NULL)
        └─ Stripe or PayPal, exactly as for an order
        └─ webhook or /sync confirms
        └─ outbox → payments.events {purpose:TOPUP, username, amountMinor}
                          │
                          ▼
                       balance  → house:topup ──► user
```

## 2. The money flows, as ledger movements

Every movement is a debit and an equal credit. House accounts are the counterparty to money
entering and leaving, and their negative balance *is* the money supply.

```
 TOP-UP     provider payment confirmed ──► house:topup ──► user     (backed issuance)
 GIFT       admin                       ──► house:gift  ──► user     (unbacked issuance)
 SPEND      user ──► house:shop                                      (redeemed)
 REFUND     house:refund ──► user                                    (reissued)
 TRANSFER   user ──► user                       (no house account touched, supply unchanged)
```

A user balance may go **negative** when `CreditPolicy` extends credit (§4.2). That is a loan,
not a broken invariant: the entries still sum to zero, and the negative balance is the
receivable.

So `|house:gift|` is the total credit conjured out of nothing — the number to watch — and
`|house:topup|` is the total backed by real payments. The invariants that must always hold
are in §7.1.

## 3. API

Everything under `/api/balance/**`, routed by the gateway with the JWT relayed.

| Method | Path | Who | Does |
|---|---|---|---|
| `GET` | `/api/balance/me` | user | `{ balanceMinor, balanceChf, currency }` |
| `GET` | `/api/balance/me/transactions?page=&size=` | user | their ledger entries, newest first |
| `POST` | `/api/balance/me/transfers` | user | `{ to, amountChf, idempotencyKey }` → send money |
| `POST` | `/api/balance/admin/gifts` | `ROLE_ADMIN` | `{ username, amountChf, reason, idempotencyKey }` |
| `GET` | `/api/balance/admin/reconcile` | `ROLE_ADMIN` | invariant check + money-supply report |
| `POST` | `/api/balance/internal/intents` | `SCOPE_internal` | check funds, record an intent — **no ledger write** |
| `POST` | `/api/balance/internal/intents/{id}/capture` | `SCOPE_internal` | idempotent debit → two ledger rows |
| `GET` | `/api/balance/internal/intents/{id}` | `SCOPE_internal` | status; backs `retrieveIntent` and `/sync` |
| `POST` | `/api/balance/internal/intents/{id}/refund` | `SCOPE_internal` | compensating credit |

The four `internal` endpoints exist because they are what the `PaymentProvider` SPI needs:
`createIntent`, `finalizePayment`, `retrieveIntent`, `createRefund`. Balance exposes intent
objects the way Stripe and PayPal do — that is what makes it an ordinary provider (§4.1)
rather than a special case wired into checkout.

Plus one new endpoint in **payment**:

| Method | Path | Does |
|---|---|---|
| `POST` | `/api/payments/topup-intent` | `{ amountChf, provider }` → a provider intent with `purpose=TOPUP` |

The sender/owner is always the JWT subject. No request body ever carries a `from`.

## 4. Decisions

| # | Decision |
|---|---|
| D1 | **Ledger, not a balance column.** `account.balance_minor` is a cache, provable against `SUM(entries)`. |
| D2 | **Double-entry with house accounts** (§2). Otherwise gifts create money from nowhere and nothing reconciles. |
| D3 | **`BIGINT` rappen.** Never `double`, never `BigDecimal` in the ledger. Convert at the edge with payment's `MinorUnits`. |
| D4 | **CHF only, hard-coded.** No FX. |
| D5 | **Idempotency key on every mutating call**, `UNIQUE` in the DB, original response stored so retries return it. |
| D6 | **Balance alone decides whether to accept a payment**, exactly as PayPal does. The rule lives in one `CreditPolicy` class and nowhere else — not in checkout, not in `payment`, not in a `CHECK` constraint (§4.2). |
| D7 | **The debit happens at capture, not at intent creation.** Creating an intent only checks funds and records the request; `finalizePayment` writes the ledger entries. An abandoned checkout therefore leaves no hold to expire and no money moved. |
| D8 | **Balance is an ordinary `RedirectPaymentProvider`** — the same SPI PayPal implements, `ConfirmationMode.REDIRECT`, no new enum, no UI branch, no special case anywhere (§4.1). |
| D9 | **Credit only on provider confirmation** — never on a client claim or redirect return. Violating this is free money. |
| D10 | **Validate the recipient against `profile`** so money can't go to a username that doesn't exist. |
| D11 | **Admin gift is role-checked server-side**, not just hidden in the UI. |
| D12 | **Never UPDATE or DELETE a ledger row.** Corrections are compensating entries. No "set balance" endpoint exists, for anyone. |

### 4.1 Balance is just another provider

The shopper picks **Stripe, PayPal or Balance** from the same selector, and nothing downstream
knows the difference. Balance implements the *existing* `RedirectPaymentProvider` SPI — the
one PayPal already uses — so every path is shared:

| | Stripe | PayPal | **Balance** |
|---|---|---|---|
| `ConfirmationMode` | `CLIENT_SDK` | `REDIRECT` | **`REDIRECT`** |
| Two-step (approve → capture) | no | yes | **yes** |
| `finalizePayment` | — | capture order | **write the ledger entries** |
| Where the shopper goes | stays in page | paypal.com | `/api/payments/return/balance` |
| Confirms via | `/sync` | return + webhook | return + `/sync` |
| Emits `payments.events` | `payment` does | `payment` does | **`payment` does** |
| UI component | `StripePaymentWidget` | `RedirectPaymentWidget` | **`RedirectPaymentWidget`** |

PayPal's model maps onto balance exactly: *an order reaches APPROVED with no money moved, and
only capture charges it.* For balance, "approved" means funds were checked and an intent
recorded; capture writes the two ledger rows. That is why no new confirmation mode is needed
and why `PaymentWidget` needs no new case — it switches on `confirmationMode`, and balance's
is one it already handles.

The only oddity is cosmetic: the redirect goes to our own gateway rather than an external
site, so the shopper bounces through `/api/payments/return/balance?orderId=N` and straight
back. One extra round trip buys complete uniformity, and `finalizePayment` must be idempotent
anyway because the return and `/sync` race — exactly as `RedirectPaymentProvider`'s contract
already demands.

### 4.2 Balance decides, not the UI

A card issuer does not publish your limit so the shop can grey out a button; it **declines**.
Balance works the same way, and this is the single-responsibility line:

- **Checkout always offers Balance** when the provider is enabled. It never fetches a balance
  to decide what to show. It cannot, correctly — funds move between page load and capture.
- **Balance accepts or declines**, and a decline is an ordinary failed intent. It flows
  through the path that already exists: `PAYMENT_FAILED` → the existing retry → pick a card.
  Identical to a card being declined.
- The decision is taken twice, as with any provider: at `createIntent` for fast feedback
  before the redirect, and again inside the atomic capture, which is the authoritative one.

**Today's policy: accept if the balance is greater than zero — any amount.** A user holding
CHF 10 can buy a CHF 200 order and land at −190. That is a deliberate first cut of lending;
a real limit replaces this one class without touching anything else.

```java
/** The whole credit decision. Replace this to change lending policy. */
public interface CreditPolicy {
    boolean accept(Account account, long amountMinor);
}

// v1: any positive balance buys anything.
class AnyPositiveBalancePolicy implements CreditPolicy {
    public boolean accept(Account account, long amountMinor) {
        return account.getBalanceMinor() > 0;
    }
}
```

**A negative user balance is a loan**, and the books still balance: debit the user CHF 200,
credit `house:shop` CHF 200, sum zero. No new house account is needed — the negative balance
*is* the receivable. `SUM(user balances WHERE balance_minor < 0)` is total credit outstanding,
and §7.1 reports it.

**The cost, stated plainly:** the `CHECK (balance_minor >= 0)` backstop is gone for user
accounts, because "was positive before this purchase" is not expressible as a constraint. The
guard is now the conditional `UPDATE` alone (§6, step 5), and exposure per user is unbounded
until a real limit lands. Acceptable for demo credit; it would not be for real money. When
the limit arrives, a `credit_limit_minor` column plus
`CHECK (balance_minor >= -credit_limit_minor)` puts the backstop back.

## 5. Schema

`balance/src/main/resources/db/changelog/001-create-balance-schema.sql`

```sql
CREATE TABLE account (
    id            BIGSERIAL   PRIMARY KEY,
    username      VARCHAR(64) NOT NULL UNIQUE,          -- the JWT subject
    kind          VARCHAR(16) NOT NULL DEFAULT 'USER',  -- USER | HOUSE
    balance_minor BIGINT      NOT NULL DEFAULT 0,
    currency      CHAR(3)     NOT NULL DEFAULT 'CHF',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
    -- No non-negative CHECK: a user may go negative when CreditPolicy extends
    -- credit, and "was positive before this purchase" is not a constraint you
    -- can write. The guard is the conditional UPDATE (§4.2). Add
    -- credit_limit_minor + CHECK (balance_minor >= -credit_limit_minor) when a
    -- real limit lands.
);

CREATE TABLE ledger_entry (               -- append-only (D12)
    id           BIGSERIAL   PRIMARY KEY,
    transfer_id  UUID        NOT NULL,    -- groups the two legs of one movement
    account_id   BIGINT      NOT NULL REFERENCES account(id),
    amount_minor BIGINT      NOT NULL,    -- signed; the two legs sum to zero
    currency     CHAR(3)     NOT NULL DEFAULT 'CHF',
    kind         VARCHAR(16) NOT NULL,    -- TOPUP | SPEND | REFUND | TRANSFER | GIFT
    reference    VARCHAR(128),            -- order id, payment id, acting admin
    memo         VARCHAR(200),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ledger_account ON ledger_entry(account_id, created_at DESC);
CREATE INDEX idx_ledger_transfer ON ledger_entry(transfer_id);

-- Balance's equivalent of a Stripe PaymentIntent or a PayPal order: the object
-- payment holds an id for. CREATED means funds were checked and nothing moved;
-- only CAPTURED has ledger rows behind it (D7).
CREATE TABLE balance_intent (
    id           UUID         PRIMARY KEY,
    username     VARCHAR(64)  NOT NULL,
    amount_minor BIGINT       NOT NULL,
    order_id     BIGINT,
    status       VARCHAR(16)  NOT NULL,   -- CREATED | CAPTURED | FAILED | REFUNDED
    transfer_id  UUID,                    -- set on capture; links to ledger_entry
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_balance_intent_order ON balance_intent(order_id);

CREATE TABLE idempotency (                -- D5
    key         VARCHAR(128) PRIMARY KEY,
    transfer_id UUID         NOT NULL,
    response    TEXT         NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO account (username, kind) VALUES
    ('house:topup','HOUSE'), ('house:gift','HOUSE'),
    ('house:shop','HOUSE'), ('house:refund','HOUSE');
```

## 6. Build steps

### Step 0 — fix the scaffold *(do this first)*

`balance/build.gradle.kts` is missing three dependencies every comparable service has:

```kotlin
implementation("org.springframework.boot:spring-boot-starter-liquibase")
implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
implementation("org.springframework:spring-jdbc")   // Liquibase needs a blocking DataSource
```

Without the second, **there is no JWT validation and every endpoint is open**, including
`/admin/gifts`. Then copy the `r2dbc` + `datasource` + `liquibase` blocks from
`profile/src/main/resources/application.yaml`, and copy `profile/Dockerfile`.

### Step 1 — the bank *(no money moves yet)*

1. `001-create-balance-schema.sql` + `db.changelog-master.yaml`.
2. `domain/Account`, `domain/LedgerEntry` — Lombok `@Getter/@Setter`, `@Column` for snake_case.
3. `repository/` — `AccountRepository`, `LedgerEntryRepository`, `IdempotencyRepository`.
4. `security/BalanceSec` — copy `ProfileSec`; `/internal/**` → `SCOPE_internal`,
   `/admin/**` → `ROLE_ADMIN`, `/me/**` → authenticated.
5. `service/BalanceService` — one `@Transactional` method, `move(from, to, amount, kind, ref)`,
   writing two entries + both cached balances. Everything else calls this. The debit is a
   **conditional** `UPDATE` carrying the policy predicate, and you act on the row count:
   ```sql
   UPDATE account SET balance_minor = balance_minor - :amt, updated_at = now()
   WHERE id = :id AND balance_minor > 0        -- AnyPositiveBalancePolicy
   ```
   Zero rows updated = declined. Never read-then-write: the check and the debit are one
   statement, which is what makes two concurrent spends safe.
6. `service/CreditPolicy` + `AnyPositiveBalancePolicy` (§4.2). House-account legs bypass it.
7. `handler/BalanceHandler` + `route/BalanceRoute` — `GET /me`, `GET /me/transactions`.
8. `GET /admin/reconcile` — the three invariants (§7.1).
9. Gateway route `/api/balance/**`; k8s deployment + Postgres + `app-multi` overlay entry.

### Step 2 — unbacked issuance (admin gift)

10. `POST /admin/gifts` → `move(house:gift → user, GIFT)`. Record the acting admin in `reference`.
11. Admin UI control in `ui-shop` and `ui-demo`.

This is the cheapest way to get real balances in — `payment` is not involved.

### Step 3 — transfer

12. `client/ProfileClient` to validate the recipient (D10).
13. `POST /me/transfers` → reject self-transfer, `move(sender → recipient, TRANSFER)`.
    **Lock accounts in ascending id order** or two users paying each other will deadlock.
14. `IdempotencyService`: look up the key, return the stored response, or run and store.
15. Balance page in both UIs — amount, history, send form.

### Step 4 — redemption (pay an order)

16. In `balance`: `balance_intent` table + the four `/internal/intents*` endpoints.
    Capture writes the ledger rows and must be **idempotent** — re-capturing a `CAPTURED`
    intent returns its existing state and writes nothing.
17. In `payment`: `provider/balance/BalanceProvider implements RedirectPaymentProvider` +
    `BalanceClient`. `confirmationMode()` → `REDIRECT`, `webhookEnabled()` → `false`,
    `supportedCurrencies()` → `{CHF}`, `createIntent` → returns a `redirectUrl` of
    `/api/payments/return/balance?orderId=N`, `finalizePayment` → capture,
    `retrieveIntent` → GET, `createRefund` → refund.
18. Enable it: `payment.providers.balance.enabled=true` — the same
    `@ConditionalOnProperty` switch Stripe and PayPal use.
19. UI: **nothing to build.** The provider selector is driven by
    `GET /api/payments/providers`, and `RedirectPaymentWidget` already handles
    `REDIRECT`. Only worth adding a disabled state when `balance < total`.

`shop` is not touched, and neither is `PaymentWidget`. Insufficient funds → failed intent →
`PAYMENT_FAILED` via the existing path → the existing retry lets them pick a card.

### Step 5 — backed issuance (top-up)

20. `payment` migration `006-topup-support.sql`: `order_id` nullable, swap the unique index for
    a partial one (`WHERE order_id IS NOT NULL`), add `purpose` and `username`.
21. `POST /api/payments/topup-intent`; outbox payload gains `purpose` + `username`.
22. `balance/consumer/PaymentEventConsumer` on `payments.events` — ignore anything but
    `purpose=TOPUP`, idempotent on the payment id, `move(house:topup → user, TOPUP)`.
23. Top-up UI in both storefronts.

Last on purpose: it's the only step that lets money in from outside the system.

## 7. Gotchas

### 7.1 Prove the ledger, don't trust it

`GET /admin/reconcile` checks all three, and you run it after every deploy:

```
SUM(ledger_entry.amount_minor) = 0            -- money neither created nor destroyed
account.balance_minor = SUM(its entries)      -- no cache drift
SUM(user balances) = -SUM(house balances)     -- supply identity
```

It also reports the numbers an operator actually wants, all of which fall out of §2:

```
|house:gift|                                  -- unbacked issuance
|house:topup|                                 -- backed issuance
SUM(balance_minor) WHERE kind='USER' AND balance_minor < 0
                                              -- credit outstanding (§4.2)
```

Credit outstanding is the one to watch once lending is on: with `AnyPositiveBalancePolicy`
it is unbounded by construction.

### 7.2 The rest

- **No `.block()` anywhere in `balance`.** `shop`'s `EventConsumer` does it; that must not be
  copied into a ledger. Both legs + the idempotency row commit together or not at all.
- **The top-up event is money.** Anything that can publish `PaymentSucceeded` with
  `purpose=TOPUP` can mint credit — so `kafka-ui` keeps having no HTTPRoute (`CLAUDE.md`), and
  `/internal/**` stays behind `SCOPE_internal`.
- **One identity, one account.** `UNIQUE(username)` means two identities = two balances. That
  prerequisite is **done** (`docs/users/identity-merge.md`); keep it that way, because
  re-merging after balances exist means moving money and writing compensating entries.
- **A decline is not an error.** Checkout offers Balance unconditionally; balance declines by
  returning a failed intent, and the existing `PAYMENT_FAILED` → retry path handles it exactly
  as it handles a declined card (§4.2). Nothing upstream should special-case it.
- **The policy is evaluated inside the atomic `UPDATE`**, never as a separate read. A balance
  read a moment earlier is already stale — that is the same reason the UI must not pre-check.

## 8. Verify

1. Gift CHF 10 → user `1000`, `house:gift` `-1000`, two entries.
2. Reconcile: zero drift, all three invariants hold. **Re-run after every step below.**
3. Replay an idempotency key → one movement, same response, no second entry.
4. Transfer more than you hold → rejected, ledger untouched.
5. Two concurrent spends of a balance covering one → exactly one succeeds.
6. Balance appears in the checkout selector next to Stripe and PayPal, with **no UI change**,
   and is offered **regardless of the amount** — the UI never pre-checks funds.
7. Pay an order with balance → order reaches `PAID` through the unchanged shop path.
8. Hit the return URL twice for the same order → captured once, one pair of ledger rows.
9. Abandon checkout after choosing balance → intent stays `CREATED`, **no ledger rows**, no
   money moved (D7).
10. **Hold CHF 10, buy a CHF 200 order → accepted, balance lands at −190**, entries still sum
    to zero, and reconcile reports CHF 190 credit outstanding.
11. **Hold exactly CHF 0, buy anything → declined**, order goes `PAYMENT_FAILED`, retry offers
    Stripe and PayPal. Ledger untouched.
12. Cancel a paid order → refund credits back, `SUM = 0` still holds.
13. Top up in test mode → credit appears **only** after webhook/sync, never on the redirect return.
14. `POST /internal/intents` with a normal user token → `403`.
