# Adding PayPal as a second payment provider

Status: **steps 1–7 implemented** · 2026-08-04 · sandbox app registered, adapter
written, not yet run end to end with a real shopper. Steps 8–9 (webhook
registration, dropping the deprecated aliases) remain.

## Verified against the live sandbox (2026-08-04)

Four assumptions in this plan were checked against `api-m.sandbox.paypal.com`
rather than taken from documentation, using the exact request body the adapter
builds:

| Checked | Result |
|---|---|
| Credentials + token | 200, `expires_in: 32400` (9h) — the 5-minute refresh margin is ample |
| Create order with CHF | 200, order `12K34094SH736372K`, amount `{"currency_code":"CHF","value":"42.50"}` accepted |
| Status of a fresh order | **`PAYER_ACTION_REQUIRED`**, not `CREATED` — both map to null, so no transition either way |
| Approve link `rel` | **`payer-action`** is what comes back when `payment_source` is specified; `payerActionLink()` checks it first, with `approve` as fallback |
| `PayPal-Request-Id` idempotency | Replaying the same key returned **the same order id** — this is what makes at-least-once `OrderPlaced` redelivery safe |
| `custom_id` echo | Comes back at `purchase_units[0].custom_id`, **not** top level — `orderIdOf()` falls back to the purchase unit for exactly this |

This is step 5 of [`refactor-payment.md`](./refactor-payment.md) — "Second
provider: adapter + selector entry + config flag. Additive by construction if
1–4 are done." Steps 0–4 *are* done, and the claim mostly holds: the port, the
registry, the provider-neutral schema, the API/event contract and the frontend
widget switch all exist and all work without knowing what Stripe is.

But "additive by construction" was written before anything redirect-shaped
existed. Four things in the current code were built for a REDIRECT provider and
have **never been exercised by one**, and PayPal is not merely "Stripe over a
redirect" — it is capture-based, not intent-based. Those are the parts of this
that are real work rather than filling in an interface.

---

## 1. What is already done and needs no change

Verified against the tree at `d5f0727`:

| Piece | State |
|---|---|
| `PaymentProvider` port | Complete; no Stripe type crosses it. `provider/PaymentProvider.java` |
| `PaymentProviderRegistry` | Keyed by `name()`, validates shop currency at startup, `@ConditionalOnProperty` per adapter. |
| Adapter resolved per-payment | `PaymentService.providerFor()` reads `Payment.provider`, not config — a PayPal payment stays PayPal after the default changes. |
| Schema | `payment.provider`, `provider_payment_id`, `provider_payload` (JSON), `payment_attempt`, `provider_event.provider`. Migration 005. No new column strictly required (see §4.3). |
| HTTP contract | `provider` / `providerPaymentId` / `providerPayload` canonical; `GET /api/payments/providers` live and public. |
| Kafka contract | `provider` + `providerPaymentId` / `providerRefundId` published; `shop`'s `EventConsumer` accepts either key. |
| `POST /api/shop/orders` | Takes optional `provider`, carries it on `OrderPlaced` → `PaymentService.processOrderPlaced(..., providerName)`. Unknown and ambiguous are both 400s. |
| Frontend | `PaymentWidget` switches on `confirmationMode`; `RedirectPaymentWidget` and `ProviderSelector` already built. `Checkout.tsx:160` already sends the selected provider. **Both** `ui-shop` and `ui-demo` migrated. |

**Non-issue, checked:** `providerFor()`'s "no provider recorded → fall back to
`defaultProvider()`" branch (`PaymentService.java:72-81`) would start throwing
`AmbiguousProviderException` once two providers are enabled. It cannot fire:
`payment.provider` is `NOT NULL` since migration 001 and the `Payment`
constructor has always set it. No backfill migration is needed. The branch is
dead code; leaving it is fine.

---

## 2. Why PayPal is not just "Stripe with a redirect"

Stripe's PaymentIntent is one object whose *status* moves to `succeeded`. Money
moves as a side effect of confirmation; we only ever read.

PayPal Orders v2 is **two objects and an explicit verb**:

```
POST /v2/checkout/orders   (intent=CAPTURE)  → order id, status CREATED
   ↓ shopper visits the payer-action link, approves
                                             → status APPROVED   ← no money yet
POST /v2/checkout/orders/{id}/capture        → status COMPLETED, capture id
                                             ← money moves HERE
```

Three consequences that shape everything below:

