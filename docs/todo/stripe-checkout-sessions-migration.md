# Migrate Stripe integration: PaymentIntent+Elements → Checkout Sessions API

**Trigger:** Stripe.js console warning seen live in `granite-security.org` checkout:

> [Stripe.js] Your Elements integration is using an older API. We recommend
> migrating to the Checkout Sessions API to reduce code, maintenance, and
> tokens (for AI agents). Additionally, you can easily turn on advanced
> features like Adaptive Pricing.
> https://docs.stripe.com/payments/payment-element/migration-ewcs

**Status:** not started. This is a plan, not a patch — no code changed yet.

---

## Why bother

Per Stripe's migration guide (fetched 2026-07-22, one-time-payment path):
Checkout Sessions is the new baseline integration — unified `PaymentElement`
instead of per-method elements, one `actions.confirm()` instead of
method-specific `confirm*Payment()` calls, and it comes with automatic tax,
shipping, discounts/promo codes, and Adaptive Pricing (currency conversion)
built in rather than hand-rolled. None of those extras are currently used in
this app, but the API surface itself is what's being deprecated — staying on
raw `PaymentIntent` + `Elements` still works today but is the path Stripe is
steering integrations away from.

---

## Current integration — every touchpoint

The flow here is **not** the typical "browser calls `/create-checkout-session`
on click" pattern the guide assumes — `PaymentIntent` creation is
event-driven, kicked off by `shop` publishing an `OrderPlaced` Kafka event,
consumed asynchronously by `payment`. That shape carries over to Checkout
Sessions basically unchanged (see "What changes" below), but it's why the
migration isn't a literal copy-paste of the guide's samples.

**Backend (`payment` service):**
- `payment/src/main/java/org/granitesecurity/payment/service/PaymentService.java:134-198`
  (`doCreatePaymentIntent`) — calls `PaymentIntent.create(...)` with
  `automatic_payment_methods.enabled=true`, `amount`, `currency`, and
  `metadata.order_id` / `metadata.username`. Called from two places:
  - `processOrderPlaced` (line 41) — fired by
    `payment/.../consumer/OrderPlacedConsumer.java:26-39` on every
    `orders.events` Kafka message. This is the real creation path in
    production.
  - `createPaymentIntent` (line 50) — a synchronous fallback if the frontend
    asks before the Kafka-driven one has landed (idempotent on `orderId` via
    `paymentRepository.findByOrderId`).
- `payment/.../service/PaymentService.java:65-76` (`syncPaymentStatus`) —
  polls Stripe directly via `PaymentIntent.retrieve(stripePiId)`. This is
  documented as **the actual mechanism that updates order status in
  production** (`docs/stripe-integration.md`) — the webhook path exists but
  has never been reachable (see `docs/todo/improvements.md` — no, actually
  see the live investigation in this session: the registered webhook
  endpoint points at the wrong domain and is disabled).
- `payment/.../handler/WebhookHandler.java:90-155` — listens for
  `payment_intent.succeeded` / `.payment_failed` / `.canceled` only
  (`isStatusChangeEvent`, line 203).
- `payment/.../route/PaymentRoute.java:20-28` — routes:
  `POST /api/payments/intent` (sync create), `GET /api/payments/intent/{orderId}`,
  `POST /api/payments/intent/{orderId}/sync`, `POST /api/payments/webhook`.
- `payment/.../domain/Payment.java` — stores `stripe_payment_intent_id` and
  `client_secret` (a `pi_..._secret_...` value) per order.

**Frontend (`ui-shop`):**
- `ui-shop/src/pages/Checkout.tsx:1-9` — imports `loadStripe`, `Elements`,
  `PaymentElement`, `useStripe`, `useElements` from
  `@stripe/react-stripe-js` (`^6.7.0`) / `@stripe/stripe-js` (`^9.9.0`)
  (`ui-shop/package.json:13-14`).
- `Checkout.tsx:82-84` — `<Elements stripe={stripePromise} options={{clientSecret: order.clientSecret}}>`
  wraps a raw PaymentIntent client secret.
- `Checkout.tsx:36-40` (`handlePay`) — `stripe.confirmPayment({elements, confirmParams: {return_url}, redirect: 'if_required'})`.
- `Checkout.tsx:214-223` (`handlePaymentConfirmed`) — immediately calls
  `api.syncPaymentIntent(order.id)` after confirmation, then polls
  `GET /api/shop/orders/{id}` + `GET /api/payments/intent/{orderId}` until
  the order leaves `PENDING` (`pollOrderStatus`, `Checkout.tsx:143-165`).
- `ui-shop/src/types.ts:32,40` — `OrderResponse.clientSecret?` /
  `PaymentIntentResponse.clientSecret` typed as the PaymentIntent secret.

