# Retry Payment Button — Plan

Status: **planning only, nothing implemented yet.**

## 1. Feature recap

On the order detail page (`ui-shop/src/pages/OrderDetail.tsx`), add a "Retry
Payment" button that:
- Only appears when the order's payment status (from the **payments**
  service — source of truth per the brief) is not `SUCCEEDED`, and the order
  hasn't shipped/delivered/been cancelled.
- Matches the styling of the existing "Back to Orders" button (`className="btn"`).
- On click, starts a new payment attempt via the payments service, then drops
  the user into the existing Stripe Elements flow to re-enter card details.
- Shows a loading state while the intent is being (re)created, and a clear
  error if that call fails.
- Never marks the order/payment as paid client-side — status must only
  change to `SUCCEEDED`/`PAID` once the `payment` service independently
  confirms with Stripe and republishes the event (see §2.1 below — in this
  deployment that's the `/sync` call, not a webhook).

Also investigated: **order #13 shows `CREATED` at the top but `REFUNDED`
under Delivery** — traced to an actual bug in `delivery`'s Kafka consumer
(§3), not just "staleness". Fixing it is small and directly relevant to
making the retry button's visibility trustworthy, so it's in scope.

## 2. Current backend state (what retry has to build on)

### Payment domain (`payment/src/main/java/org/granitesecurity/payment/...`)

- One `payment` row per order — **`CREATE UNIQUE INDEX idx_payment_order_id
  ON payment(order_id)`** (`001-create-payment-table.sql`). Any retry design
  must update the existing row, not insert a new one.
- `PaymentStatus`: `CREATED, PROCESSING, SUCCEEDED, FAILED, CANCELED, REFUNDED`.
- `PaymentService.createPaymentIntent(orderId, ...)` (`route/PaymentRoute.java:23`,
  `POST /api/payments/intent`) is **idempotent by design**: if a `Payment` row
  already exists for the order, it just returns the existing row — including
  its original `clientSecret` — and never talks to Stripe again. This is
  correct for the initial order-placement flow but **wrong for retry**: if
  the existing PaymentIntent is `canceled` or `payment_failed` in Stripe, the
  old `client_secret` can no longer be confirmed, and this endpoint would
  hand the UI a dead end.
- **This deployment does not have a live Stripe webhook wired up** — the
  `WebhookHandler`/`POST /api/payments/webhook` code exists, but per
  `k8s/hetzner/sichocolate.md` ("the Stripe webhook is intentionally
  [not registered]") and `k8s/todo.md` ("payment sync endpoint if no Stripe
  webhook listener"), nothing in the current environment is configured to
  call it. So `POST /api/payments/intent/{orderId}/sync` (`syncPaymentStatus`)
  — which pulls the latest status from Stripe for the existing PaymentIntent
  and republishes `PaymentSucceeded`/`PaymentFailed`/`PaymentCanceled` via the
  outbox if it changed — **is the actual source-of-truth-update mechanism
  today, not a fallback.** `Checkout.tsx`'s `handlePaymentConfirmed` already
  calls it right after `stripe.confirmPayment()` succeeds client-side; retry
  must do the same. Concretely: `stripe.confirmPayment()` only proves the
  browser *submitted* the card successfully — the payment/order/delivery
  records only flip to `SUCCEEDED`/`PAID` once this `/sync` call runs and its
  outbox event is consumed. If a user closes the tab between
  `confirmPayment()` resolving and the `/sync` call completing, the order can
  be stuck showing an unpaid status despite Stripe having accepted the
  charge — a pre-existing gap in `Checkout.tsx` today, not something this
  plan introduces, but worth being aware of since retry doubles the number of
  places this can happen. Out of scope to fix here unless you want to expand
  scope to add a periodic/background reconciliation job that calls `/sync`
  for orders stuck in `CREATED`/`PROCESSING`.
- No existing endpoint creates a **fresh** Stripe PaymentIntent for an order
  that already has one. This needs to be added.

### Delivery's cached payment status — root cause of the #13 bug

`delivery/.../consumer/PaymentEventConsumer.java:35`:
```java
String paymentStatus = "SUCCEEDED".equals(status) ? "PAID" : "REFUNDED";
```
This consumer listens to `payments.events` for **every** message the topic
carries, including:
- `PaymentIntentCreated` (published on every initial intent creation) — its
  payload has **no `status` field at all** (`orderId, stripePaymentIntentId,
  clientSecret, amount, currency`). `status` deserializes to `null`.
- `PaymentFailed` / `PaymentCanceled` — real `status` values `FAILED` /
  `CANCELED`.

Since the ternary treats *anything other than the literal string
`"SUCCEEDED"`* as `"REFUNDED"` — including `null` — **every order's delivery
row gets flipped to `paymentStatus = REFUNDED` the moment its PaymentIntent
is created**, well before any real refund has occurred. `REFUNDED` is a
legitimate `Payment`-side status (used for actual Stripe refunds), but
nothing in this codebase ever publishes a `PaymentRefunded` event onto
`payments.events` — so `delivery` reaching `REFUNDED` here is always a
misclassification, never a real refund. This is a straightforward bug, not
just "staleness."

Fix: only react to messages that actually carry a status, and map
explicitly instead of defaulting to `REFUNDED`:
```java
String status = data.get("status") != null ? data.get("status").toString() : null;
if (status == null) {
    log.debug("Ignoring payment event without status for order {}: {}", orderId, message);
    return;
}
String paymentStatus = switch (status) {
    case "SUCCEEDED" -> "PAID";
    case "FAILED" -> "FAILED";
    case "CANCELED" -> "CANCELED";
    default -> null; // unknown/unhandled status — don't guess
};
if (paymentStatus == null) {
    log.warn("Unhandled payment status {} for order {}", status, orderId);
    return;
}
deliveryService.updatePaymentStatus(orderId, paymentStatus)...
```
`Delivery.paymentStatus` defaults to `"UNPAID"` at creation
(`Delivery.java`'s constructor), so orders correctly show `UNPAID` until a
real terminal status event arrives — no other code path needs to change.
No DB migration needed; this is consumer logic only.

### Shop's order status (for the "hasn't shipped/cancelled" check)

`OrderStatus` (`shop/.../domain/OrderStatus.java`): `PENDING, PAID, SHIPPED,
DELIVERED, PAYMENT_FAILED, CANCELLED, RETURNED, REIMBURSED`. The button
should be hidden once `order.status` is `SHIPPED`, `DELIVERED`, `CANCELLED`,
`RETURNED`, or `REIMBURSED` — i.e. only show it for `PENDING` /
`PAYMENT_FAILED`, combined with the payment-status check.

## 3. Backend change: a real "retry" endpoint

Add `POST /api/payments/intent/{orderId}/retry` in `payment`:

- `PaymentRoute.java`: `.POST("/api/payments/intent/{orderId}/retry", paymentHandler::retryPaymentIntent)`
- `PaymentHandler.retryPaymentIntent`: parses `orderId`, delegates to
  `PaymentService.retryPaymentIntent(orderId)`, maps result to
  `CreatePaymentIntentResponse` (same shape as the other two endpoints),
  `onErrorResume` → 400 with `{"error": ...}` (matching `syncPaymentStatus`'s
  existing error-handling style).
- `PaymentService.retryPaymentIntent(Long orderId)`:
  1. `paymentRepository.findByOrderId(orderId)`, 404/error if none exists
     (an order should always have a payment row by the time a user can see
     "Retry Payment", since it's created synchronously or via the
     `OrderPlaced` consumer).
  2. If `payment.getStatus().equals(SUCCEEDED.name())` → error ("payment
     already completed") — belt-and-suspenders alongside the frontend's own
     visibility check.
  3. Otherwise create a **new** Stripe `PaymentIntent` (reuse the existing
     `doCreatePaymentIntent`-style params: same amount/currency/metadata),
     with a **fresh idempotency key** per attempt (e.g.
     `"payment-retry-" + orderId + "-" + UUID.randomUUID()` — must differ
     from the original `"payment-order-" + orderId` key or Stripe will just
     hand back the original, already-dead intent).
  4. **Update the existing `Payment` row in place** (same `id`/`orderId` —
     the unique index forces this): new `stripePaymentIntentId`, new
     `clientSecret`, `status = CREATED`, bump `updatedAt`. Do not insert a
     second row.
  5. Publish a `PaymentIntentCreated` outbox event (same shape as the
     original creation path) so downstream consumers see the new intent id —
     harmless no-op for `delivery` once §3's fix lands (no `status` field →
     ignored), and lets `shop`'s `EventConsumer` log the same
     "awaiting completion" branch it already has for the original creation.

Open question worth confirming with the user before implementing: should
retry always mint a brand-new PaymentIntent, or only when the existing one
is in a genuinely dead Stripe state (`canceled`, or terminally
`payment_failed`) — reusing the existing `clientSecret` when Stripe would
still accept a confirmation on it (e.g. still `requires_payment_method`)?
Always-new is simpler and matches "either creating a fresh Stripe
PaymentIntent or re-confirming the existing one — check the code to decide
which fits" leaning toward the simpler, always-safe option; reusing would
need an extra `PaymentIntent.retrieve` call to check Stripe-side status
first. Recommend **always create fresh** for a first pass — simpler, and a
declined/canceled intent can't be reused anyway.

## 4. Frontend changes (`ui-shop`)

### API layer

`ui-shop/src/api/payments.ts`: add
```ts
retryPaymentIntent: (orderId: number) =>
  request<CreatePaymentIntentResponse>(`/api/payments/intent/${orderId}/retry`, {
    method: 'POST',
    skipAuth: true,
  }),
```
(matches the existing `skipAuth` pattern used by `getPaymentIntent`/
`syncPaymentIntent` — confirm this is intentional/pre-existing, not
something to fix as part of this work.)

### Reuse the existing Stripe Elements flow

`Checkout.tsx` already contains everything needed to re-enter card details
(`PaymentForm` component, `Elements` wrapper, `handlePaymentConfirmed` →
`syncPaymentIntent` → poll order status), but it's wired to the
place-a-new-order flow (`useCart`, `handlePlaceOrder`) and is a local,
unexported function inside `Checkout.tsx`. Retry needs the same
confirm-payment mechanics against an **existing** order, without a cart.

Plan: extract `PaymentForm` (the Stripe Elements card-entry + `confirmPayment`
button) out of `Checkout.tsx` into a shared component,
`ui-shop/src/components/PaymentForm.tsx`, with the same props it has today
(`orderId`, `onPaymentConfirmed`, `onError`). `Checkout.tsx` imports it from
there instead of defining it locally — no behavior change there.

New page: `ui-shop/src/pages/RetryPayment.tsx`, routed at
`orders/:id/pay` in `App.tsx`:
- On mount: loading state → `api.payments.retryPaymentIntent(orderId)`.
- On success: render `<Elements stripe={stripePromise} options={{clientSecret}}><PaymentForm .../></Elements>`,
  same as `Checkout.tsx`'s `step === 'payment'` branch.
- On failure: clear error message + a way back (link to `/orders/:id`), no
  silent retry loop.
- `onPaymentConfirmed`: call `api.payments.syncPaymentIntent(orderId)` — this
  is the call that actually flips `Payment.status` to `SUCCEEDED` and fires
  the outbox event chain (`payment` → `payments.events` → `shop`/`delivery`),
  since there's no live webhook in this environment to do it independently
  (see §2). Await it (don't fire-and-forget like `Checkout.tsx` does today —
  worth tightening this while touching the flow, since for retry there's no
  webhook safety net at all if this call is dropped) before navigating, and
  surface an error if it fails rather than silently swallowing it. Then
  `navigate(`/orders/${orderId}`)` — **do not** set any local "paid" state;
  the order detail page's existing polling (`OrderDetail.tsx`'s
  `POLL_INTERVAL` effect) re-fetches from the `payments`/`shop` services and
  will reflect `SUCCEEDED`/`PAID` once `/sync`'s outbox event has propagated,
  not because the click happened. This satisfies the "don't mark paid
  client-side" requirement for free, since `RetryPayment.tsx` holds no
  order/payment status state of its own.
- `onError`: show the Stripe error message inline, let the user retry the
  `PaymentElement` submission (same UX as `Checkout.tsx`'s `handlePaymentError`).

### `OrderDetail.tsx` button

Add near the existing `Back to Orders` link (`OrderDetail.tsx:169`):
```tsx
{payment && payment.status !== 'SUCCEEDED' &&
  !['SHIPPED', 'DELIVERED', 'CANCELLED', 'RETURNED', 'REIMBURSED'].includes(order.status) && (
  <Link to={`/orders/${order.id}/pay`} className="btn" style={{ marginTop: 16, marginLeft: 8 }}>
    Retry Payment
  </Link>
)}
```
Since the actual "create the new intent" call and its loading/error state
now live in `RetryPayment.tsx` (not inline on `OrderDetail.tsx`), this is a
plain link — simplest option, and keeps `OrderDetail.tsx`'s existing polling
`useEffect` untouched. (Alternative considered: trigger the retry call
directly from a button on `OrderDetail.tsx` and only navigate once the new
`clientSecret` comes back — more responsive but duplicates the loading/error
handling `RetryPayment.tsx` already needs to have anyway for direct
navigation/refresh. Recommend the link + dedicated page for less duplicated
state.)

### Routing

`App.tsx`: add `<Route path="orders/:id/pay" element={<RetryPayment />} />`
next to the existing `orders/:id` route.

## 5. Files touched — summary

| Area | File | Change |
|---|---|---|
| Payment backend | `payment/.../route/PaymentRoute.java` | new `POST .../retry` route |
| Payment backend | `payment/.../handler/PaymentHandler.java` | new `retryPaymentIntent` handler |
| Payment backend | `payment/.../service/PaymentService.java` | new `retryPaymentIntent` method, refactor to share intent-creation logic with `doCreatePaymentIntent` |
| Delivery backend | `delivery/.../consumer/PaymentEventConsumer.java` | fix status mapping bug (§3) |
| Frontend | `ui-shop/src/api/payments.ts` | add `retryPaymentIntent` |
| Frontend | `ui-shop/src/components/PaymentForm.tsx` (new) | extracted from `Checkout.tsx` |
| Frontend | `ui-shop/src/pages/Checkout.tsx` | import extracted `PaymentForm` instead of local def |
| Frontend | `ui-shop/src/pages/RetryPayment.tsx` (new) | retry flow page |
| Frontend | `ui-shop/src/pages/OrderDetail.tsx` | conditional "Retry Payment" link |
| Frontend | `ui-shop/src/App.tsx` | new route `orders/:id/pay` |

# Verifications
User will verify.