1. **`APPROVED` is not paid.** Mapping it to `SUCCEEDED` would mark orders paid
   that were never charged, publish `PaymentReceived`, and ship goods for free.
   It must map to `null` — the port's existing "no transition" convention,
   which Stripe already uses for `requires_payment_method`.
2. **Someone has to call capture.** Stripe needs no equivalent. The whole of
   §4.4 exists because of this.
3. **Refunds are against the capture id, not the order id.** `provider_payment_id`
   holds the order id (that is what `retrieveIntent` needs), so `createRefund`
   receives the wrong identifier unless the adapter resolves one from the other.

---

## 3. Decisions to make before writing code

| # | Decision | Recommendation |
|---|---|---|
| 3.1 | SDK or raw HTTP? | **Raw `WebClient`.** PayPal's `paypal-server-sdk` is blocking; this service is WebFlux + R2DBC end to end and `CLAUDE.md` forbids blocking calls in request paths. Orders v2 is four endpoints — the SDK buys little and costs the reactive property. |
| 3.2 | Where does capture happen? | **`/api/payments/return/paypal` primarily, webhook `CHECKOUT.ORDER.APPROVED` as recovery.** See §4.4. |
| 3.3 | Does `/sync` capture? | **No.** `retrieveIntent` stays read-only. Making the shopper's poll a money-moving call is the kind of thing that is impossible to reason about after the fact. |
| 3.4 | Webhook required? | **Yes, for PayPal specifically** — unlike Stripe, where it is belt-and-braces over `/sync`. Without it, a shopper who approves and then closes the tab leaves an order permanently APPROVED and uncaptured. |
| 3.5 | Sandbox or live first? | Sandbox. `PAYPAL_ENV` switches the base URL; never infer it from the key. |
| 3.6 | Enabled by default? | **No** — `payment.providers.paypal.enabled` defaults `false`. Enabling it is what makes the shop multi-provider, and that flips `POST /api/shop/orders` into requiring an explicit `provider`. That should be a deliberate act, not a deploy side effect. |

---

## 4. The four gaps that are actually work

### 4.1 `returnUrl` / `cancelUrl` are never populated

`CreateIntentRequest` has both fields, documented as "where a REDIRECT provider
sends the shopper". **Nothing ever sets them.** The only constructor in use is
the `of(...)` static factory, which hardcodes `null, null`:

- `PaymentService.java:366` — retry path
- `PaymentService.java:637` — create path

PayPal requires these at order-creation time. And creation happens inside
`OrderPlacedConsumer` — a Kafka consumer, **no HTTP request in flight** — so the
base URL cannot come from a request header or `Origin`. It must be config.

**Work:**
- Add `app.public-base-url` (e.g. `https://granite-security.org`) to
  `application.yaml`, `compose.yaml`, `k8s/base/config.yaml`. The value already
  exists conceptually — same domain used for the OIDC issuer.
- Build the URLs in `PaymentService` where the request is assembled, not in the
  adapter: `{base}/api/payments/return/paypal?orderId={id}` and
  `{base}/orders/{id}?payment=cancelled`.
- Use the full `CreateIntentRequest` constructor instead of `of(...)` at both
  call sites. Keep `of(...)` — it is still right for CLIENT_SDK.

> ⚠️ The one deployment where this needs care is `app-multi`, which serves two
> domains from one backend. A single `public-base-url` will return every shopper
> to whichever domain is configured. Either accept that for now (document it) or
> thread the origin from `shop` through `OrderPlaced`. **Recommend accepting it**
> and noting it — threading it is a contract change for a cosmetic problem.

### 4.2 `ProviderIntent.redirectUrl` is a dead field

`ProviderIntent` has a top-level `redirectUrl`, documented as "where to send the
shopper". It is **never read.** `PaymentService.toProviderPayload()` serializes
`intent.payload()` and nothing else, so a value in `redirectUrl` is silently
dropped on save. Meanwhile `RedirectPaymentWidget` reads
`payload.redirectUrl` — from the *payload map*.

(For contrast, `declineReason` — the other field that looks decorative — *is*
read, at `PaymentService.java:510,521`. So the omission is specific, not a
pattern.)

Two ways to close it. **Recommend the second:**

- Adapter puts the approve link in `payload` and leaves `redirectUrl` null.
  Works today with zero changes — and leaves a field in a shared record whose
  javadoc lies.