**No email is collected anywhere in the order flow** — `OrderPlaced` events
carry `orderId`, `username`, `items`, `total`, `address`
(`payment/.../consumer/OrderPlacedConsumer.java:33`), no email field. This
matters — see Gotchas below.

---

## What changes

### 1. Backend: `PaymentIntent.create` → `Session.create`

Replace `doCreatePaymentIntent`'s Stripe call
(`PaymentService.java:151-160`) with `com.stripe.param.checkout.SessionCreateParams`:

```java
var params = SessionCreateParams.builder()
    .setMode(SessionCreateParams.Mode.PAYMENT)
    .setUiMode(SessionCreateParams.UiMode.EMBEDDED)
    .setReturnUrl(returnUrlFor(orderId))   // new: needs a real return URL, see Gotchas
    .addLineItem(SessionCreateParams.LineItem.builder()
        .setQuantity(1L)
        .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
            .setCurrency(cur)
            .setUnitAmount(amountCents)
            .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                .setName("Order #" + orderId)
                .build())
            .build())
        .build())
    .setPaymentIntentData(SessionCreateParams.PaymentIntentData.builder()
        .putMetadata("order_id", orderId.toString())
        .putMetadata("username", username != null ? username : "")
        .build())
    .build();
Session session = Session.create(params, options);
```

Notes:
- Line items here are collapsed to one synthetic line ("Order #N", full
  total) rather than mapping `shop`'s per-product `items` array — the
  `OrderPlaced` payload already has per-item `productId`/`quantity`/`unitPrice`
  (`OrderPlacedConsumer.java:33`), so mapping to real line items is possible
  and arguably an improvement (itemized Stripe receipts), but is optional
  scope — flag as a stretch goal, not required for the migration itself.
- `payment_intent_data.metadata` is required to preserve `order_id`/`username`
  on the PaymentIntent Checkout Sessions creates internally — without it,
  `WebhookHandler`'s `metadata.order_id` lookup (still needed if payment_intent.*
  events are kept as a fallback) breaks.
- Idempotency handling (`RequestOptions.setIdempotencyKey`,
  `IdempotencyException` retry via `PaymentIntentSearchParams`,
  `PaymentService.java:161-171`) needs the equivalent
  `SessionListParams`/search-by-metadata swap — Stripe's session search API
  differs from PaymentIntent's; confirm it supports the same
  `metadata['order_id']:'...'` query syntax before porting this block
  verbatim.

### 2. `Payment` domain/repository: store session id alongside PI id

`Payment.java` currently has `stripe_payment_intent_id` + `client_secret`
(a PI-shaped secret, `pi_..._secret_...`). Add `stripe_checkout_session_id`
and change what's stored in `client_secret` to the **session's** client
secret (`cs_..._secret_...` shaped) — the frontend needs the session secret,
not the PI's. Requires a Liquibase changeset in `payment/src/main/resources/db/changelog/`.

### 3. Backend: `syncPaymentStatus` → Session retrieve

`PaymentService.java:65-76` currently does `PaymentIntent.retrieve(stripePiId)`
and reads `.getStatus()` (`requires_payment_method`/`processing`/`succeeded`/...).
Checkout Sessions expose `payment_status` (`unpaid`/`paid`/`no_payment_required`)
on the **Session**, separate from the underlying PaymentIntent's status. Since
`/sync` is documented as the mechanism actually driving order status updates
in production (`docs/stripe-integration.md`), get this mapping right —
decide whether to keep syncing off the PaymentIntent status (unchanged
behavior, session is just the creation vehicle) or switch to
`Session.retrieve(sessionId).getPaymentStatus()`. Recommend keeping
PaymentIntent-status-based sync unchanged for now (retrieve the PI via
`session.payment_intent`) — smaller diff, `mapStripeStatus` (line 91) stays
as-is.

### 4. Webhook: add `checkout.session.*` events

