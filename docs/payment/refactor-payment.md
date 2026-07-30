# Multi-provider payments — refactor plan

Status: **proposed, not started** · 2026-07-30

## 1. Goals and decided constraints

`payment` and `ui-shop` are Stripe-specific end to end, not "Stripe by
default". The goal is real multi-provider support — the shopper picks a
provider at checkout — plus a user balance in CHF that refunds land in.

| Decision | |
|---|---|
| Provider choice | Per-order, chosen by the shopper. Not a config-time swap. |
| Webhooks | **Optional per provider.** No webhook has ever been registered for this deployment; `/sync` is and always has been the only thing confirming payments in production. That becomes an explicit switch, not a bug to fix. |
| Currencies | Closed set: **USD, EUR, MDL, RON, CHF** — all two-decimal. No zero-decimal currencies, no sub-cent amounts. |
| Pricing | **One active shop currency**, CHF today. No mixed-currency carts, no FX anywhere. |
| Balance | Plain **CHF balance** per user. Fed by refunds; spendable as an order discount; optionally withdrawn back to the original payment provider. See §9. |

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
| ↳ currency | `@Value("${stripe.currency:usd}")` — the shop currency is Stripe-namespaced, and defaults to `usd` while the intended currency is CHF. |
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

`ui-demo` means the DTO rename in §6 breaks a live frontend. Cheapest fix:
**keep the old field names as deprecated aliases for one release** (populate
`clientSecret` *and* `providerPayload`), so the API change stops being
lockstep with frontend work. Otherwise migrate both, or accept the breakage.

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
- `HealthHandler`: `/actuator/health/stripe` → `/actuator/health/providers`
  iterating `registry.enabled()` and calling each adapter's `health()`. Check
  for k8s probes or runbooks referencing the old path first.

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

**Money.** ×100 is correct given the currency set, so keep it in one shared
helper rather than three inline copies. The real fix is that `.longValue()`
truncates: `BigDecimal("10.999")` silently becomes `1099`. Since sub-cent
amounts are disallowed, reject rather than round:

```java
static long toMinorUnits(BigDecimal amount, String currency) {
    if (amount.stripTrailingZeros().scale() > 2)
        throw new IllegalArgumentException("Sub-cent amount not supported: " + amount);
    return amount.movePointRight(2).longValueExact();   // exact: overflow throws
}
```

Enforce the same scale rule on product prices in `shop`. Also: the refund path
recomputes from `payment.getAmount()` rather than what was captured — once
attempts exist, refund against the succeeded attempt's amount.

**Currency.** One shop currency, validated at startup to be one of the five,
and providers validated at startup to support it — refuse to enable a provider
that cannot charge it rather than discovering it on the first order. That makes
`supportedCurrencies()` a startup check, not a runtime filter, and no
`?currency=` param is needed. **Verify Stripe supports MDL** before treating it
as a usable shop currency; the others are standard. `shop` still needs a
currency column on orders so changing the shop currency later doesn't
reinterpret history — one column, written once.

## 9. User balance (design sketch — not scoped)

**Decided.** Users have a **CHF balance**. Refunds credit it. It is spendable
as a discount on new orders, and can optionally be withdrawn back to the
provider the original payment used.

Deliberately *not* a separate currency unit: no rate, no FX, no way to buy the
balance, no top-up orders. Every payment keeps an `order_id`.

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
    source_order_id BIGINT,              -- null for promotional grants
    source_payment_id UUID,              -- which payment/attempt funded it
    provider VARCHAR(32),                -- where a withdrawal would go
    amount NUMERIC(12,2) NOT NULL,       -- original credit
    spent NUMERIC(12,2) NOT NULL DEFAULT 0,
    withdrawn NUMERIC(12,2) NOT NULL DEFAULT 0,
    withdrawable_until TIMESTAMPTZ,      -- provider refund window
    created_at TIMESTAMPTZ NOT NULL
);
```

Available balance is `Σ(amount - spent - withdrawn)`. Spending consumes lots
FIFO. Withdrawable per lot is `amount - spent - withdrawn`, which gives the
"can't refund more than was captured" guarantee for free.

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

0. **Drop `clientSecret` from the `PaymentIntentCreated` payload** (§6).
   Independent, no consumer reads it, removes a credential from a topic.
1. **Backend seam, no behaviour change.** Port, registry,
   `StripePaymentProvider`; route service/handler through them. Still one
   enabled provider. Webhook path → `/webhook/stripe` **plus the `PaymentSec`
   matcher fix**. Minor-unit helper (§8) — a behaviour *fix*, so verify a real
   charge amount before and after.
2. **Data migration** (§5). Decide `payment_attempt` in or out here; if out,
   retry stays same-provider.
3. **API + event contract** (§6), with deprecated field aliases so this isn't
   lockstep with the frontend and `ui-demo` keeps working. `shop`'s
   `EventConsumer` and `OrderResponse` change here.
4. **Frontend widget abstraction** (§7), then drop the aliases — which
   requires `ui-demo` migrated or written off.
5. **Second provider** (future). Adapter + selector entry + config flag.
   Additive by construction if 1–4 are done.
6. **Docs.** Refresh `docs/stripe-integration.md` line numbers; note it now
   documents the Stripe adapter specifically.

Steps 0–2 are externally invisible and land independently.

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
- The load-bearing check is a **real checkout in the cluster against Stripe
  test mode**: place, pay, `/sync`, refund, confirm `PAID` → `REIMBURSED` in
  shop. It is the only thing covering the outbox → Kafka → `shop` consumer
  path that §6 changes. Run it after steps 1 and 3.
- No new Testcontainers requirement.

## 12. Open questions

- **`payment_attempt` in steps 1–4, or later?** The one sequencing decision.
- **`ui-demo`: aliases, migrate, or write off?**
- **Can Stripe charge in MDL?** Factual; blocks MDL as shop currency.
- **Provider ordering/default** on the selector once a second exists —
  deferred to step 5.
- **Balance (§9):** hold-expiry window, the exact provider refund windows for
  `withdrawable_until`, and whether promotional grants (no source payment) are
  withdrawable at all — they have no provider to go back to, so presumably
  spend-only.