- **Change `toProviderPayload` to merge `redirectUrl` into the persisted map**
  when non-null. Then the record means what it says, the frontend keeps reading
  `payload.redirectUrl`, and the next redirect provider does not rediscover this.
  ~5 lines in `PaymentService`.

Either way, add a test pinning that a `ProviderIntent` with a `redirectUrl`
survives the round trip to `providerPayload` — this is exactly the sort of thing
that silently regresses.

### 4.3 `/api/payments/return/{provider}` does not exist

Promised in two javadocs (`ConfirmationMode.REDIRECT`,
`RedirectPaymentWidget`) and in `refactor-payment.md` §6. Not in
`PaymentRoute.java`. Not permitted in `PaymentSec.java`.

**Work:**
- `PaymentRoute`: `GET /api/payments/return/{provider}`. **GET**, because the
  shopper's browser arrives by redirect.
- `PaymentSec`: `.pathMatchers("/api/payments/return/**").permitAll()` — the
  shopper comes back from PayPal with no Authorization header. Same reasoning as
  the webhook matcher, and it must be `/**` for the same reason.
- `gateway`: `/api/payments/**` already routes as one block and `GateSec` is
  `anyExchange().permitAll()`, so no gateway change. Worth confirming in-cluster
  rather than trusting this line.
- Handler: resolve the payment, ask the adapter to finalize, then **HTTP 303**
  to `{base}/orders/{orderId}`. 303 specifically — same reason as commit
  `1ee3e23` in `auth-server`: the target must be fetched as GET.
- Treat it as **untrusted input**. The `token`/`PayerID` query params are
  attacker-controllable; the capture call itself is the authority on whether
  money moved. Never mark a payment paid because someone hit the return URL.

### 4.4 Capture, and who is responsible for it

The shape that works with the existing port:

```
createIntent()      → POST /v2/checkout/orders (intent=CAPTURE)
                      store order id in provider_payment_id
                      store payer-action link in provider_payload.redirectUrl
                      status → CREATED (our vocabulary)

/return/paypal      → adapter captures, then applyProviderStatus(...)
                      COMPLETED → SUCCEEDED, publishes PaymentReceived via outbox

webhook             → CHECKOUT.ORDER.APPROVED   : capture (recovery path)
                      PAYMENT.CAPTURE.COMPLETED : → SUCCEEDED
                      PAYMENT.CAPTURE.DENIED    : → FAILED
                      PAYMENT.CAPTURE.REFUNDED  : refund transition

retrieveIntent()    → GET /v2/checkout/orders/{id}, read-only
                      CREATED/SAVED   → null (no transition)
                      APPROVED        → null (no transition)  ← the load-bearing one
                      COMPLETED       → SUCCEEDED
                      VOIDED          → CANCELLED
```

Capture is not on the port. It does not need to be: `/return` can call a method
on the concrete `PayPalPaymentProvider`, or — cleaner — the adapter can make
`retrieveIntent` the only read and expose finalization through a small
`RedirectPaymentProvider` sub-interface that `WebhookHandler` and the return
handler check for with `instanceof`. **Recommend the sub-interface**: it keeps
`PaymentProvider` honest about being provider-neutral and makes the next
redirect provider implement a named contract rather than copy PayPal.

**Idempotency of capture is mandatory**, because both the return endpoint and
the webhook will race on the same order routinely. PayPal's `PayPal-Request-Id`
header covers the API side; the DB side is already covered by
`provider_event` dedupe for webhooks, but the return path has no equivalent —
capture must tolerate `ORDER_ALREADY_CAPTURED` and treat it as success by
re-reading the order.

**Refund identifier (§2.3).** `createRefund` receives
`payment.getProviderPaymentId()` = the PayPal *order* id, but the refund
endpoint is `POST /v2/payments/captures/{capture_id}/refund`. Recommended:
adapter does `GET /v2/checkout/orders/{orderId}` and reads
`purchase_units[0].payments.captures[0].id`. One extra call per refund, **no
schema change**. The alternative — a `provider_capture_id` column — is cleaner
but pulls a migration into this change for one field; the payload JSON is also
available if you want to cache it there.

---

## 5. Webhook signature verification — the reactive trap

`PaymentProvider.parseWebhook` is **synchronous**:

```java
ProviderWebhookEvent parseWebhook(String payload, Map<String, String> headers)
        throws WebhookVerificationException;
```

