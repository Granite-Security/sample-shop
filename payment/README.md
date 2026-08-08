# payment

Port **8062**. Takes money, gives it back, and tells shop what happened —
WebFlux + R2DBC over `paymentdb`. Multi-provider: Stripe, PayPal and the
in-house `balance` ledger sit behind one `PaymentProvider` interface.

## API

`PaymentRoute` (functional routing); rules in `PaymentSec`.

| Endpoint | Auth |
|----------|------|
| `GET /api/payments/intent/{orderId}` | public — the SPA polls it for `clientSecret` |
| `POST /api/payments/intent` | public |
| `POST /api/payments/intent/{orderId}/sync` | public — confirms without a webhook |
| `POST /api/payments/intent/{orderId}/retry` | public |
| `POST /api/payments/topup-intent`, `/topup/{paymentId}/sync` | authenticated — balance top-ups |
| `GET /api/payments/providers` | public — which providers are enabled |
| `POST /api/payments/webhook/{provider}` | public — provider signature is the auth |
| `GET /api/payments/return/{provider}` | public — where PayPal/balance send the shopper back |
| `/api/payments/internal/**` | `SCOPE_internal` — statuses and order-ids for shop/profile |
| `GET /actuator/health/providers` | public |

`/intent/**` is permit-all on purpose: the SPA fetches the `clientSecret`
before it has anything else, and the secret is itself the capability. The
`internal/**` rule is registered **before** it, or `/internal/statuses` would
fall through to permit-all.

`/sync` is the fallback for deployments with no registered webhook — it asks the
provider directly and advances state. When a provider's webhook *is* enabled,
that provider's `/sync` answers 503, so the two can never both confirm.

## Checkout: how the SPA gets an intent

**The UI never creates the payment intent.** It waits for one to appear. The
only call it makes is `POST /api/shop/orders`; the intent is created later, when
payment consumes `OrderPlaced` and calls the provider.

```
review            shopper picks address + provider
   │  POST /api/shop/orders            ← the only call the UI makes
placing           returns { id, status: PENDING }, no clientSecret
   │
waiting_payment   GET /api/payments/intent/{orderId}, on a timer
   │                404              → no intent row yet, keep polling
   │                200, no payload  → row exists, provider hasn't answered, keep polling
   │                200 + payload    → stop
payment           Stripe Elements / redirect button renders HERE
   │  shopper pays
confirming        POST /api/payments/intent/{orderId}/sync
```

Its existence is gated on the outbox relay's 5s poll **plus** payment's round
trip to the provider. That delay is the whole reason the SPA polls instead of
reading a field off the place-order response.

Both waiting states are bounded by `POLL_TIMEOUT` and fail with different
messages, which is worth keeping: "not created" and "not ready" point at
different halves of the pipeline.

`Checkout.tsx` also branches on `clientSecret` arriving on the place-order
response itself. Shop never fills it in, so that branch is dead — kept only in
case it ever does.

Top-ups are the opposite shape: `POST /api/payments/topup-intent` is called
directly and synchronously, because there is no order and no event to ride on.

## Events

Produced through a **transactional outbox** (`OutboxRelay`, 5s poll) — never
from the request path.

| Topic | Direction | Event | Fields that matter |
|-------|-----------|-------|--------------------|
| `orders.events` | in | `OrderPlaced` | `eventType`, `orderId`, `provider` — which provider must charge. shop rejects an order that names none, so null only appears on events published before that; it falls back to the only enabled provider and fails loudly when several are |
| `orders.events` | in | `RefundRequested`, `OrdersPurged` | `eventType` — one topic carries all three, so branch first |
| `payments.events` | out | — | `orderId` (Kafka key), `status` — `SUCCEEDED`, `FAILED`, `REFUNDED`, `REFUND_FAILED` |

`PaymentStatus`: `CREATED → PROCESSING → SUCCEEDED \| FAILED \| CANCELED`, then
`REFUNDED`. Every payment also carries a `purpose` — `ORDER` or `TOPUP`; a
top-up has no `orderId`, which is why webhooks resolve by reference rather than
by order.

Webhook deliveries are recorded in `provider_event` before acting, so a
redelivery is idempotent. A delivery for a provider whose webhook is disabled
is refused outright — its signing secret is likely unset, so verification
would be meaningless.

## Redirect provider flows

PayPal and balance implement the same port, `RedirectPaymentProvider`: the
shopper leaves, comes back, and an explicit **capture** step takes the money.
Stripe does not — its PaymentIntent succeeds on its own, so there is nothing to
call. Everything below is that one extra verb.

### PayPal — the part that is always the same

Creating the order and getting the shopper to approve never varies:

```
shop ──OrderPlaced──> payment
                        │ create order at PayPal            ← no money moved
                        └─ status APPROVED ≠ paid; redirectUrl goes to the SPA

SPA ──> PayPal approval page ──> shopper approves ──> money still has NOT moved
```

Only `POST /capture` charges the order. What differs between the two setups
below is **who calls it**, and they are mutually exclusive per provider:
when the webhook is enabled, that provider's `/sync` answers 503.

