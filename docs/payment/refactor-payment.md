# Multi-provider payments — refactor plan

Status: **proposed, not started**
Author: design note, 2026-07-29 (revised same day after a code audit — §2, §4.1,
§4.4b, §4.5b, §4.5c, §4.8, §4.9 and §8 are from that pass)

## 1. Why

`payment` and `ui-shop` are Stripe-specific end to end today, not "Stripe by
default." `PaymentService` calls `com.stripe.model.PaymentIntent` /
`com.stripe.model.Refund` statics directly, `WebhookHandler` verifies
`Stripe-Signature` and deserializes Stripe's `Event` type, and the frontend
checkout flow is built around Stripe Elements' specific transaction model
(`clientSecret` + `<PaymentElement>` + `stripe.confirmPayment()`).

Three goals, decided:

1. **Real multi-provider support, not just swappable.** The shopper picks a
   payment provider at checkout (e.g. Stripe or PayPal) when more than one is
   enabled. This is a bigger scope than a config-time provider swap: an order
   carries which provider it used, checkout needs a provider-selection step,
   and refund/webhook routing must dispatch per-provider — not assume "the
   one configured provider."
2. **Webhooks stay optional, per provider.** Confirmed: no webhook has ever
   been registered in the Stripe dashboard for this deployment — `/sync` is
   and always has been the only mechanism actually confirming payments in
   production. That's not being "fixed" by forcing webhook registration;
   it's being made an explicit, per-provider on/off switch. A provider can be
   run with webhooks enabled (if you've registered the endpoint) or without
   (poll/sync only) — both are first-class, not one "correct" and one
   "fallback for local dev."
3. **A first-party currency is a stated future goal.** Not scoped here, but
   it is the single biggest constraint on the port's shape, because an
   internal ledger is not an external PSP: it settles synchronously, has no
   webhook, no client-side widget, and can fail for a *business* reason
   (insufficient balance) rather than a provider reason. Designing the port
   only against Stripe-and-PayPal-shaped providers would produce an
   abstraction the ledger has to fight. §8 states the constraints it imposes
   on steps 1–4 so those steps don't have to be redone.

`payment.provider` / `payment.provider_payment_id` already exist as columns
on `Payment` (`payment/.../domain/Payment.java:31-34`) but are populated with
the hardcoded literal `"stripe"` (`PaymentService.doCreatePaymentIntent`,
`new Payment(orderId, total, cur, "stripe")`) and shadowed by a redundant
`stripe_payment_intent_id` column that's the one actually used. The schema
already anticipated this; nothing behind it ever implemented it.

Related reading: [`docs/stripe-integration.md`](../stripe-integration.md)
explains today's webhook-vs-sync confirmation flow. Its core claim — the
webhook was never registered, `/sync` does all the confirmation work in
production — is **confirmed accurate**, not stale. Its line-number citations
*are* stale (code has moved since it was written: `confirmPayment` is now in
`PaymentForm.tsx`, not `Checkout.tsx`; `syncPaymentStatus` moved within
`PaymentService.java`) and it predates the refund-reconciliation logic. Worth
a refresh pass, independent of this refactor.

## 2. Current state — coupling inventory

**Backend (`payment` service)**

| File | Coupling |
|---|---|
| `service/PaymentService.java` | Imports `com.stripe.model.PaymentIntent`, `com.stripe.exception.StripeException`, `com.stripe.param.*` directly; calls `PaymentIntent.create()`, `PaymentIntent.retrieve()`, `com.stripe.model.Refund.create()`/`.retrieve()` inline in business logic. No interface between this class and the Stripe SDK. Provider is hardcoded to the literal `"stripe"` at payment creation. |
| `handler/WebhookHandler.java` | Verifies `Stripe-Signature` via `com.stripe.net.Webhook.constructEvent(...)`; deserializes Stripe's `Event`/`PaymentIntent` types; single route, no notion of "which provider is this webhook for." |
| `domain/Payment.java` | Has generic `provider` / `provider_payment_id` columns *and* a redundant Stripe-specific `stripe_payment_intent_id` column that's the one actually used. |
| `domain/StripeEvent.java`, `repository/StripeEventRepository.java` | Webhook dedupe table keyed by Stripe's event id/shape. |
| `dto/CreatePaymentIntentResponse.java` | Fields named `stripePaymentIntentId`, `RefundInfo.stripeRefundId` — Stripe vocabulary leaks into the public API contract. |
| `config/StripeConfig.java` | Stripe API key wiring — fine to keep, becomes one provider's config block among several. |
| `handler/HealthHandler.java` | `GET /actuator/health/stripe` calls `PaymentIntent.list(...)` to probe connectivity. A provider-specific liveness probe living on a generic path. |
| `route/PaymentRoute.java` | `POST /api/payments/webhook` — single un-scoped webhook path. |
| `security/PaymentSec.java` | `.pathMatchers("/api/payments/webhook").permitAll()` is an **exact** match, not a prefix. Moving the route to `/webhook/{provider}` without changing this to `/webhook/**` silently makes every webhook 401 — the adapter would never even be reached. |
| `service/PaymentService.java` (amounts) | `total.multiply(BigDecimal.valueOf(100)).longValue()` appears in three places. Two-decimal minor units are a *Stripe-and-most-card-networks* convention, not a universal one, and they are wrong for a first-party token. See §4.8. |
| `service/PaymentService.java` (currency) | `@Value("${stripe.currency:usd}")` — the service's default currency is a Stripe-namespaced global. `shop` has no currency concept at all; prices are bare `BigDecimal`. |
| Outbox event payloads | `PaymentIntentCreated` / `PaymentSucceeded` / `PaymentRefunded` carry `stripePaymentIntentId`, `stripeRefundId`, and **`clientSecret`** as JSON fields on `payments.events`. This is a *cross-service* contract, not an internal detail — see §4.5b. |

**Other services**

| File | Coupling |
|---|---|
| `shop/.../consumer/EventConsumer.java:62` | Identifies a `PaymentIntentCreated` event by `data.containsKey("stripePaymentIntentId")`. Renaming the event field makes this branch fall through to `log.warn("Unknown payment event type")`. Harmless in effect (the branch only logs) but it is a live coupling and must be changed in the same step. |
| `shop/.../dto/OrderResponse.java` | `clientSecret` field, `@Schema(description = "Stripe client secret for payment")`. |
| `shop/.../service/OrderService.java` | `buildOrderResponse(..., String clientSecret)` / `toOrderResponse(..., clientSecret)` — the Stripe vocabulary is in shop's method signatures. |

**Frontend (`ui-shop`)**

| File | Coupling |
|---|---|
| `components/PaymentForm.tsx` | `useStripe`/`useElements`/`<PaymentElement>` from `@stripe/react-stripe-js`; calls `stripe.confirmPayment()` — Stripe's specific client-side confirmation flow. |
| `pages/Checkout.tsx`, `pages/RetryPayment.tsx` | `loadStripe(...)`, wraps the payment step in `<Elements stripe={stripePromise} options={{ clientSecret }}>`. No concept of "which provider" — Stripe is assumed. No provider-selection step exists. |
| `types.ts` | `OrderResponse.clientSecret`, `CreatePaymentIntentResponse.stripePaymentIntentId`, `RefundInfo.stripeRefundId` — Stripe-specific field names in the shared type contract. |

**Frontend (`ui-demo`) — omitted from the original draft, and it is deployed**

`ui-demo` is not a dead demo: `k8s/hetzner/app-chocolate` serves it alone, and
`app-multi` (the current default overlay) serves it alongside `ui-shop`. It
carries the *same* Stripe coupling in `src/types.ts` (`clientSecret`,
`stripePaymentIntentId`) and `src/pages/CheckoutPage.tsx` (`loadStripe`,
`<Elements options={{ clientSecret }}>`, the `clientSecret` polling loop at
lines 93–139, 206, 295).

Because §4.5's DTO changes are breaking, **`ui-demo` breaks the moment step 3
lands** unless it is migrated too. Decide explicitly, and record the choice
here rather than discovering it at deploy time:

- **(a) Migrate both.** Roughly doubles the frontend work in step 4.
- **(b) Keep the old field names as deprecated aliases** on the response DTO
  for one release (`clientSecret` *and* `providerPayload` both populated) so
  `ui-demo` keeps working untouched, then drop them. Cheapest; recommended.
- **(c) Accept `ui-demo` checkout breaking** and fix it later — only viable if
  `app-chocolate`/`app-multi` are not live.

Option (b) also de-risks step 3 for `ui-shop`: the API change stops being
lockstep-or-outage, which is what forces §5's steps 3 and 4 to ship together.

Different providers have fundamentally different client-side integration
models (Stripe Elements' embedded widget + `clientSecret` vs. PayPal's
redirect/Smart-Buttons vs. others), so this is UI work, not just a different
SDK import.

## 3. High-level target architecture

```
                    ┌─────────────────────────────────────────────┐
                    │                payment service                │
                    │                                               │
 OrderPlaced(+chosen│  PaymentService ──uses──►  PaymentProviderRegistry
 provider) / Refund │        │                         │             │
 Requested (Kafka) ─┼────────┘                         ├─► StripePaymentProvider
                    │  Payment (provider,               ├─► PayPalPaymentProvider (future)
                    │   provider_payment_id)             └─► ...                        │
                    │                                                                    │
                    │  WebhookHandler                                                    │
                    │   /api/payments/webhook/{provider} ──dispatches to──► registry.get(provider)
                    │                                                                    │
                    │  GET /api/payments/providers  ──► [{id, displayName, webhookEnabled}, ...]
                    └─────────────────────────────────────────────┘
                                        │
                                        ▼
                     outbox → PaymentIntentCreated / PaymentSucceeded /
                     PaymentRefunded  (provider-neutral event payloads,
                     `provider` field carried through)


  Browser (ui-shop)
    Checkout
       │
       ▼
   1. Provider selection step (only shown when >1 enabled provider)
       │
       ▼
   2. Place order with chosen `provider`
       │
       ▼
   3. PaymentWidget (port) ──renders based on provider──► StripePaymentWidget
                                                        └► PayPalPaymentWidget (future)
       │
       ▼
   4. onConfirmed → POST /sync (provider-agnostic; every provider must
      support a pull-based reconciliation path — see §4.5) or, if that
      provider's webhook is enabled, the webhook may beat /sync to it;
      both write the same status transition, so either order is safe.
```

Key structural moves:

1. **`PaymentProvider` port + registry**, not a single injected bean. Spring
   collects `List<PaymentProvider>` and the registry keys them by
   `provider.name()`. `PaymentService`/`WebhookHandler` resolve which
   provider to use per-order from `Payment.provider`, never from static
   config.
2. **`provider` becomes a real per-order choice**, not a hardcoded literal.
   Order placement (or payment-intent creation) accepts a `provider` field;
   it's validated against the set of currently enabled providers.
3. **Webhook route is provider-scoped**: `/api/payments/webhook/{provider}`.
   Each adapter verifies its own signature scheme independently. A provider
   with webhooks disabled (not registered on the provider's dashboard, or
   deliberately turned off) simply never receives calls at that path — `/sync`
   still works regardless, because `/sync` is a *required* capability every
   adapter must implement, while webhook support is optional per adapter.
4. **Provider-neutral persistence and API contract**, as before:
   `stripe_payment_intent_id` → `provider_payment_id`;
   `stripePaymentIntentId`/`stripeRefundId` DTO fields → `providerPaymentId`/
   `providerRefundId`; `clientSecret` → opaque `providerPayload`.
5. **Frontend gets a provider-selection step and a `PaymentWidget` switch**,
   choosing among whichever providers `GET /api/payments/providers` reports
   as enabled.

## 4. Low-level design

### 4.1 `PaymentProvider` interface (new, `payment/.../provider/`)

```java
public interface PaymentProvider {

    String name();              // "stripe", "paypal", "granite-credit", ...
    String displayName();       // "Stripe", "PayPal" — for the checkout selector
    boolean webhookEnabled();   // from config; false until registered on the provider's side

    /** How the shopper completes payment. Drives which widget the frontend renders. */
    ConfirmationMode confirmationMode();   // CLIENT_SDK | REDIRECT | SERVER_SIDE

    /** Currencies this adapter can charge in. Used to filter the selector — see §4.9. */
    Set<String> supportedCurrencies();

    Mono<ProviderIntent> createIntent(CreateIntentRequest request);

    /**
     * Abandon the current attempt and start a fresh one for the same order.
     * Stripe creates a new PaymentIntent; a redirect provider a new
     * approval link; the internal ledger simply re-checks the balance.
     */
    Mono<ProviderIntent> recreateIntent(CreateIntentRequest request, String previousProviderPaymentId);

    Mono<ProviderIntent> retrieveIntent(String providerPaymentId);  // backs /sync — REQUIRED

    Mono<ProviderRefund> createRefund(String providerPaymentId, Money amount, String idempotencyKey);

    Mono<ProviderRefund> retrieveRefund(String providerRefundId);

    /** Only called when webhookEnabled() is true for this provider. */
    ProviderWebhookEvent parseWebhook(String payload, Map<String, String> headers) throws WebhookVerificationException;
}
```

Four things the first draft's signature could not express, each of which
would have forced a port change at step 5 — the exact cost the seam exists to
avoid:

1. **`recreateIntent` / retry.** `PaymentService.retryPaymentIntent` builds a
   brand-new `PaymentIntent` on the existing `Payment` row today
   (`PaymentService.java:239-282`), with a `payment-retry-<order>-<uuid>`
   idempotency key. It is a distinct provider operation from `createIntent`
   (different idempotency semantics, and some providers must void the prior
   attempt first), and it was missing entirely.
2. **Idempotency on creation.** `createRefund` took an `idempotencyKey` but
   `createIntent` did not — yet creation is where today's idempotency lives
   (`payment-order-`/`payment-order-async-` prefixes) *and* where the gnarliest
   Stripe-specific recovery lives: on `IdempotencyException`, the code falls
   back to `PaymentIntent.search("metadata['order_id']:'...'")`
   (`PaymentService.java:467-478`). That fallback is pure Stripe and **must
   move inside the adapter** — `CreateIntentRequest` carries an
   `idempotencyKey` and an `orderId`, and how the adapter recovers a collided
   key is its own business.
3. **`confirmationMode`.** `CLIENT_SDK` (Stripe Elements: hand the client an
   opaque payload, it confirms in-browser), `REDIRECT` (PayPal: send the
   browser to an approval URL, get it back on a return URL), `SERVER_SIDE`
   (first-party ledger: nothing to confirm client-side; the intent is already
   settled or already declined). The frontend switch in §4.6 keys off *this*,
   not off a hardcoded list of provider ids — which is what makes adding a
   provider genuinely additive on the frontend too.
4. **Redirect providers need URLs.** `CreateIntentRequest` therefore carries
   `returnUrl` / `cancelUrl`, and `ProviderIntent` may carry a
   `redirectUrl`. Without these, adding PayPal means changing the port and
   every adapter. There is a matching route —
   `GET /api/payments/return/{provider}` — that resolves the intent and
   redirects the browser onward; for `CLIENT_SDK` and `SERVER_SIDE` providers
   nothing ever calls it.

Supporting value types (`ProviderIntent`, `ProviderRefund`,
`ProviderWebhookEvent`, `CreateIntentRequest`, `Money`) are plain records
carrying only what `PaymentService` needs — id, status (mapped to the existing
`PaymentStatus`/`RefundStatus` enums), a `providerPayload` for the client,
amount, currency, redirect URLs. No Stripe (or PayPal, etc.) SDK types cross
this boundary. Add one more, easy to forget:
`ProviderIntent.declineReason` — an internal-ledger `SERVER_SIDE` provider
fails for reasons (`INSUFFICIENT_FUNDS`) that are not exceptions and that the
shopper must be shown.

`StripePaymentProvider` wraps today's `PaymentIntentCreateParams` /
`RefundCreateParams` / `Webhook.constructEvent` calls and today's
`mapStripeStatus`/`mapStripeRefundStatus` mapping logic — that logic moves
into the adapter, it doesn't get rewritten.

### 4.2 `PaymentProviderRegistry`

```java
@Component
public class PaymentProviderRegistry {
    private final Map<String, PaymentProvider> byName;

    public PaymentProviderRegistry(List<PaymentProvider> providers) {
        this.byName = providers.stream().collect(toMap(PaymentProvider::name, p -> p));
    }

    public PaymentProvider get(String name) { ... } // 400/404 if unknown or disabled
    public List<PaymentProvider> enabled() { ... }   // for GET /api/payments/providers
}
```

Enabling/disabling a provider is a Spring config flag
(`@ConditionalOnProperty(payment.providers.stripe.enabled)`), so an
implemented-but-currently-off provider is possible (e.g. mid-rollout).

### 4.3 `PaymentService` / `WebhookHandler` changes

- `PaymentService` resolves `PaymentProvider` via
  `registry.get(payment.getProvider())` instead of calling Stripe statics.
  Order/payment creation takes a `provider` argument (validated against
  `registry.enabled()`) instead of the hardcoded `"stripe"` literal.
- `WebhookHandler`'s route becomes
  `POST /api/payments/webhook/{provider}`; it resolves the adapter via the
  registry, 404s if unknown, and returns a clear error if
  `webhookEnabled()` is false for that provider (someone posting to a
  webhook path for a provider that hasn't got one configured is a
  misconfiguration worth surfacing, not silently ignoring).
- `StripeEvent`/`StripeEventRepository` → `ProviderEvent`/`ProviderEventRepository`,
  dedupe key becomes `(provider, event_id)`.

### 4.4 Data model migration

New Liquibase changelog in `payment`:

```sql
UPDATE payment SET provider_payment_id = stripe_payment_intent_id
  WHERE provider_payment_id IS NULL AND stripe_payment_intent_id IS NOT NULL;
ALTER TABLE payment DROP COLUMN stripe_payment_intent_id;

-- MISSING from the first draft: client_secret is just as Stripe-shaped as
-- stripe_payment_intent_id (003-add-stripe-schema.sql:11-13). It becomes the
-- generic opaque payload the client needs, whatever its shape per provider.
ALTER TABLE payment RENAME COLUMN client_secret TO provider_payload;
ALTER TABLE payment ALTER COLUMN provider_payload TYPE TEXT;  -- was VARCHAR(255); a JSON payload will not fit

ALTER TABLE refund RENAME COLUMN stripe_refund_id TO provider_refund_id;

ALTER TABLE stripe_event RENAME TO provider_event;
ALTER TABLE provider_event ADD COLUMN provider VARCHAR(32) NOT NULL DEFAULT 'stripe';
```

Note the existing changelogs are written with Liquibase preconditions and
`--rollback` lines (`003-add-stripe-schema.sql`) — match that style; the
renames need explicit rollbacks or the changelog is one-way.

`payment.provider` is already populated (`"stripe"` for every existing row)
— no backfill needed there, only going forward it stops being a literal and
starts being the shopper's actual choice.

**`provider_payload` should be nullable and is not always needed.** A
`SERVER_SIDE` provider has nothing to hand the client. Don't let the column
become implicitly-required the way `client_secret` effectively is today
(`RetryPayment.tsx:28` errors out when it is absent).

### 4.4b One payment, many attempts

This is the structural gap in the first draft, and it is the one that shows
up the day a second provider goes live.

`Payment` is one row per order holding *one* `provider` +
`provider_payment_id`. But the most valuable multi-provider behaviour is
precisely: **card declined at Stripe → shopper retries with PayPal.** That
overwrites `provider` and `provider_payment_id` in place, and the failed
Stripe attempt vanishes — no audit trail, and `/sync` can no longer reconcile
the abandoned intent it no longer knows about. Reconciling money you have no
record of attempting is not a problem you want.

```sql
CREATE TABLE payment_attempt (
    id                  UUID PRIMARY KEY,
    payment_id          UUID NOT NULL REFERENCES payment(id),
    order_id            BIGINT NOT NULL,
    provider            VARCHAR(32) NOT NULL,
    provider_payment_id VARCHAR(128),
    provider_payload    TEXT,
    status              VARCHAR(32) NOT NULL,
    decline_reason      VARCHAR(64),
    created_at          TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_payment_attempt_order ON payment_attempt(order_id);
-- At most one attempt may reach SUCCEEDED per order: double-charge guard.
CREATE UNIQUE INDEX idx_payment_attempt_one_success
    ON payment_attempt(order_id) WHERE status = 'SUCCEEDED';
```

`Payment` keeps `order_id`, `amount`, `currency`, `status` and gains
`current_attempt_id`; `provider` / `provider_payment_id` / `provider_payload`
become properties of the *attempt*. Backfill is one row per existing payment.
That partial unique index is worth more than it costs: it is a database-level
guarantee against the failure mode multi-provider introduces — two providers
each believing they own the same order.

Whether this lands in steps 1–4 or is deferred is a real choice, but **decide
it now**: deferring it means a second schema migration and a second pass over
`PaymentService` at step 5, and it means shipping a retry flow that silently
loses attempt history in the meantime. If deferred, at minimum forbid
switching providers on retry until it exists.

### 4.5 API contract changes

New endpoint:

```
GET /api/payments/providers  (public)
→ [{ "id": "stripe", "displayName": "Stripe", "webhookEnabled": false }, ...]
```

`ui-shop` calls this to render the provider-selection step — and to skip it
entirely (auto-select) when only one provider is enabled, which is today's
default state and stays a one-provider deployment's exact current behavior.

`CreatePaymentIntentResponse`:

```diff
- String stripePaymentIntentId;
+ String provider;
+ String providerPaymentId;
- String clientSecret;
+ JsonNode providerPayload;   // { "clientSecret": "pi_..._secret_..." } for Stripe
```

`RefundInfo.stripeRefundId` → `RefundInfo.providerRefundId`.

`OrderResponse.clientSecret` (shop, relayed from payment) →
`OrderResponse.provider` + `OrderResponse.providerPayload`.

Order placement (`POST /api/shop/orders`) gains an optional `provider` field;
shop passes it through to payment when creating the intent. Missing/invalid
provider when multiple are enabled is a 400 — the frontend must always send
one once there's a real choice.

This is a breaking API change — see rollout plan (§5) for sequencing.

### 4.5b Kafka event payloads (`payments.events`) — a second, separate contract

The first draft mentioned "provider-neutral event payloads" in the §3 diagram
but never specified them, and they are not an internal detail: `shop` consumes
this topic.

```diff
  PaymentIntentCreated / PaymentSucceeded / PaymentFailed / PaymentCanceled:
- "stripePaymentIntentId": "pi_..."
+ "provider": "stripe", "providerPaymentId": "pi_..."
- "clientSecret": "pi_..._secret_..."      // PaymentIntentCreated only — see below
  PaymentRefunded:
- "stripeRefundId": "re_..."
+ "provider": "stripe", "providerRefundId": "re_..."
```

Two things this forces:

**`shop` must change in the same step.** `EventConsumer.java:62` detects
`PaymentIntentCreated` by `data.containsKey("stripePaymentIntentId")`. After
the rename that branch stops matching and the event logs as
`"Unknown payment event type"`. The branch only logs today, so nothing breaks
functionally — but it must be updated to key on `eventType`/`providerPaymentId`
rather than left to rot. Note the consumer already carries `paymentId`/`reason`
legacy branches; add the new key alongside rather than swapping it, so
in-flight messages on the topic during rollout still match.

**Stop publishing the client secret to Kafka.** `PaymentIntentCreated`
currently puts `clientSecret` on the topic (`PaymentService.java:425`). That
is a bearer credential for confirming a payment, sitting in a Kafka topic, and
no consumer uses it — `ui-shop` and `ui-demo` both fetch it over HTTP from
`GET /api/payments/intent/{orderId}`. Renaming it to `providerPayload` makes it
worse, because the payload is opaque and grows per provider. Drop the field.
This is the same reasoning already applied to `identity.events` and kafka-ui
in `CLAUDE.md` — the topic is not a place for credentials, and `kafka-ui` is
deployed in-cluster with write access.

That deletion is independent of everything else here and can land first, on
its own, as a small security fix.

### 4.5c Gateway and resource-server config

Neither was mentioned, and both block the new routes:

- **`PaymentSec.java`**: `.pathMatchers("/api/payments/webhook")` is an exact
  match. It must become `/api/payments/webhook/**`, or every provider-scoped
  webhook returns 401 before reaching the adapter. Same for the new
  `/api/payments/return/**` (redirect providers) and
  `/api/payments/providers` (public).
- **`gateway/RouterConfig.java`**: `/api/payments/**` already routes to the
  payment service as one block, so no new route is needed — but confirm in
  `GateSec` that `/api/payments/providers` does not require an OAuth2 session.
  It is called on the checkout page, which is behind auth today, so this may
  be moot; verify rather than assume.
- **`HealthHandler`**: `GET /actuator/health/stripe` becomes
  `/actuator/health/providers`, iterating `registry.enabled()` and asking each
  adapter for its own probe (add `Mono<ProviderHealth> health()` to the port).
  Check whether any k8s probe or runbook references the old path before
  removing it.

### 4.6 Frontend

**Provider selection** (new step in `Checkout.tsx`, only rendered when
`GET /api/payments/providers` returns more than one enabled provider):

```tsx
<ProviderSelector
  providers={enabledProviders}
  selected={selectedProvider}
  onSelect={setSelectedProvider}
/>
```

`handlePlaceOrder` sends `provider: selectedProvider` (or the single enabled
provider's id, auto-selected, when there's only one).

**Widget dispatch**:

```tsx
// components/payment/PaymentWidget.tsx
export function PaymentWidget({ provider, confirmationMode, payload, orderId,
                                onPaymentConfirmed, onError }) {
  switch (confirmationMode) {
    case 'CLIENT_SDK':
      // provider-keyed only within this arm — each SDK is genuinely different
      if (provider === 'stripe')
        return <StripePaymentWidget payload={payload} orderId={orderId}
                 onPaymentConfirmed={onPaymentConfirmed} onError={onError} />;
      break;
    case 'REDIRECT':
      // generic: every redirect provider is "show a button, leave, come back"
      return <RedirectPaymentWidget redirectUrl={payload.redirectUrl} orderId={orderId} />;
    case 'SERVER_SIDE':
      // generic: nothing to confirm in-browser; poll /sync for the outcome
      return <ServerSideConfirmation orderId={orderId}
               onPaymentConfirmed={onPaymentConfirmed} onError={onError} />;
  }
  throw new Error(`Unsupported payment provider: ${provider}`);
}
```

Switching on `confirmationMode` rather than on `provider` is what makes the
frontend additive too: a second *redirect* provider (and most non-card
providers are redirect-shaped) needs **no new component at all** — only a new
entry in the selector. The first draft's provider-keyed switch would have
required a new widget per provider forever.

`StripePaymentWidget` is today's `Checkout.tsx` `<Elements>` wiring +
`PaymentForm.tsx`, moved under `components/payment/stripe/`, reading
`payload.clientSecret`. `Checkout.tsx`/`RetryPayment.tsx` render
`<PaymentWidget provider={order.provider} payload={order.providerPayload} .../>`
and drop their `@stripe/*` imports entirely — those live only inside
`stripe/StripePaymentWidget.tsx`.

`api.payments.syncPaymentIntent` stays provider-agnostic (it just asks the
backend to reconcile) and needs no change — this is what makes `/sync` the
one path every provider must support regardless of webhook status.

### 4.7 Config

```yaml
payment:
  providers:
    stripe:
      enabled: true
      webhook:
        enabled: false   # true only once registered in the Stripe dashboard
    paypal:
      enabled: false     # scaffolded, off until implemented/configured
```

Each provider's own secrets (`STRIPE_SECRET_KEY`, a future
`PAYPAL_CLIENT_SECRET`, etc.) stay scoped to that provider's config class,
same pattern as `StripeConfig` today.

