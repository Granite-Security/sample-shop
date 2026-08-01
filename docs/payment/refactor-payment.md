# Multi-provider payments — refactor plan

Status: **proposed, not started** · 2026-07-30 · open questions reviewed
2026-08-01 (§12)

## 1. Goals and decided constraints

`payment` and `ui-shop` are Stripe-specific end to end, not "Stripe by
default". The goal is real multi-provider support — the shopper picks a
provider at checkout — plus a user balance in CHF that refunds land in.

| Decision | |
|---|---|
| Provider choice | Per-order, chosen by the shopper. Not a config-time swap. |
| Webhooks | **Optional per provider.** No webhook has ever been registered for this deployment; `/sync` is and always has been the only thing confirming payments in production. That becomes an explicit switch, not a bug to fix. |
| Currencies | Closed set: **USD, EUR, RON, CHF** — all two-decimal. No zero-decimal currencies, no sub-cent amounts. **MDL is out of scope** (Stripe does not settle it). |
| Pricing | **One active shop currency**, CHF — default switched 2026-08-01. No mixed-currency carts, no FX anywhere. Pre-cutover payments remain USD and are refundable only in USD (§8, §9.0). |
| Balance | Plain **CHF balance** per user. Fed by refunds of real payments only; spendable as an order discount; optionally withdrawn back to the original payment provider for at most what that payment captured. Promotional grants are spend-only. See §9. |

`payment.provider` / `provider_payment_id` already exist on `Payment`
(`domain/Payment.java:31-34`) but are populated with the literal `"stripe"`
(`PaymentService.doCreatePaymentIntent`) and shadowed by a redundant
`stripe_payment_intent_id` that is the column actually used. The schema
anticipated this; nothing implemented it.

Related: [`docs/stripe-integration.md`](../stripe-integration.md). Its central
claim (webhook never registered, `/sync` does the work) is accurate; its line
numbers are stale and it predates refund reconciliation. Worth a refresh pass
independently.

## 2. Coupling inventory

**`payment`**

| File | Coupling |
|---|---|
| `service/PaymentService.java` | Calls `PaymentIntent.create/retrieve`, `com.stripe.model.Refund.create/retrieve` inline in business logic. No interface between it and the SDK. Provider hardcoded to `"stripe"`. |
| ↳ amounts | `total.multiply(BigDecimal.valueOf(100)).longValue()` in three places. ×100 is correct for our currency set, but `.longValue()` truncates — see §8. |
| ↳ currency | `@Value("${stripe.currency:chf}")` — the shop currency is still Stripe-namespaced; rename to `payment.shop-currency` in step 1 (§8). |
| ↳ retry | `retryPaymentIntent` (`:239-282`) builds a fresh PaymentIntent on the existing row — a distinct provider operation. |
| ↳ idempotency | On `IdempotencyException`, falls back to `PaymentIntent.search("metadata['order_id']...")` (`:467-478`). Pure Stripe; must move inside the adapter. |
| `handler/WebhookHandler.java` | Verifies `Stripe-Signature`, deserializes Stripe `Event`/`PaymentIntent`. Single route, no notion of which provider. |
| `security/PaymentSec.java` | `.pathMatchers("/api/payments/webhook").permitAll()` is an **exact** match. Moving to `/webhook/{provider}` without changing it to `/**` makes every webhook 401 before the adapter is reached. |
| `handler/HealthHandler.java` | `GET /actuator/health/stripe` probes via `PaymentIntent.list`. |
| `domain/Payment.java` | Generic `provider`/`provider_payment_id` *and* redundant `stripe_payment_intent_id` + `client_secret` (`003-add-stripe-schema.sql`). |
| `domain/StripeEvent.java`, repo | Webhook dedupe keyed by Stripe's event shape. |
| `domain/Refund.java` | `stripe_refund_id` (`004-add-refund-table.sql:8`). |
| `dto/CreatePaymentIntentResponse.java` | `stripePaymentIntentId`, `clientSecret`, `RefundInfo.stripeRefundId` — Stripe vocabulary in the public contract. |
| Outbox payloads | `PaymentIntentCreated`/`PaymentSucceeded`/`PaymentRefunded` carry `stripePaymentIntentId`, `stripeRefundId`, and **`clientSecret`** (`:425`) on `payments.events`. A cross-service contract, not an internal detail. |

**Other services**

| File | Coupling |
|---|---|
| `shop/.../consumer/EventConsumer.java:62` | Detects `PaymentIntentCreated` via `data.containsKey("stripePaymentIntentId")`. Renaming the field makes it fall through to `log.warn("Unknown payment event type")`. |
| `shop/.../dto/OrderResponse.java` | `clientSecret` field, "Stripe client secret" schema doc. |
| `shop/.../service/OrderService.java` | `buildOrderResponse(..., String clientSecret)` — Stripe vocabulary in shop's signatures. |
| `ui-shop` | `PaymentForm.tsx` (`useStripe`/`<PaymentElement>`/`confirmPayment`), `Checkout.tsx` + `RetryPayment.tsx` (`loadStripe`, `<Elements>`), `types.ts` field names. `RetryPayment.tsx:28` hard-errors when `clientSecret` is absent. |
| `ui-demo` | **Same coupling, and it is deployed** — `app-chocolate` serves it alone, `app-multi` (current default) alongside `ui-shop`. `types.ts`, `pages/CheckoutPage.tsx`. |

