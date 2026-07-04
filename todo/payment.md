# Stripe Payment Integration Plan

A phased plan for integrating Stripe into the Granite Security e-commerce platform, replacing the generic mock payment in Master-Plan Phase 5 with real Stripe API calls while maintaining the event-driven architecture.

## Architecture decisions (read first)

1. **Stripe Payment Intents API** — the modern, PCI-compliant approach. The server creates a `PaymentIntent`, the client collects card details via Stripe Elements (never touches our server), and Stripe confirms asynchronously.

2. **Webhook-driven state machine** — Stripe sends webhook events (`payment_intent.succeeded`, `payment_intent.payment_failed`) that drive order status transitions. No polling.

3. **Payment service owns Stripe** — the `payment` microservice (port 8062) owns all Stripe API calls. The shop service never talks to Stripe directly.
   - PaymentIntent creation is **synchronous** (shop calls payment via REST during order placement) so `clientSecret` is available immediately.
   - Post-payment processing (webhook → status transitions) remains **event-driven** via Kafka.

4. **Idempotency everywhere** — Stripe API calls use idempotency keys; webhook processing is idempotent by `Event` idempotent replay; Kafka events use event ids for deduplication.

5. **Test mode first** — all development uses Stripe test keys (`sk_test_...`, `pk_test_...`). Real charges only after full e2e validation.

## Prerequisites

- Stripe account (test mode)
- `STRIPE_SECRET_KEY` (server) and `STRIPE_PUBLISHABLE_KEY` (client-facing)
- `STRIPE_WEBHOOK_SECRET` for webhook signature verification
- Stripe CLI (`stripe listen --forward-to`) for local webhook testing

## Phase 1 — Payment service scaffold

### Step 1.1 — Create the payment microservice
- **Goal:** A new `payment/` module consistent with `shop/` conventions.
- **Do:** Scaffold with WebFlux, R2DBC + Liquibase, OAuth2 resource server, Kafka producer/consumer. Port 8062. Add to `compose.yaml`.
- **Done when:** Service boots and connects to Kafka + its Postgres.

### Step 1.2 — Add Stripe SDK dependency
- **Do:** Add `com.stripe:stripe-java` to `build.gradle.kts`. Configure `Stripe.apiKey` from `STRIPE_SECRET_KEY` env var.
- **Done when:** A health-check endpoint can call `Stripe.PaymentIntent.list()` in test mode.

### Step 1.3 — Database schema
- **Do:** Liquibase changesets for:
  - `payment` table: `id`, `order_id` (FK to shop's `customer_order`), `stripe_payment_intent_id`, `amount`, `currency`, `status` (enum: `CREATED`, `PROCESSING`, `SUCCEEDED`, `FAILED`, `CANCELED`), `client_secret`, `created_at`, `updated_at`.
  - `stripe_event` table: `id`, `stripe_event_id` (unique), `type`, `created_at`, `processed_at`. Used for idempotent webhook processing.
- **Done when:** Migration applies cleanly on startup.

## Phase 2 — Stripe API integration (server-side)

### Step 2.1 — Create PaymentIntent on order placement (synchronous)
- **Goal:** When an order is placed via REST, create a Stripe `PaymentIntent` synchronously so the frontend gets `client_secret` immediately.
- **Do:**
  1. Payment service exposes `POST /api/payments/intent` (orderId, total, currency, username) — creates `PaymentIntent` via Stripe, persists `Payment`, publishes `PaymentIntentCreated` outbox event.
  2. Shop's `OrderService.placeOrder()` calls the payment service REST endpoint after persisting the order (includes the user's JWT for auth).
  3. `OrderResponse` includes the `clientSecret` field.
  4. The existing Kafka consumer `OrderPlacedConsumer` still works — it's idempotent (skips if payment exists).
- **Idempotency:** `idempotencyKey = "payment-order-{orderId}"` on Stripe API calls.
- **Done when:** `POST /api/shop/orders` returns `clientSecret` with the order.

### Step 2.2 — Confirm PaymentIntent (frontend, Stripe Elements)
- **Goal:** The frontend collects card details via Stripe Elements and calls `stripe.confirmPayment()`.
- **Do:**
  1. Install `@stripe/stripe-js` + `@stripe/react-stripe-js`.
  2. `Checkout.tsx` uses the `clientSecret` from `OrderResponse` to render `<Elements>` with `<PaymentElement>`.
  3. On submit, `stripe.confirmPayment({ elements, redirect: 'if_required' })` processes the payment.
  4. On success, navigate to order detail page.
- **Done when:** A client can complete payment in Stripe's test mode using `4242 4242 4242 4242`.