Note `stripe.currency` (today's `@Value("${stripe.currency:usd}")` in
`PaymentService`) is **not** a Stripe setting — it is the platform's default
pricing currency and belongs at `payment.default-currency`. Leaving it
Stripe-namespaced while claiming provider neutrality is exactly the kind of
leftover that makes the next person assume Stripe is still special.

Also add the k8s/compose plumbing to the checklist: `STRIPE_SECRET_KEY` and
`STRIPE_WEBHOOK_SECRET` are wired in `compose.yaml` and the Hetzner
`secrets-patch.yaml.example` files (all three overlays). Any renamed or added
env var needs the same treatment in all of them, and the `.example` files are
checked in — update them, not just the live secrets.

### 4.8 Money and minor units — the constraint the plan didn't state

`amountCents = total.multiply(BigDecimal.valueOf(100)).longValue()` appears
three times in `PaymentService` (creation, retry, refund). Two decimal places
is a card-network convention. It is already wrong for JPY/KRW (zero decimals)
and it will be wrong for a first-party token, which may want 8 decimals or
integer-only units.

**Minor-unit conversion must move inside the adapter.** The port speaks
`Money(BigDecimal amount, String currency)`; `StripePaymentProvider` decides
that USD means ×100 and JPY means ×1. `PaymentService` never sees "cents"
again.