`ui-demo` means the DTO rename in §6 breaks a live frontend. **Decided:
deprecated aliases.** The API populates `clientSecret` *and* `providerPayload`
(and both id fields) for as long as it takes; `ui-shop` migrates first, and
`ui-demo` is refactored only once we are happy with `ui-shop`. The aliases are
therefore not "one release" — they stay until `ui-demo` lands, and step 4's
alias drop is gated on that, not on a date.

Practical consequence: **nothing in steps 3–4 may make an alias impossible to
populate.** `clientSecret` is only expressible as an alias while every enabled
provider is `CLIENT_SDK`; a `REDIRECT` provider has no client secret to put
there. That is fine — it just means a second provider (step 5) is hard-blocked
on `ui-demo` being migrated or written off, and the alias window closes on its
own the moment PayPal is real.

## 3. Target architecture

```
OrderPlaced(+provider) ─► PaymentService ─► PaymentProviderRegistry
                                               ├─► StripePaymentProvider
                                               └─► PayPalPaymentProvider (future)
POST /api/payments/webhook/{provider} ─► registry.get(provider)
GET  /api/payments/providers          ─► [{id, displayName, confirmationMode, webhookEnabled}]
outbox ─► provider-neutral events on payments.events

Checkout: pick provider (skipped if only one) → place order → PaymentWidget
          (switched on confirmationMode) → /sync, or webhook if enabled.
          Either order is safe; both write the same transition.
```

Key moves: a `PaymentProvider` port + registry resolved **per order from
`Payment.provider`**, never from static config; `provider` becomes a real
choice; provider-scoped webhook routes each verifying their own signature;
provider-neutral persistence, API and events; a frontend widget switch.

## 4. `PaymentProvider` port

```java
public interface PaymentProvider {
    String name();                          // "stripe", "paypal"
    String displayName();
    boolean webhookEnabled();               // config; false until registered provider-side
    ConfirmationMode confirmationMode();    // CLIENT_SDK | REDIRECT
    Set<String> supportedCurrencies();      // validated against shop currency at startup
    Mono<ProviderHealth> health();

    Mono<ProviderIntent> createIntent(CreateIntentRequest request);
    Mono<ProviderIntent> recreateIntent(CreateIntentRequest request, String previousProviderPaymentId);
    Mono<ProviderIntent> retrieveIntent(String providerPaymentId);   // backs /sync — REQUIRED
    Mono<ProviderRefund> createRefund(String providerPaymentId, Money amount, String idempotencyKey);
    Mono<ProviderRefund> retrieveRefund(String providerRefundId);

    /** Only called when webhookEnabled(). */
    ProviderWebhookEvent parseWebhook(String payload, Map<String,String> headers)
            throws WebhookVerificationException;
}
```

Four things the signature must carry or step 5 forces a port change:

- **`recreateIntent`** — retry is a distinct operation with different
  idempotency semantics; some providers must void the prior attempt first.
- **`idempotencyKey` on `CreateIntentRequest`**, not just on refunds.
  Creation is where today's idempotency lives, and where the Stripe-specific
  collision recovery lives; how an adapter recovers a collided key is its own
  business.
- **`confirmationMode`** — `CLIENT_SDK` (Stripe Elements) vs `REDIRECT`
  (PayPal). The frontend switches on *this*, not on provider id, so a second
  redirect provider needs no new component.
- **`returnUrl`/`cancelUrl` on `CreateIntentRequest`, `redirectUrl` on
  `ProviderIntent`**, plus a `GET /api/payments/return/{provider}` route.
  `CLIENT_SDK` providers never call it.

Value types (`ProviderIntent`, `ProviderRefund`, `ProviderWebhookEvent`,
`CreateIntentRequest`, `Money`) are plain records: id, status mapped to the
existing `PaymentStatus`/`RefundStatus`, `providerPayload`, amount, currency,
redirect URLs, plus `declineReason` so a decline the shopper must see arrives
as data rather than only as an exception. No SDK types cross the boundary.

`StripePaymentProvider` absorbs today's `PaymentIntentCreateParams` /
`RefundCreateParams` / `Webhook.constructEvent` calls and the
`mapStripeStatus`/`mapStripeRefundStatus` logic — moved, not rewritten.

**Registry**: a `@Component` taking `List<PaymentProvider>`, keyed by
`name()`, exposing `get(name)` and `enabled()`. Enable/disable via
`@ConditionalOnProperty(payment.providers.<name>.enabled)`, so an
implemented-but-off provider is possible.