### Step 2.3 — Stripe webhook endpoint
- **Goal:** Receive async payment confirmations from Stripe.
- **Do:** A `POST /api/payments/webhook` endpoint (public, verified by Stripe signature):
  1. Construct the event from the raw payload and `Stripe-Signature` header using `STRIPE_WEBHOOK_SECRET`.
  2. Check `stripe_event` table for duplicate `stripe_event_id` — skip if already processed.
  3. Handle relevant event types:
     - `payment_intent.succeeded` → mark payment `SUCCEEDED`, publish `PaymentSucceeded`.
     - `payment_intent.payment_failed` → mark payment `FAILED`, publish `PaymentFailed`.
     - `payment_intent.canceled` → mark payment `CANCELED`, publish `PaymentCanceled`.
  4. Record the `stripe_event_id` for idempotency.
- **Security:** Verify the webhook signature on every request. Do **not** expose this endpoint through the gateway's OAuth2 filter (it's called by Stripe, not a browser).
- **Done when:** Running `stripe trigger payment_intent.succeeded` hits the webhook and transitions state.

## Phase 3 — Event wiring & order status

### Step 3.1 — Consume payment events in the shop service
- **Goal:** The shop advances `customer_order.status` based on payment outcomes.
- **Do:** Add a Kafka consumer in `shop` for `PaymentSucceeded` / `PaymentFailed`:
  - `PaymentSucceeded` → transition `PENDING → PAID`.
  - `PaymentFailed` → transition `PENDING → PAYMENT_FAILED` (optionally allow retry).
- **Idempotency:** Keyed by order id; skip if order already at or past the target state.
- **Done when:** A Stripe test payment completes and the order status updates to `PAID`.

### Step 3.2 — Compensation: restock on payment failure
- **Goal:** If payment fails, restore product stock.
- **Do:** On `PAYMENT_FAILED` transition, call `CatalogService.restock()` (or publish `RestockProducts` event). Mirror the Master-Plan Phase 7 compensation pattern.
- **Done when:** A failed payment restores the reserved stock.

## Phase 4 — Frontend integration (shop UI)

### Step 4.1 — Expose `client_secret` via shop API
- **Goal:** The frontend needs the `client_secret` to power the payment form.
- **Do:** Add `GET /api/shop/orders/{id}/payment` endpoint (authenticated, owner-scoped) that returns `{ clientSecret: "pi_xxx_secret_yyy" }`. The shop service queries the payment service or reads from its own DB if the payment event data is replicated.
- **Alternative:** Include `client_secret` in the `PlaceOrderResponse` directly (requires synchronous call from shop → payment service).
- **Done when:** After placing an order, the client can fetch the `client_secret`.

### Step 4.2 — Stripe Elements on the checkout page
- **Goal:** Client-side card form.
- **Do:** Use `@stripe/stripe-js` (or Kotlin/JS equivalent) to mount a `PaymentElement` and call `stripe.confirmPayment()` with the `client_secret`.
- **Done when:** A user can enter card details in the browser and complete a test payment.

### Step 4.3 — Payment status polling / real-time update
- **Goal:** The UI shows the order status progressing after payment.
- **Do:** Poll `GET /api/shop/orders/{id}` or use SSE (Master-Plan Step 9.3) to reflect `PAID` status after webhook processing.
- **Done when:** The UI transitions from "pending payment" to "paid" automatically.

## Phase 5 — Operational hardening

### Step 5.1 — Stripe webhook retry & idempotency
- **Goal:** Handle Stripe's at-least-once webhook delivery.
- **Do:** The `stripe_event` table deduplicates by `stripe_event_id`. Return `200` on duplicate events without reprocessing. Configure Stripe webhook settings for automatic retries.
- **Done when:** Deliberately replaying a webhook event is a no-op.

### Step 5.2 — Dead-letter queue for failed Stripe API calls
- **Goal:** Stripe API outages don't lose orders.
- **Do:** If `PaymentIntent.create()` fails (network error, rate limit), retry with exponential backoff. After max retries, send to Kafka DLT (Dead Letter Topic). Monitor DLT for manual intervention.
- **Done when:** A simulated Stripe API failure lands the event in DLT without data loss.

### Step 5.3 — Refund flow (ADMIN)
- **Goal:** Admin can refund payments.
- **Do:** Expose `POST /api/shop/orders/{id}/refund` (admin-only). The shop publishes `RefundRequested`; the payment service calls `stripe.refund()` and publishes `RefundProcessed`. Update order status to `REFUNDED` and restore stock.
- **Done when:** Admin refunds a paid order and the money (test) is returned.

### Step 5.4 — Webhook signature verification in tests
- **Goal:** Test the webhook handler without a real Stripe connection.
- **Do:** Generate test events with a known `STRIPE_WEBHOOK_SECRET` using `EventDataObject` and `Event` construction. Verify signature verification logic in unit tests.
- **Done when:** Webhook handler tests pass without network calls.