Two details worth fixing while touching it:

- `.longValue()` **truncates**. `BigDecimal("10.999")` becomes 1099, silently.
  Use `movePointRight(exponent).setScale(0, RoundingMode.HALF_EVEN)`, and
  reject amounts whose scale exceeds the currency's — a rounding difference
  between what shop charged and what the provider captured is a reconciliation
  bug that surfaces weeks later.
- The refund path recomputes the amount from `payment.getAmount()` rather than
  reusing what was charged. Once attempts exist (§4.4b), refund against the
  succeeded *attempt's* captured amount.

This is a prerequisite for the ledger, not a nicety: an internal currency with
non-2-decimal precision cannot be represented at all until this is done.

### 4.9 Currency is a second axis, and it is not orthogonal

`enabled()` as a flat boolean assumes every enabled provider can charge every
order. Once there is a first-party currency this is plainly false: the ledger
provider handles `GRC` and nothing else; Stripe handles fiat and never `GRC`.

So the selector query is not "which providers are on" but "which providers can
charge *this order*":

```
GET /api/payments/providers?currency=EUR
→ [{ id, displayName, confirmationMode, webhookEnabled }, ...]
```

backed by `registry.enabledFor(currency)` filtering on
`supportedCurrencies()`. This costs one method and one query param now; adding
it later means changing the endpoint contract and the frontend again. The
original §7 flagged per-provider currency restrictions as "out of scope" — but
the *hook* for it must exist in step 3 or step 3 gets redone.