That is fine for Stripe: verification is local HMAC over the payload. **PayPal's
documented verification is a remote API call** to
`/v1/notifications/verify-webhook-signature`. `WebhookHandler.handleWebhook`
calls `parseWebhook` inside a `flatMap` on `bodyToMono` — i.e. **on an event
loop thread**. A blocking HTTP call there stalls the reactor for every request
the service is serving.

Three options, none free:

1. **Offline verification** — fetch the cert from the `paypal-cert-url` header,
   verify the `paypal-transmission-sig` signature locally, cache certs. No
   blocking, no signature change. Most code; requires care validating the cert
   URL is a PayPal domain, or it becomes an SSRF.
2. **Change the port to `Mono<ProviderWebhookEvent> parseWebhook(...)`.** Honest
   and small — `WebhookHandler` already returns `Mono`, and `StripePaymentProvider`
   wraps its result in `Mono.just`. Touches the shared interface, which is why
   it is worth deciding deliberately rather than discovering.
3. `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())` around the
   whole call. Works, but hides a network call behind a synchronous signature —
   the next adapter author will not know it is there.

**Recommend option 2**, with option 1 as a later optimization. Making the port
`Mono`-returning is the truthful shape: verification is I/O for most providers
that are not Stripe.

`PAYPAL_WEBHOOK_ID` is required for verification and is *not* a secret in the
`whsec_` sense — it is an identifier. It still belongs in config, not git-tracked
example files with a real value.

---

## 6. Currency

Shop currency is **CHF** (`PAYMENT_SHOP_CURRENCY`, cutover 2026-08-01), and the
registry fails startup if any enabled provider cannot charge it
(`PaymentProviderRegistry.validate()`). PayPal settles CHF, so this passes.

`supportedCurrencies()` should be honest rather than copied from Stripe's set:
PayPal does **not** settle RON. Declaring `{USD, EUR, CHF}` means a future RON
cutover fails at boot with a clear message instead of at a shopper's checkout —
which is the entire point of that check. Verify the current list against
PayPal's docs at implementation time rather than trusting this paragraph.

---

## 7. Config and secrets

```yaml
payment:
  providers:
    paypal:
      enabled: ${PAYMENT_PROVIDER_PAYPAL_ENABLED:false}
      env: ${PAYPAL_ENV:sandbox}          # sandbox | live → base URL
      webhook:
        enabled: ${PAYMENT_PROVIDER_PAYPAL_WEBHOOK_ENABLED:false}

app:
  public-base-url: ${PUBLIC_BASE_URL:http://localhost:5173}
```

Secrets (`PAYPAL_CLIENT_ID`, `PAYPAL_CLIENT_SECRET`, `PAYPAL_WEBHOOK_ID`) go in
a `PayPalConfig` scoped to the adapter, mirroring `StripeConfig`. Note
`StripeConfig` sets a **global** `Stripe.apiKey`; do not copy that pattern —
PayPal credentials should be instance state on the adapter, since a global is
exactly what makes two providers interfere.

Files to touch, all of them (this is the step most likely to be half-done):
- `payment/src/main/resources/application.yaml`
- `compose.yaml`
- `k8s/base/config.yaml`, `k8s/base/payment.yaml` (env → secretKeyRef)
- `k8s/hetzner/{app,app-chocolate,app-multi}/secrets-patch.yaml` **and their
  `.example` counterparts** — refactor plan §8 flags the examples specifically
  because they are the ones that get forgotten.

Access token: PayPal issues a short-lived bearer via client_credentials. Cache
it with its expiry, refresh ahead of time, and never on the request path if it
can be avoided. A 401 must trigger exactly one re-auth-and-retry, not a loop.

---

## 8. Build order

1. ~~**`app.public-base-url` + populate `returnUrl`/`cancelUrl`**~~ (§4.1)
   **Done.** Two properties rather than one: `app.public-base-url` (gateway
   origin, for PayPal's `return_url`) and `app.frontend-origin` (SPA, for the
   final 303). The second **reuses the existing `FRONTEND_ORIGIN`** that
   `notification` already builds password-reset links from — same question,
   already answered correctly in compose and k8s, so no second variable was
   invented. `CreateIntentRequest.of(...)` is still used nowhere; both call
   sites now go through `PaymentService.intentRequest(provider, ...)`.
2. ~~**Merge `redirectUrl` into `providerPayload`**~~ (§4.2) **Done**, with
   `ProviderPayloadRoundTripTest` pinning it. `toProviderPayload` is now
   package-private so the round trip is testable, matching how
   `payloadMap` was already exposed.