## Phase 6 — Go-live preparation

### Step 6.1 — Production Stripe keys & webhook endpoints
- **Do:** Set `STRIPE_SECRET_KEY` (live), `STRIPE_PUBLISHABLE_KEY`, and `STRIPE_WEBHOOK_SECRET` in the production environment. Update the Stripe webhook URL in the Stripe dashboard to point to the production gateway.
- **Done when:** Webhook events from live Stripe reach the payment service.

### Step 6.2 — PCI compliance checklist
- **Do:** Verify:
  - No card data ever touches our servers (Stripe Elements / PaymentElement handles it).
  - Webhook payloads are signature-verified before processing.
  - All API calls use HTTPS.
  - Stripe API keys are secrets-managed, not in code or logs.
  - No raw card data in logs, metrics, or events.
- **Done when:** PCI SAQ A or similar self-assessment confirms compliance.

### Step 6.3 — Monitoring & alerting
- **Do:** Track metrics: payment success rate, Stripe API latency, webhook processing lag, refund rate. Set up alerts for elevated failure rates.
- **Done when:** A dashboard shows payment health and alerts on anomalies.

### Step 6.4 — Load testing
- **Goal:** Verify the payment flow handles concurrent orders.
- **Do:** Use Stripe test mode with virtual card numbers. Simulate N concurrent order → payment → webhook flows. Ensure no race conditions on stock or payment deduplication.
- **Done when:** 50 concurrent orders complete without errors or inconsistent state.

## Key environment variables

| Variable | Default | Used by |
|---|---|---|
| `STRIPE_SECRET_KEY` | — | payment service |
| `STRIPE_PUBLISHABLE_KEY` | — | frontend (via `VITE_STRIPE_PUBLISHABLE_KEY` in `.env`) |
| `STRIPE_WEBHOOK_SECRET` | — | payment service (webhook verification) |
| `STRIPE_CURRENCY` | `usd` | payment service |
| `PAYMENT_SERVICE_URI` | `http://localhost:8062` | shop service (to call payment REST endpoint) |
| `PAYMENT_MICROSERVICE` | `http://payment:8062` | gateway |

## Synchronous REST contract (shop → payment)

Called by shop during `POST /api/shop/orders`:

| Method | Path | Request | Response |
|---|---|---|---|
| `POST` | `/api/payments/intent` | `{ orderId, total, currency?, username }` | `{ id, orderId, stripePaymentIntentId, clientSecret, status, amount, currency, createdAt }` |

Idempotent via `payment-order-{orderId}` idempotency key — retry-safe.

## Kafka event contract (additions)

| Event | Producer | Consumers | Payload |
|---|---|---|---|
| `PaymentIntentCreated` | payment | shop, frontend (via API) | `orderId`, `clientSecret`, `amount` |
| `PaymentSucceeded` | payment | shop | `orderId`, `stripePaymentIntentId` |
| `PaymentFailed` | payment | shop | `orderId`, `failureReason` |
| `PaymentCanceled` | payment | shop | `orderId`, `reason` |
| `RefundRequested` | shop | payment | `orderId`, `amount` (optional, default full) |
| `RefundProcessed` | payment | shop | `orderId`, `refundId` |
| `RestockProducts` | shop | shop (self) | `orderId` |

## Stripe webhook events to handle

| Stripe event | Action |
|---|---|
| `payment_intent.succeeded` | Mark payment SUCCEEDED, publish `PaymentSucceeded` |
| `payment_intent.payment_failed` | Mark payment FAILED, publish `PaymentFailed` |
| `payment_intent.canceled` | Mark payment CANCELED, publish `PaymentCanceled` |
| `charge.refunded` | Mark payment REFUNDED, publish `RefundProcessed` |

## Relation to Master-Plan.md

This plan supersedes and expands Master-Plan's **Phase 5 (Payment microservice)**:

- Master-Plan Step 5.1–5.4 become Phase 1 above (scaffold, consume `OrderPlaced`, persist intent, emit result).
- Master-Plan Step 7.1 (compensation) is Phase 3.2 above.
- Master-Plan Step 4.1 (order status machine) is a prerequisite — orders must have `PENDING`, `PAID`, `PAYMENT_FAILED` states before payment events arrive.

## Implementation order (recommended)

1. **Phase 1** — payment service scaffold + schema (prerequisite)
2. **Phase 2** — Stripe API + webhook (core payment logic)
3. **Phase 3** — wire events into shop (order status advancement)
4. **Phase 4** — frontend Stripe Elements (user-facing payment)
5. **Phase 5** — operational hardening (refunds, retries, DLT)
6. **Phase 6** — go-live (production keys, PCI, monitoring)