Note `shop` has no currency concept today (order totals are bare `BigDecimal`;
`OrderResponse` has no currency field). Whatever currency an order is priced
in is implicit. That is fine while there is exactly one currency, and it is
the first thing to break when there are two — flag it for §8, not for this
refactor.

## 5. Rollout plan

0. **Drop `clientSecret` from the `PaymentIntentCreated` event payload**
   (§4.5b). Independent of everything else, no consumer reads it, removes a
   credential from a Kafka topic. Land it first and separately.
1. **Backend seam, no behavior change.** Introduce `PaymentProvider`,
   `PaymentProviderRegistry`, `StripePaymentProvider`; route
   `PaymentService`/`WebhookHandler` through them. Still exactly one enabled
   provider (`stripe`), still hardcoded at creation time. Verified by the
   existing test suite plus a manual checkout/refund/`/sync` run against
   Stripe test mode. Webhook path becomes `/api/payments/webhook/stripe`
   (update the Stripe CLI tunnel command and any dashboard registration
   instructions accordingly — none exist yet per §1, so nothing to migrate
   there today).
   Also: `PaymentSec` webhook matcher → `/api/payments/webhook/**` (§4.5c),
   and minor-unit conversion moves into the adapter (§4.8) — that one is a
   behaviour *fix*, not a no-op, so verify a real charge amount before and
   after.