**Service/handler**: `PaymentService` resolves the adapter via
`registry.get(payment.getProvider())`; creation takes a validated `provider`
argument. `WebhookHandler` moves to `POST /api/payments/webhook/{provider}`,
404s on unknown, and errors clearly when `webhookEnabled()` is false —
a misconfiguration worth surfacing. `StripeEvent` → `ProviderEvent`, dedupe
key `(provider, event_id)`.

## 5. Data model

```sql
UPDATE payment SET provider_payment_id = stripe_payment_intent_id
  WHERE provider_payment_id IS NULL AND stripe_payment_intent_id IS NOT NULL;
ALTER TABLE payment DROP COLUMN stripe_payment_intent_id;
ALTER TABLE payment RENAME COLUMN client_secret TO provider_payload;
ALTER TABLE payment ALTER COLUMN provider_payload TYPE TEXT;  -- was VARCHAR(255)
ALTER TABLE refund RENAME COLUMN stripe_refund_id TO provider_refund_id;
ALTER TABLE stripe_event RENAME TO provider_event;
ALTER TABLE provider_event ADD COLUMN provider VARCHAR(32) NOT NULL DEFAULT 'stripe';
```

Match the existing changelog style (preconditions + `--rollback`, as in
`003-add-stripe-schema.sql`) or the renames are one-way. `provider_payload`
must be nullable and its shape not assumed — a redirect provider hands the
client a URL, not a secret.

**Attempts.** `Payment` holds one provider per order, but the most valuable
multi-provider behaviour is "declined at Stripe → retry with PayPal", which
overwrites `provider_payment_id` in place: no audit trail, and `/sync` can no
longer reconcile the abandoned intent.

```sql
CREATE TABLE payment_attempt (
    id UUID PRIMARY KEY,
    payment_id UUID NOT NULL REFERENCES payment(id),
    order_id BIGINT NOT NULL,
    provider VARCHAR(32) NOT NULL,
    provider_payment_id VARCHAR(128),
    provider_payload TEXT,
    status VARCHAR(32) NOT NULL,
    decline_reason VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_payment_attempt_order ON payment_attempt(order_id);
-- Double-charge guard: at most one succeeded attempt per order.
CREATE UNIQUE INDEX idx_payment_attempt_one_success
    ON payment_attempt(order_id) WHERE status = 'SUCCEEDED';
```

`Payment` keeps `order_id`/`amount`/`currency`/`status` and gains
`current_attempt_id`. Backfill is one row per existing payment. **Decide now
whether this lands in steps 1–4**: deferring means a second migration and a
second pass over `PaymentService`, and until it exists, retry must stay
same-provider.

## 6. Contracts

**HTTP**

```
GET /api/payments/providers  (public)
→ [{ "id": "stripe", "displayName": "Stripe",
     "confirmationMode": "CLIENT_SDK", "webhookEnabled": false }, ...]
```

```diff
  CreatePaymentIntentResponse:
- String stripePaymentIntentId;      + String provider; String providerPaymentId;
- String clientSecret;               + JsonNode providerPayload;
  RefundInfo.stripeRefundId          → providerRefundId
  shop OrderResponse.clientSecret    → provider + providerPayload
```

`POST /api/shop/orders` gains an optional `provider`, passed through to
payment. Missing/invalid when multiple are enabled is a 400. With one enabled
provider the shopper sees no change: the selector doesn't render and `provider`
auto-fills.

**Kafka (`payments.events`)** — also a contract, since `shop` consumes it:

```diff
- "stripePaymentIntentId"  + "provider", "providerPaymentId"
- "stripeRefundId"         + "provider", "providerRefundId"
- "clientSecret"           (delete — see below)
```

`shop`'s `EventConsumer` must change in the same step. Add the new key
*alongside* the legacy branches it already carries rather than swapping, so
in-flight messages still match during rollout.

**Drop `clientSecret` from `PaymentIntentCreated` now.** It is a
payment-confirmation credential sitting in a Kafka topic that no consumer
reads — both frontends fetch it over HTTP from
`GET /api/payments/intent/{orderId}`. Renaming it to `providerPayload` makes it
worse. Same reasoning already applied to `identity.events`/kafka-ui in
`CLAUDE.md`. Independent of everything else; land it first.

**Gateway and resource server**

- `PaymentSec`: webhook matcher → `/api/payments/webhook/**`; add
  `/api/payments/return/**` and `/api/payments/providers`.
- `gateway/RouterConfig`: `/api/payments/**` already routes as one block —
  but verify in `GateSec` that `/providers` needs no OAuth2 session.
- `HealthHandler`: ~~`/actuator/health/stripe` → `/actuator/health/providers`~~
  **done in step 1.** Nothing referenced the old path — no k8s probe, no
  runbook, no frontend — so it was renamed rather than aliased. Reports
  DEGRADED rather than DOWN when a provider is unreachable, since the service
  still serves reads and drains its outbox.

## 7. Frontend