### PayPal with the webhook enabled

PayPal tells us. The browser return is a second, racing path — not the
mechanism.

```
shopper approves
   │
   ├─ browser: GET /api/payments/return/paypal?orderId=42
   │              └─> finalizePayment → POST /capture   ← money moves
   │              └─> 303 to the order page
   │
   └─ PayPal:  POST /api/payments/webhook/paypal  (CHECKOUT.ORDER.APPROVED)
                  └─> finalizePayment → POST /capture   ← money moves

both land on the same finalizePayment, often at the same time:
   already COMPLETED → report it, never charge twice
```

The webhook is what saves the shopper who approves and then closes the tab —
they never reach the return endpoint, and nothing else would capture.

### PayPal with the webhook disabled (this deployment)

Nobody tells us. `/sync` is the only thing that asks, and it is called by the
SPA — from checkout after the shopper returns, and by the order page while the
order sits unpaid.

```
shopper approves
   │
   ├─ browser: GET /api/payments/return/paypal?orderId=42
   │              └─> finalizePayment → POST /capture   ← money moves
   │              └─> 303 to the order page
   │
   └─ SPA: POST /api/payments/intent/42/sync
              └─> finalizePayment → POST /capture       ← money moves

shopper approves and closes the tab instead:
   nothing captures until someone opens the order page and /sync runs
```

`/sync` is a pull, so the money is taken late rather than never. That is the
whole trade: no public webhook endpoint to register, at the cost of a payment
that stays uncaptured until the SPA next asks.

Either way it ends the same:

```
outbox ──> payments.events {orderId, status: SUCCEEDED} ──> shop marks PAID
```

### Balance (in-house ledger)

Identical shape, no external site — the redirect goes to our own gateway. One
extra round trip buys complete uniformity: shop, `/sync`, retry, refunds and the
checkout UI special-case nothing.

```
shop ──OrderPlaced──> payment
                        │ POST /api/balance/internal/intents
                        │   (client_credentials, scope internal)
                        └─ balance checks funds, records the request
                           CREATED → null status: no ledger rows, nothing to expire

SPA ──> GET /api/payments/return/balance?orderId=42
          └─> finalizePayment → POST /internal/intents/{id}/capture
                                └─ ledger rows written  ← money moves HERE

outbox ──> payments.events ──> shop marks PAID
```

An abandoned checkout leaves no money moved and no hold to expire — the intent
simply stays `CREATED`. A retry is a new intent, since the old one charged
nothing.

Top-ups use the same return endpoint keyed on `?paymentId=` instead of
`?orderId=`, and land back on the balance page.

## Outbound calls

| To | How | When |
|----|-----|------|
| Stripe / PayPal | provider SDK / REST, API keys | create intent, confirm, refund |
| `balance` 8067 | OAuth2 `client_credentials`, scope `internal` (`balance-client`) | `/api/balance/internal/intents/**` — only when `BalanceProvider` is enabled |

## Configuration

| Variable | Purpose |
|----------|---------|
| `PAYMENT_R2DBC_URL` / `_USERNAME` / `_PASSWORD` | Runtime DB (`PAYMENT_JDBC_*` is Liquibase only) |
| `KAFKA_BOOTSTRAP_SERVERS` | Broker |
| `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET`, `STRIPE_CURRENCY` | Stripe |
| `PAYPAL_CLIENT_ID` / `_SECRET`, `PAYPAL_WEBHOOK_ID`, `PAYPAL_ENV` | PayPal (`sandbox` \| `live`, never inferred from the credentials) |
| `PAYMENT_PROVIDER_{STRIPE,PAYPAL,BALANCE}_ENABLED` | Which providers exist |
| `PAYMENT_PROVIDER_{STRIPE,PAYPAL}_WEBHOOK_ENABLED` | Off ⇒ `/sync` confirms; on ⇒ `/sync` returns 503 |
| `PUBLIC_BASE_URL`, `FRONTEND_ORIGIN` | Where redirect providers return the shopper |
| `INTERNAL_CLIENT_ID` / `_SECRET`, `AUTH_SERVER_TOKEN_URI` | Token for the balance call |
| `JWT_JWK_SET_URI`, `TRUSTED_JWT_ISSUERS` | Token validation (see greetings) |

Two startup guards, both deliberate: `payment.shop-currency` is validated
against every enabled provider's supported set, and **no enabled provider at
all fails the boot** — a payment service that cannot take money should not
pretend to be up.

`POST /api/shop/orders` requires an explicit `provider` whatever the count — it
is not inferred from there being one enabled. That inference was a trap: with
Stripe and PayPal both on in the cluster, resolving an unnamed provider throws
inside the `OrderPlaced` consumer, so the order was accepted with a 200 and then
quietly never got an intent. A 400 at shop's edge is the same refusal where the
caller can see it. Asking for an unknown provider is a 400 too, not a 500.

```bash
./gradlew bootRun
./gradlew test          # repository tests need Docker (Testcontainers)
```