2. **Data migration.** Backfill `provider_payment_id`, drop
   `stripe_payment_intent_id`, rename `client_secret` → `provider_payload`
   (widening to TEXT), rename `refund.stripe_refund_id`, rename `stripe_event`
   → `provider_event`. Decide here whether `payment_attempt` (§4.4b) is in or
   out; if out, retry must stay same-provider.
3. **API contract change.** `GET /api/payments/providers?currency=`, DTO
   renames, `providerPayload`, `confirmationMode`. With one enabled provider
   this is invisible to the shopper — the selection step doesn't render,
   `provider` auto-fills. **Ship the old field names as deprecated aliases**
   (§2, option b) so this stops being lockstep with step 4 and `ui-demo` keeps
   working. `shop`'s `EventConsumer` and `OrderResponse` change here too.
4. **Frontend widget abstraction.** Extract `PaymentWidget` keyed on
   `confirmationMode`, move Stripe specifics into `StripePaymentWidget`. Still
   Stripe-only in practice. Then drop the deprecated aliases from step 3 —
   which requires `ui-demo` to be migrated or written off first.
5. **Second provider (future, unscoped here).** When a concrete second
   provider is chosen: implement its `PaymentProvider` adapter, its
   `PaymentWidget` variant, flip `payment.providers.<name>.enabled=true`.
   Everything in steps 1–4 exists specifically so this step is additive —
   new files, no changes to `PaymentService`/`Checkout.tsx` beyond the
   registry/switch already handling N providers.