```tsx
export function PaymentWidget({ provider, confirmationMode, payload, orderId, ... }) {
  switch (confirmationMode) {
    case 'CLIENT_SDK':
      if (provider === 'stripe') return <StripePaymentWidget payload={payload} ... />;
      break;
    case 'REDIRECT':
      return <RedirectPaymentWidget redirectUrl={payload.redirectUrl} orderId={orderId} />;
  }
  throw new Error(`Unsupported payment provider: ${provider}`);
}
```

Switching on `confirmationMode` is what makes the frontend additive: most
non-card providers are redirect-shaped and need only a selector entry.

`StripePaymentWidget` is today's `<Elements>` wiring + `PaymentForm.tsx`, moved
under `components/payment/stripe/`, reading `payload.clientSecret`.
`Checkout.tsx`/`RetryPayment.tsx` render `<PaymentWidget>` and drop their
`@stripe/*` imports. A `<ProviderSelector>` renders only when
`GET /api/payments/providers` returns more than one. `syncPaymentIntent` is
already provider-agnostic and needs no change.

## 8. Money, currency, config

```yaml
payment:
  shop-currency: CHF          # renamed from stripe.currency (default was usd)
  providers:
    stripe:
      enabled: true
      webhook:
        enabled: false        # true only once registered in the dashboard
```

Provider secrets stay scoped to that provider's config class, as `StripeConfig`
does today. `STRIPE_SECRET_KEY`/`STRIPE_WEBHOOK_SECRET` are wired in
`compose.yaml` and all three Hetzner `secrets-patch.yaml.example` files —
update the checked-in examples, not just live secrets.

**Money. Done 2026-08-01** — `service/MinorUnits.java`, replacing **four**
inline copies (the plan said three; `executeRefund:166` was the fourth). It
rejects sub-cent amounts rather than truncating, uses `longValueExact()` so
overflow throws, and rejects known zero-decimal currencies outright so adding
JPY later fails loudly instead of overcharging 100×. Each call site passes the
currency it is actually charging in — `payment.getCurrency()` on the refund and
retry paths, the resolved `cur` on create. Covered by `MinorUnitsTest`.

Still outstanding: enforce the same scale rule on product prices in `shop` —
nothing stops a three-decimal price being stored today, it just now fails at
payment time instead of undercharging. Also: the refund path recomputes from
`payment.getAmount()` rather than what was captured — once attempts exist,
refund against the succeeded attempt's amount.

**Currency.** One shop currency, validated at startup to be one of the four,
and providers validated at startup to support it — refuse to enable a provider
that cannot charge it rather than discovering it on the first order. That makes
`supportedCurrencies()` a startup check, not a runtime filter, and no
`?currency=` param is needed. MDL is out of the set entirely, so nothing needs
verifying; USD/EUR/RON/CHF are all standard Stripe settlement currencies.

**The CHF switch is a cutover, not a default change.** Done 2026-08-01: the
default is now `chf` in `application.yaml`, `compose.yaml` and
`k8s/base/config.yaml` (the Hetzner overlays inherit base and never overrode
it), with the README and `kind.md` tables updated. Deliberately **lowercase**,
matching the previous `usd` — the value is passed straight to
`PaymentIntentCreateParams.setCurrency` (`PaymentService.java:451`), which
wants a lowercase ISO code.

What this does *not* do: every `payment` row written before the cutover still
carries `currency = 'USD'` (`001-create-payment-table.sql:8` defaults it), and
every historical Stripe charge is a USD charge that can only ever be refunded
in USD. The table holds two currencies from now on. Consequences, all still
outstanding:

- Stored case is **not** an issue: `doCreatePaymentIntent` persists
  `cur.toUpperCase()` and `retryPaymentIntent` lowercases only on the way out
  to Stripe, so the column is uppercase throughout. Nothing to normalise.
- Refunds are **already currency-safe**: `executeRefund` passes only
  `paymentIntent` + amount to `RefundCreateParams`, so Stripe refunds in the
  original charge's currency. There is no path where a CHF config value is
  applied to a USD charge.
- Still true: keep `payment.currency` per-row and read it rather than the
  configured shop currency, since the config is only the currency for *new*
  payments. As of 2026-08-01 the minor-unit conversion does exactly this.
- `shop` still has no currency column at all, so orders placed before it lands
  have no recoverable denomination. That window is now open.
- This interacts with the balance: see §9.0.

`shop` still needs a currency column on orders so changing the shop currency
later doesn't reinterpret history — one column, written once. There is
currently **no currency anywhere in `shop`** (no column, no field, no DTO), so
this is a new column plus a backfill of `'USD'` for existing orders, not a
rename. Do it in step 2 alongside the other migrations; doing it after the CHF
cutover means a window of orders whose currency is unrecoverable from data.

## 9. User balance (design sketch — not scoped)

**Decided.** Users have a **CHF balance**. Refunds credit it. It is spendable
as a discount on new orders, and can optionally be withdrawn back to the
provider the original payment used.

Deliberately *not* a separate currency unit: no rate, no FX, no way to buy the
balance, no top-up orders. Every payment keeps an `order_id`.