3. ~~**`parseWebhook` → `Mono`**~~ (§5) **Done.** `StripePaymentProvider` keeps
   its logic verbatim behind `Mono.fromCallable`; `WebhookHandler` turned its
   try/catch into `onErrorResume(WebhookVerificationException)`.
4. ~~**`/api/payments/return/{provider}` + `PaymentSec` matcher**~~ (§4.3)
   **Done**, plus `RedirectPaymentProvider`. One thing the plan did not
   anticipate: `CHECKOUT.ORDER.APPROVED` has to *trigger* a capture, which
   `WebhookHandler` had no way to express — it only applied statuses. Hence
   `ProviderWebhookEvent.requiresFinalization` and the `approval(...)` factory,
   and `WebhookHandler` now holds a `PaymentService`.
5. ~~**`PayPalPaymentProvider`**~~ **Done.** Raw `WebClient`, lazy token cache,
   one-shot 401 re-auth, `ORDER_ALREADY_CAPTURED` treated as success.
6. ~~**Enable in sandbox**~~ **Config in place**, `PAYMENT_PROVIDER_PAYPAL_ENABLED=true`
   in `.env` for compose; still `"false"` in `k8s/base/config.yaml`, deliberately.
7. ~~**Frontend polish**~~ **Done, and it was needed.** `usePaymentProviders`
   auto-selects only at `length === 1`, so with two providers nothing was
   pre-selected while "Place Order" stayed enabled — a raw 400 from shop.
   Both `ui-shop/pages/Checkout.tsx` and `ui-demo/pages/CheckoutPage.tsx` now
   gate on `needsProviderChoice`. No other frontend change was required: the
   polling already reads `providerPayload` generically, and `PaymentWidget`
   already switches on `confirmationMode`.
8. **Register the webhook** in the PayPal dashboard at
   `https://granite-security.org/api/payments/webhook/paypal`, set
   `paypal-webhook-id`, flip `PAYMENT_PROVIDER_PAYPAL_WEBHOOK_ENABLED`. Until
   then the handler returns 503 by design, so the capture-on-return path must
   work standalone first. **Still to do.**
9. **Drop the deprecated aliases** (`stripePaymentIntentId`, `clientSecret`,
   `stripeRefundId`). Refactor plan step 4 says a REDIRECT provider is what
   expires them — a PayPal payment has no client secret to put there. Do this
   *after* PayPal is live and cached SPA bundles have turned over, not in the
   same deploy. **Still to do.**

Steps 1–4 were externally invisible and remain so until step 6 is flipped in a
given environment.

---

## 9. Testing

Per `verify-manually-in-k8s`, the real check is a sandbox order end to end in
the cluster. But three things want pinning in code first, because they fail
silently:

- **`APPROVED` maps to `null`, not `SUCCEEDED`** (§2). If one test exists, this
  is it.
- **`redirectUrl` survives the round trip** to `provider_payload` (§4.2).
- **Double capture is safe** — return endpoint and webhook both fire; the order
  must end SUCCEEDED once, with one `PaymentReceived` on the outbox.

Manual, in sandbox:

1. Place an order choosing PayPal → redirected to PayPal → approve → land back
   on `/orders/{id}` with status PAID.
2. **Approve, then close the tab before returning.** Order must still reach PAID
   via the webhook. This is the case that justifies §3.4.
3. Cancel at PayPal → back to the order, still PENDING, retry offers a fresh
   order.
4. Refund a PayPal order → `PaymentRefunded` on `payments.events`, shop shows
   REFUNDED.
5. Place a Stripe order in the same session — confirm the two do not interfere
   and `providerFor()` routes each to the right adapter.

Existing tests that will need attention: `PaymentServiceRefundTest` and
`CreatePaymentIntentResponseTest` (the latter pins the aliases, so it changes at
step 9, not before).

---

## 10. Open questions

1. **`app-multi` return URL** (§4.1) — accept one configured domain for both, or
   thread the origin through `OrderPlaced`? Recommend accepting for now.
2. **Capture id storage** (§4.4) — extra API call per refund, or a
   `provider_capture_id` column? Recommend the call; revisit if refunds get hot.
3. **Webhook verification** (§5) — offline crypto now, or `Mono` port + remote
   call now and offline later? Recommend the latter.
4. **Does `ui-demo` need the same selector check** as step 7? It was migrated
   2026-08-02 with the same structure, but its `ProviderSelector` behaviour with
   two providers has never been exercised either.