6. **Docs.** Refresh `docs/stripe-integration.md` (line numbers only — its
   webhook-never-registered claim needs no correction, confirmed in §1) and
   note it now documents the Stripe adapter specifically.

Steps 1–2 are safe to land independently and don't change externally
observable behavior. Steps 3–4 must land together (breaking API + FE in
lockstep). Step 5 is genuinely separate work, scoped only when a provider is
chosen.

## 6. Testing strategy

- The existing service tests are `PaymentServiceRefundTest` and
  `PaymentServicePurgeTest` (there is no `PaymentServiceTest`). Both are
  Mockito/StepVerifier; `PaymentServiceRefundTest` asserts on
  `dto.refund().stripeRefundId()` and builds `Refund` fixtures via
  `setStripeRefundId` — it changes with the DTO rename, it isn't just
  re-pointed at a fake provider. There is **no existing test covering
  create/retry/sync**, which is the bulk of what this refactor moves; that gap
  is worth closing *before* the refactor, so the seam has something to prove
  it didn't change behaviour.
- New `StripePaymentProviderTest` isolates the Stripe-SDK-specific mapping
  logic that today is buried inside `PaymentService`/`WebhookHandler`.
- New `PaymentProviderRegistryTest` covers unknown-provider lookups and the
  `enabled()` filtering.