**Decided — withdrawal eligibility.** Only lots funded by a real payment are
withdrawable, and only up to the amount that payment actually captured.
**Promotional grants are never withdrawable** — they are spend-only. This is
the rule the lot model in §9.3 has to enforce, and it is what keeps the whole
feature a delayed refund rather than stored value: no path exists by which
money leaves the system that did not first enter it from the same user's card.

### 9.0 The USD/CHF boundary

The balance is CHF, but historical payments are USD (§8) — and a withdrawal
returns money to the *original charge*, which is denominated in that charge's
currency. Crediting a 20.00 USD refund as 20.00 CHF and then withdrawing
20.00 CHF back to a USD charge violates "the amount they actually paid" in both
directions, and no FX is allowed anywhere by §1.

Two options, and this needs a decision before §9.7 step 1:

- **Preferred: lots carry their own currency**, `NOT NULL`, taken from the
  funding payment. Withdrawal is exact and per-lot by construction. The
  "balance" shown to the user is then per-currency — in practice one CHF
  figure plus a shrinking USD tail that drains as it is spent or withdrawn.
  Spending a USD lot against a CHF order is the one place a rate would be
  needed, so: **USD lots are withdraw-only, not spendable.** No FX, no
  reinterpretation, and the tail disappears on its own.
- Simpler but lossy: refuse to credit refunds of pre-cutover USD payments to
  the balance at all, and keep refunding those directly to the provider on the
  old path. Fewer moving parts; leaves two refund flows alive indefinitely.

Either way, do **not** add a `currency` column to `balance_lot` later — a
single-currency lot table is the one thing in §9 that cannot be migrated
cleanly once rows exist, because the currency of an existing row is not
recoverable from the row.

### 9.1 Refund flow inverts

Today `processRefundRequested` calls Stripe immediately and moves the order to
`REFUNDED`/`REIMBURSED`. The new flow:

1. Refund approved → **credit the balance**, order → `REIMBURSED`. No provider
   call. Fast, always succeeds, no external dependency.
2. The user may then choose to withdraw that credit to their original payment
   method — *that* is when the provider refund happens.

So the existing `Refund` table and `executeRefund` are repurposed: they stop
being the refund itself and become the **withdrawal** mechanism.

### 9.2 Spending it is a discount, not a payment method

Applying balance reduces the order total; the remainder goes to the provider.
`payment` never learns the balance exists — it receives a smaller number. This
is why credit is not a `PaymentProvider`, and why "part balance, part card"
needs no split-payment machinery.

`shop` gains, alongside the existing single `total` (`CustomerOrder.java:21`):

```sql
ALTER TABLE customer_order ADD COLUMN subtotal       NUMERIC(12,2) NOT NULL;
ALTER TABLE customer_order ADD COLUMN credit_applied NUMERIC(12,2) NOT NULL DEFAULT 0;
-- total keeps its meaning: the amount actually charged. Backfill subtotal=total.
```

Keeping `total` as the charged amount means `OrderPlaced`, `payment` and every
consumer keep working untouched. Invariant:
`total = subtotal - credit_applied`, `0 <= credit_applied <= subtotal`.

### 9.3 The balance is not fungible — track lots

**This is the core design problem.** A withdrawal must go back to the provider
that took the money, but a pooled balance loses that. If 20 CHF came from a
Stripe order and 30 from a PayPal order, a 40 CHF withdrawal has no
well-defined destination — and no single provider refund can exceed what that
payment captured.

So the balance is a set of **lots**, each carrying its origin:

```sql
CREATE TABLE balance_lot (
    id UUID PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    origin VARCHAR(16) NOT NULL,         -- PAYMENT_REFUND | PROMOTIONAL_GRANT
    currency VARCHAR(8) NOT NULL,        -- §9.0 — from the funding payment
    source_order_id BIGINT,              -- null for promotional grants
    source_payment_id UUID,              -- which payment/attempt funded it
    provider VARCHAR(32),                -- where a withdrawal would go
    amount NUMERIC(12,2) NOT NULL,       -- original credit
    spent NUMERIC(12,2) NOT NULL DEFAULT 0,
    withdrawn NUMERIC(12,2) NOT NULL DEFAULT 0,
    withdrawable_until TIMESTAMPTZ,      -- provider refund window; null = never
    created_at TIMESTAMPTZ NOT NULL,
    -- Decided: promotional grants are spend-only, enforced in the schema.
    CONSTRAINT grants_not_withdrawable CHECK (
        origin <> 'PROMOTIONAL_GRANT'
        OR (withdrawn = 0 AND provider IS NULL AND withdrawable_until IS NULL)
    ),
    CONSTRAINT payment_lots_have_origin CHECK (
        origin <> 'PAYMENT_REFUND'
        OR (source_payment_id IS NOT NULL AND provider IS NOT NULL)
    ),
    CONSTRAINT lot_not_overdrawn CHECK (spent + withdrawn <= amount)
);
```