`WebhookHandler.isStatusChangeEvent` (`WebhookHandler.java:203-206`) only
matches `payment_intent.*`. Per the guide, add handling for
`checkout.session.completed`, `.async_payment_succeeded`,
`.async_payment_failed`, `.expired`. Given the webhook is currently
non-functional in production anyway (registered against the wrong domain,
disabled — see this session's earlier investigation), this is a good moment
to fix that registration too: point it at
`https://granite-security.org/api/payments/webhook` and enable the new event
types, closing the gap `docs/stripe-integration.md` already flags (browser
tab closing before `/sync` fires leaves orders stuck PENDING forever).

### 5. Frontend: swap Elements provider + confirm call

- `ui-shop/package.json` — check `@stripe/react-stripe-js`
  (currently `^6.7.0`) exports the `@stripe/react-stripe-js/checkout`
  subpackage (`CheckoutElementsProvider`, `useCheckoutElements`,
  `ContactDetailsElement`, `PaymentElement` from the checkout namespace).
  The guide's install line (`@stripe/react-stripe-js@^5.0.0
  @stripe/stripe-js@^8.0.0`) is *lower* than what's already installed here —
  confirm the checkout subpackage is present at `^6.7.0`/`^9.9.0` (likely
  yes, guide's version floor just predates this repo's last bump) before
  assuming a downgrade is needed.
- `Checkout.tsx:82-90` (`PaymentForm` + `<Elements>` wrapper) — replace with
  `<CheckoutElementsProvider stripe={stripePromise} options={{clientSecret: order.clientSecret}}>`
  wrapping a new form that uses `useCheckoutElements()` instead of
  `useStripe()`/`useElements()`.
- `Checkout.tsx:36-49` (`handlePay`) — replace
  `stripe.confirmPayment({elements, confirmParams, redirect})` with
  `checkout.confirm({redirect: 'if_required'})` from the hook's returned
  `checkout` object.
- Add a `ContactDetailsElement` (or pre-populate `customer_email` server-side)
  — see Gotchas, email is now mandatory.
- `ui-shop/src/types.ts:32,40` — rename/repurpose `clientSecret` fields to
  reflect they now hold a Checkout Session secret, not a PaymentIntent one
  (type-level only, no functional change if the field name stays).

---

## Gotchas specific to this app

1. **No email in the order flow.** Checkout Sessions require a valid customer
   email (guide §6). `OrderPlaced` events and `Payment`/`Order` domain models
   have no email field today — only `username`. Need to either: (a) add
   `ContactDetailsElement` client-side and accept the extra UI step, or
   (b) thread email through from `auth-server`'s user record (JWT `email`
   claim, if present) into the `OrderPlaced` payload and pass it as
   `customer_email` at session-creation time. Decide before starting — this
   is the one genuinely new requirement, not just an API rename.
2. **`return_url` is now required** for `ui_mode: elements` (embedded), even
   though the current flow rarely needs a redirect (cards mostly confirm
   in-place). `Checkout.tsx:39` already builds one
   (`window.location.origin + /orders/${orderId}`) for `confirmParams` — reuse
   the same URL server-side in `SessionCreateParams.setReturnUrl(...)`, but
   note it now needs to be known at **session creation** time (server-side,
   inside `OrderPlacedConsumer`'s async flow), not just at confirm time —
   confirm the domain is available there (it is — same `granite-security.org`
   config already used for OIDC issuer validation, `config-patch.yaml`).
3. **Idempotency/search-by-metadata parity** — flagged above, don't assume
   `PaymentIntentSearchParams`'s query syntax carries over 1:1 to whatever
   Session search/list API is used for the idempotency-collision fallback
   (`PaymentService.java:162-170`). Verify against Stripe's Java SDK docs
   before porting.
4. **Async, event-driven creation ≠ the guide's synchronous
   "click → fetch → mount" example.** All of the guide's code samples assume
   the session is created in direct response to a browser request. Here it's
   created by a Kafka consumer with no HTTP request in flight
   (`OrderPlacedConsumer`) — the frontend already handles this today via
   `pollForClientSecret` (`Checkout.tsx:107-131`), polling
   `GET /api/payments/intent/{orderId}` until a secret shows up. That polling
   shape doesn't need to change — just what kind of secret eventually
   appears.
5. **Existing R2DBC connection-reset issue** (`docs/todo/improvements.md`) —
   `payment` is one of the three services still missing the pool
   validation-query fix. Any DB write added by this migration (new column,
   more outbox events) inherits that same intermittent-500 risk until that's
   fixed. Not blocking, but do the pool fix first if this migration is being
   scheduled — cheaper to fix once than to debug through it twice.

---

## Suggested order of work

1. ~~Fix the R2DBC pool config for `payment`~~ — done 2026-07-22, see `docs/todo/improvements.md` (applied to `payment`, `shop`, `profile` alongside `delivery`).
2. Decide the email story (gotcha #1) — this gates the API shape more than anything else in the guide.
3. Backend: `Session.create` swap in `PaymentService.doCreatePaymentIntent`, new `stripe_checkout_session_id` column, idempotency-search parity check.
4. Frontend: `CheckoutElementsProvider` swap in `Checkout.tsx`.
5. Webhook: add `checkout.session.*` handling in `WebhookHandler`, and — separately but same trip — actually register/enable the webhook endpoint for `granite-security.org` in the Stripe Dashboard (currently pointed at an unrelated, disabled `helloworlds.space` endpoint).
6. Test end-to-end against Stripe test mode (card `4242...`, and at least one 3DS/`requires_action` case) before touching the live keys in `secrets-patch.yaml`.
7. Optional stretch: real per-product line items instead of one synthetic "Order #N" line, using `OrderPlaced`'s existing `items` array.