- A fake `NoopPaymentProvider` (in test sources only) is worth adding early —
  it proves the registry/dispatch logic works for N > 1 provider before a
  real second adapter exists, without waiting on choosing/implementing one.
- Webhook signature verification and the Stripe CLI tunnel flow
  (`k8s/instructions.md`) are unaffected in mechanism, only in path
  (`/api/payments/webhook/stripe`).
- No new Testcontainers requirement; this is a code-shape refactor, not a new
  integration.
- The load-bearing verification here is a **real checkout against Stripe test
  mode in the cluster**, not the unit suite: place an order, pay, `/sync`,
  refund, and confirm the order reaches `PAID` then `REIMBURSED` in shop.
  That is the only check that covers the outbox → Kafka → `shop` consumer
  path that §4.5b changes. Run it after step 1 and again after step 3.
  A `NoopPaymentProvider` is still worth having, but as a `test`-source bean
  only — do not introduce a dev-only Spring profile to enable it, since that
  makes the deployed config differ from the tested one.

## 7. Open questions

- **Refund provider consistency.** A refund always uses the provider stored
  on `Payment.provider` for that order — no ambiguity, no user choice at
  refund time. Confirm this is the intended behavior (it matches how refunds
  work today; just calling it out since multi-provider makes it worth
  stating explicitly).