Available balance is `Σ(amount - spent - withdrawn)`. Spending consumes lots
FIFO — **grant lots first**, since they are the ones that can expire worthless
and the ones the user can never get back as money; spending a withdrawable lot
before a spend-only one destroys value the user held. Withdrawable per lot is
`amount - spent - withdrawn` for `PAYMENT_REFUND` lots and **always zero** for
grants, which gives the "can't refund more than was captured" and "grants are
not money" guarantees for free.

`origin` as an explicit column rather than inferring "grant" from
`source_payment_id IS NULL` — the inference is the kind that silently turns a
data-repair row with a missing id into withdrawable cash. The `CHECK`
constraints above are the actual enforcement point; do not rely on service
code alone for a rule about money leaving the system.

The UI the user described falls out of this naturally: **show withdrawal
per lot, not as one pooled number.** "Order #123 — 20.00 CHF — [Return to
card]". That is honest about where money goes and sidesteps the "which
provider?" question entirely.

### 9.4 Withdrawal constraints

- **Provider refund windows expire.** Stripe will not refund a charge to the
  original card indefinitely (practically ~180 days). After that the withdrawal
  must fail cleanly with an explanation, not retry forever — hence
  `withdrawable_until`, set from the funding payment's date. Verify the exact
  window per provider before setting it.
- **Spent credit is not withdrawable.** Enforced by the lot arithmetic.
- **Promotional grants are not withdrawable at all** (decided). They have no
  provider and no captured amount to return, so the withdrawal UI must not
  render a button for them — show them as "store credit", visibly distinct
  from returnable lots, so the difference is discovered before checkout rather
  than at the withdrawal screen.
- **A withdrawal never exceeds what that payment captured**, per lot, in that
  payment's currency (§9.0) — not the order total, and not the sum of lots.
- **Withdrawal is async and can fail.** It is a real provider call. Model it
  with a status (`REQUESTED`/`SUCCEEDED`/`FAILED`) and reconcile via the
  existing refund `/sync` path; on failure, return the amount to the lot.
- **Naming.** "Refund to card" is wrong once PayPal exists, and misleading
  since the money is already refunded. Prefer **"Return to original payment
  method"**, with the provider rendered per lot ("Return to Visa ••4242").

### 9.5 Where it lives

Its own service (`wallet`; port 8065 is free), append-only double-entry
(`balance_lot` + `ledger_entry`), atomic conditional updates rather than
read-then-write. `shop` applies the discount, so `shop` holds and commits;
`payment` never talks to it.

Spending needs a **hold**, not a bare debit, or a decline after placement
loses the user's credit and concurrent orders double-spend it:

| Step | Ledger |
|---|---|
| Order placed with `credit_applied > 0` | `RESERVE` — balance reduced |
| `PaymentSucceeded` | `COMMIT` |
| `PaymentFailed`/`Canceled`/cancelled/expired | `RELEASE` |

Same shape as the existing stock reservation
(`OrderService.java:115-124`) — follow it deliberately, including whatever it
does about abandoned orders, since credit and stock otherwise leak on exactly
the same ones. Holds need an expiry sweep; hook release into the existing
`purgeOrders` path too.

### 9.6 Two traps

- **Zero-total orders.** Balance covering the full total leaves 0.00 to
  charge. Skip `payment` entirely and have `shop` mark the order `PAID` — do
  **not** create a zero-amount payment row; Stripe cannot hold one, and
  `/sync` errors on a null provider payment id
  (`PaymentService.java:288-291`).
- **Remainders below the provider minimum.** 50.00 with 49.80 applied leaves
  0.20 CHF, under Stripe's ~0.50 minimum: the intent is rejected and the order
  sticks. Clamp the applied amount so the remainder is either zero or at least
  the minimum, and show the clamped figure.

Because the balance is refund-funded, spendable in-shop, and withdrawable only
to the original method for at most the original amount, it is a delayed refund
rather than stored value — a materially smaller regulatory surface than a
purchasable currency would have been.

### 9.7 Build order

1. `wallet` service + lots, credited by refunds, displayed in the account UI.
   No spending, no withdrawal.
2. Withdrawal per lot (§9.4) — reuses the existing `Refund`/`executeRefund`
   machinery.
3. Spending as an order discount (§9.2, §9.5, §9.6) — the largest step, and
   the one with the real concurrency design in it.

## 10. Rollout

0. ~~**Drop `clientSecret` from the `PaymentIntentCreated` payload** (§6).~~
   **Done 2026-08-01**, together with the minor-unit helper. `Payment` keeps
   its `client_secret` column and `GET /api/payments/intent/{orderId}` still
   serves it — only the Kafka payload lost the field. Verified no consumer read
   it: `shop`'s `EventConsumer` branches on `paymentId`, `reason` and
   `stripePaymentIntentId`, never `clientSecret`.
1. ~~**Backend seam, no behaviour change.**~~ **Done 2026-08-01.** Port +
   value types + registry in `provider/`, `StripePaymentProvider` in
   `provider/stripe/`; `PaymentService`, `WebhookHandler` and `HealthHandler`
   all routed through them, with the adapter resolved from `Payment.provider`
   rather than config. Webhook path → `/webhook/{provider}` **with the
   `PaymentSec` matcher widened to `/**`**. `/actuator/health/stripe` →
   `/actuator/health/providers`. `GET /api/payments/providers` pulled forward
   from §6 (additive, read-only, and it makes the registry verifiable in the
   cluster). No DB or event-payload change — those are steps 2 and 3.
   ~~Minor-unit helper (§8)~~ — landed early in step 0; it is a behaviour
   *fix*, so a real charge amount still wants verifying in the cluster.

   Two things worth knowing about the seam as built:
   - **`PaymentProviderException`.** Adapters wrap SDK failures in it so
     callers can tell "the provider failed" from "our persistence failed".
     That distinction is load-bearing in `executeRefund`: catching everything
     would record a refund Stripe actually made as FAILED.
   - **Webhook-disabled is a 503, not a silent success.** A delivery arriving
     for a provider whose `webhook.enabled` is false is refused, because the
     signing secret may be unset and verification would be theatre.
2. ~~**Data migration** (§5).~~ **Done 2026-08-01**, `payment_attempt`
   **in** — `005-provider-neutral-schema.sql`, plus `shop`'s
   `008-add-order-currency.sql` backfilled `'USD'`. Attempts are written by the
   service, not just modelled: create and retry each open one, and status
   transitions advance the current attempt. Two decisions made during the work:
   - **`provider_payload` holds JSON, not a bare secret**, transformed in the
     same changeset so the column never carries two formats. The DTO still
     exposes a flat `clientSecret`, extracted on read — renaming that field is
     step 3, and doing it inside a migration commit would break both frontends.
   - **Advancing an attempt is best-effort.** It is an audit trail; failing a
     shopper's `/sync` because a history row would not write is the wrong
     trade. The unique index on succeeded attempts is the part that must not be
     papered over, so a violation there is logged loudly.
2b. **CHF cutover** (§8) — ~~config~~ **done 2026-08-01**, ahead of the rest of
   this plan. It landed *before* the per-row currency reads, so the follow-ups
   in §8 (case normalisation, refund reading `payment.currency` instead of
   config, `shop` order currency) are now debt rather than prerequisites, and
   the pre-cutover USD rows are already a mixed-currency table.
3. ~~**API + event contract** (§6)~~ **Done 2026-08-01.** Canonical
   `provider`/`providerPaymentId`/`providerPayload` fields added, with
   `stripePaymentIntentId`/`clientSecret`/`stripeRefundId` populated alongside
   as aliases (`CreatePaymentIntentResponse.of` fills them, and
   `CreatePaymentIntentResponseTest` pins that they stay in step). Events gained
   `provider` + `providerPaymentId`/`providerRefundId` *next to* the legacy
   keys, and `shop`'s `EventConsumer` accepts either. `POST /api/shop/orders`
   takes an optional `provider`, carried on `OrderPlaced` along with the order's
   `currency`; unknown-provider and ambiguous-provider are both 400s.

   **`shop`'s `OrderResponse.clientSecret` was never populated server-side** —
   both call sites passed `null`, and `ui-shop` merges the value in client-side
   from `GET /api/payments/intent/{orderId}`. §6's "shop
   `OrderResponse.clientSecret` → provider + providerPayload" was written on the
   assumption shop filled it. `provider`/`providerPayload` were added anyway, as
   the null-on-the-wire shape the SPA already mutates locally, so step 4 needs
   no further shop change. `currency` is populated, being shop's own.
4. ~~**Frontend widget abstraction** (§7) in `ui-shop`.~~ **Done 2026-08-01.**
   `components/payment/`: `PaymentWidget` switching on `confirmationMode`,
   `stripe/StripePaymentWidget` (the `<Elements>` wiring and publishable key) +
   `stripe/StripePaymentForm` (the old `PaymentForm`, moved),
   `RedirectPaymentWidget`, `ProviderSelector`, and a `usePaymentProviders`
   hook. **Every `@stripe/*` import in `ui-shop` now lives under
   `components/payment/stripe/`** — `Checkout.tsx` and `RetryPayment.tsx` have
   none. Both tolerate the legacy flat `clientSecret` as well as
   `providerPayload`, so a row written before migration 005 still pays.

   `RedirectPaymentWidget` is built although nothing renders it yet: it makes
   the `confirmationMode` switch total rather than a one-case stub the next
   provider has to reopen, which is the whole claim of §7. `ProviderSelector`
   renders nothing below two providers, so today it is invisible and becomes
   visible the moment a second adapter is enabled — no page changes.

   **Aliases stay up.** Dropping them needs `ui-demo` migrated or written off,
   which by decision happens after `ui-shop` is settled, and which gates step 5.
5. **Second provider** (future). Adapter + selector entry + config flag.
   Additive by construction if 1–4 are done.