- **Provider list ordering/defaults on the selector.** Once a second provider
  exists, does the checkout UI need a "preferred/default" provider, or is
  first-enabled-in-config fine? Deferred to step 5 (unscoped) — not a
  blocker for the seam work in steps 1–4.
- **Per-provider currency/region restrictions.** Now partially in scope: §4.9
  adds `supportedCurrencies()` and `enabledFor(currency)` because the
  first-party currency makes the flat boolean wrong. Region/country
  restrictions remain out of scope.
- **Does `payment_attempt` (§4.4b) land in steps 1–4, or later?** The one
  genuinely open sequencing decision. Deferring costs a second migration and a
  second pass over `PaymentService`, and means retry cannot switch providers
  in the meantime.
- **`ui-demo`: migrate, alias, or write off?** (§2). Blocks step 3's
  sequencing either way.

## 8. Toward a first-party currency (not scoped — constraints only)

A "Granite credit" is not a fourth PSP adapter with a different SDK. It is a
different *kind* of thing, and the reason it appears in this document is that
three decisions in steps 1–4 either accommodate it for free or have to be
redone. Those three are §4.1's `SERVER_SIDE` confirmation mode, §4.8's money
handling, and §4.9's currency-aware provider filtering. Nothing else here
needs to be built now.

What makes it different:

- **It settles synchronously.** No webhook, ever. `createIntent` returns
  `SUCCEEDED` or a decline in the same call. This is why the port needs
  `confirmationMode` and `declineReason` rather than assuming every provider
  hands the client something to confirm.
- **It declines for business reasons.** Insufficient balance is not an
  exception or a provider outage; it is an expected outcome the shopper must
  see, with a "top up" path. Today's code models every failure as
  `StripeException`.
- **It needs a ledger, not a payments table.** Balances must be
  double-entry (`credit_account` + append-only `ledger_entry`, balance derived
  or maintained under a constraint), because a mutable `balance` column plus
  concurrent orders is how money gets created. Refunds are compensating
  entries, not calls to a remote API. Debits need to be atomic against the
  balance check — in one R2DBC transaction with a conditional update, not
  read-then-write.
- **It is a new bounded context.** Balances outlive orders, are queried from
  the profile/account UI, and are credited by things that are not orders
  (promotions, refunds-to-credit, admin grants). It should be its own service
  (a `wallet` / `credit` service, port 8065 is free) that `payment` calls
  through a thin `GraniteCreditProvider` adapter — *not* a package inside
  `payment`. Putting a ledger inside the PSP-integration service couples the
  money supply's lifecycle to Stripe's.
- **It forces `shop` to learn about currency.** Order totals are bare
  `BigDecimal` today with no currency field anywhere. An order priced in EUR
  and paid in GRC needs an exchange rate, a rate *at time of pricing* (not at
  time of payment), and a decision about who owns that rate. This is the
  largest piece of work and it is entirely outside `payment`.
- **It is financially regulated in a way Stripe integration is not.** Issuing
  a store of value that users top up with real money is stored-value/e-money
  territory in most jurisdictions. A closed-loop, non-refundable,
  earned-only-through-promotions credit generally is not. Which one it is
  changes the engineering, so decide the product shape before the schema.

Recommended sequencing when it is picked up: closed-loop **earned** credits
first (no top-up with real money, no cash-out) — that exercises the ledger,
the `SERVER_SIDE` provider path, and shop's currency handling with the least
regulatory surface, and top-up can be added later as a Stripe payment that
credits the ledger.