6. **Docs.** Refresh `docs/stripe-integration.md` line numbers; note it now
   documents the Stripe adapter specifically.

Steps 0–2 are externally invisible and land independently. **2b is the first
visible change** — prices switch denomination — and is deliberately separated
so it can be scheduled and announced on its own.

## 11. Testing

- Existing service tests are `PaymentServiceRefundTest` and
  `PaymentServicePurgeTest` (there is no `PaymentServiceTest`).
  `PaymentServiceRefundTest` asserts on `dto.refund().stripeRefundId()` and
  builds fixtures via `setStripeRefundId`, so it changes with the rename.
- **Nothing currently covers create/retry/sync** — the bulk of what this
  refactor moves. Worth closing that gap before the refactor so the seam has
  something proving it changed no behaviour.
- New: `StripePaymentProviderTest` for the mapping logic now buried in
  `PaymentService`/`WebhookHandler`; `PaymentProviderRegistryTest` for unknown
  lookups and `enabled()` filtering. A `NoopPaymentProvider` in **test sources
  only** proves N>1 dispatch before a real second adapter exists — no dev-only
  Spring profile, so deployed config matches tested config.
- The load-bearing check is a **real checkout against Stripe test mode**:
  place, pay, `/sync`, refund, confirm `PAID` → `REIMBURSED` in shop. It is the
  only thing covering the outbox → Kafka → `shop` consumer path that §6 changes.
  **Automated as `scripts/verify-checkout.sh`** and run green (35/35) against
  the compose stack on 2026-08-01, after steps 0–4. It asserts each step's own
  claim, not just that checkout works: aliases mirroring canonical fields, the
  migrated columns, `payment_attempt` written and linked, `clientSecret` absent
  from the Kafka payload, and the charge actually settling in **chf**.

  Not yet run against the Hetzner cluster: its API server allows 6443 from a
  single IP that no longer matches, so `kubectl` cannot reach it from here.
  Nothing in this plan is blocked on that, but nothing has been verified in
  production either.

  Three bugs surfaced by running it, all pre-existing and none from this
  refactor — see §12 "Found while verifying".
- No new Testcontainers requirement.

## 12. Open questions

**Resolved 2026-08-01**

- **`ui-demo`** → deprecated aliases; refactor it after `ui-shop` is settled.
  Step 4's alias drop is gated on that, and a second provider is gated on the
  aliases being gone (§2).
- **MDL** → out of scope. Currency set is USD, EUR, RON, CHF (§1, §8).
- **Shop currency** → CHF. Note this is a cutover from the USD currently
  deployed, not a default change (§8).
- **Promotional grants** → spend-only, never withdrawable, enforced by a
  `CHECK` constraint. Only real payments are refundable, and only for the
  amount that payment actually captured (§9, §9.3, §9.4).

**Found while verifying (2026-08-01)** — all pre-existing, all fixed:

- **`shop`'s `GlobalErrorHandler` logged nothing on a 500.** Every unhandled
  exception returned "An unexpected error occurred" and left no trace at all,
  which is why the first failure of the verification run was invisible. Now
  logs at ERROR for 5xx only; 4xx stays quiet, being the API answering.
- **`compose.yaml` never set `JWT_JWK_SET_URI`** for `shop`, `payment`,
  `delivery`, `profile` or `greetings` — only for `storage`. They fell back to
  `localhost:9090`, which inside a container is the service itself, so *every
  authenticated request failed in compose* with "Could not obtain the keys".
  k8s always set it (`k8s/base/config.yaml:96`), so only local dev was broken.
- **The Stripe health check reported the wrong mode.** It inferred `live` from
  the mere existence of a PaymentIntent, so a test account read `live` as soon
  as it had one charge — a health endpoint claiming live while taking test
  money. Now read from the API key prefix, which is what decides it.

**Still open**

- ~~**`payment_attempt` in steps 1–4, or later?**~~ **Resolved: in, landed in
  step 2** on 2026-08-01. Original reasoning kept below.

  *Recommendation was: in, at step 2.* The decided balance rule — refundable
  only for what was actually paid — makes "what did this order actually
  capture, and through which provider" a load-bearing fact rather than an
  audit nicety, and `balance_lot.source_payment_id`/`provider` want to point at
  an attempt. Landing it later means a second migration, a second pass over
  `PaymentService`, and re-pointing wallet rows that already exist. Cost now is
  one backfilled row per payment. If it goes out, retry stays same-provider and
  §9 cannot start until it lands.
- **§9.0: lot currency, or refuse to credit pre-cutover USD refunds?** Blocks
  §9.7 step 1, because `balance_lot` cannot gain a currency column cleanly
  after rows exist.
- **Hold-expiry window** for reserved credit (§9.5), and whether it matches
  whatever `purgeOrders` already does to stock reservations.
- **Exact provider refund windows** for `withdrawable_until` — factual, Stripe
  is ~180 days but verify before hardcoding.
- **Provider ordering/default** on the selector once a second exists —
  deferred to step 5.
