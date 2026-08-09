# Payment redirects always land on `granite-security.org`

Status: **diagnosed, not fixed.** This is the plan.

A shopper on `sichocolate.com` who pays with **balance** or **PayPal** is dropped on
`granite-security.org` when the payment finishes. They arrive at a different site, on a
different origin, where their SPA session does not exist — so they also appear signed out.

Stripe is unaffected. That is the tell, and §2.2 explains why.

## 1. What actually happens

Two redirects happen at the end of a REDIRECT payment, and **both are wrong**:

```
sichocolate.com/checkout
   │  shopper confirms at PayPal / balance
   ▼
granite-security.org/api/payments/return/PAYPAL?orderId=42     ← hop 1: PUBLIC_BASE_URL
   │  PaymentHandler finalizes, then 302s
   ▼
granite-security.org/orders/42                                  ← hop 2: FRONTEND_ORIGIN
```

Hop 1 is the URL handed to the provider as its return target. Hop 2 is our own redirect
after finalizing. Fixing one without the other still strands the shopper.

## 2. Root cause

`payment` builds every shopper-facing URL from two single-valued config keys:

| Built in `PaymentService` | From | Live value |
|---|---|---|
| `returnUrl` (`:244`) | `PUBLIC_BASE_URL` | `https://granite-security.org` |
| `cancelUrl` (`:245`) | `FRONTEND_ORIGIN` | `https://granite-security.org` |
| `orderPageUrl` (`:252`) | `FRONTEND_ORIGIN` | `https://granite-security.org` |
| `balancePageUrl` (`:257`) | `FRONTEND_ORIGIN` | `https://granite-security.org` |
| `ordersPageUrl` (`:262`) | `FRONTEND_ORIGIN` | `https://granite-security.org` |

Both keys live in `k8s/base/config.yaml` (`:71`, `:91`). When `app-multi` was built to serve
two domains, `TRUSTED_JWT_ISSUERS`, `CORS_ALLOWED_ORIGINS`, `SPA_CLIENT_*` and
`AUTH_SERVER_ISSUER` were all made multi-domain. **`PUBLIC_BASE_URL` and `FRONTEND_ORIGIN`
were missed** — `app-multi/config-patch.yaml` does not override either, so both still carry
the base default.

Verified against the live cluster (`granite-config` ConfigMap, context `davide-hetzner-admin`).

### 2.1 This is not a config oversight that config can fix

The comment above `intentRequest` already states the constraint:

> They are built from config, not from a request, because the main creation path is
> `OrderPlacedConsumer` — a Kafka consumer with no HTTP request in flight.

`payment` cannot know which storefront the shopper came from. The intent is created while
handling a Kafka event; there is no `Host` header to read. Adding a second env var just
swaps which domain is wrong for whom. **The originating domain has to travel with the
order.** See §4.

### 2.2 Why Stripe is unaffected

`ConfirmationMode` decides whether the shopper ever leaves our origin:

| Provider | Mode | Affected |
|---|---|---|
| `BalanceProvider` | `REDIRECT` | **yes** |
| `PayPalPaymentProvider` | `REDIRECT` | **yes** |
| `StripePaymentProvider` | `CLIENT_SDK` | no |

Stripe confirms in-page via Elements and never uses `returnUrl`/`cancelUrl`, so it never
crosses a domain. Balance and PayPal are exactly the two the user reported — the symptom
set matches the code with nothing left over, which is why this diagnosis is worth trusting.

## 3. The path problem hiding behind the domain problem

Fixing the origin alone is not enough: **the two storefronts do not share route shapes.**

| Redirect target | `ui-shop` | `ui-demo` |
|---|---|---|
| `/orders/{id}` | ✅ | ❌ — it is `/profile/orders/{id}` |
| `/orders` | ✅ | ❌ — it is `/profile/orders` |
| `/profile/balance` | ✅ | ✅ |

So a domain-only fix sends sichocolate shoppers to `sichocolate.com/orders/42`, a route
ui-demo does not serve. They would stop landing on the wrong site and start landing on a
blank one.

This also explains a detail of the report: **top-ups were only ever wrong in the domain**,
never the path, because `/profile/balance` happens to exist in both.

## 4. Design

Two flows, two different constraints. Do not force one mechanism onto both.

### 4.1 Order payments — the origin travels with the order

`shop.placeOrder` runs **inside an HTTP request** and therefore does know the domain. The
chain is `shop (HTTP) → outbox → orders.events → payment (Kafka)`, so the origin rides
along:

1. `shop` reads the forwarded host on `POST /api/shop/orders` and stores it on the order
   row (a new nullable column).
2. `OrderService.buildPayload` adds it to the `OrderPlaced` payload.
3. `OrderPlacedConsumer` reads it and passes it to `processOrderPlaced`.
4. `PaymentService` uses it in place of `frontendOrigin`/`publicBaseUrl`, falling back to
   the configured values when absent.

The event is a loose JSON map and there is **direct precedent** for adding optional fields:
`currency` and `provider` are already documented as "optional only for events published
before shop carried them". An in-flight `OrderPlaced` without an origin keeps working and
falls back — no coordinated deploy, no schema version bump.

Persisting it on the order (not just the event) is what makes a **retry** land correctly:
`/orders/{id}/pay` and any refund or re-issue happens long after the event is gone.

It must also be persisted on the **payment** row. At return time the shopper arrives from
the provider, so the `Origin` header on that request is the provider's, not the storefront's
— by then the only record of where they came from is the one we stored.

### 4.2 Top-ups — just read the request

`POST /api/payments/topup-intent` is a direct HTTP call to `payment`. The forwarded host is
right there. No event, no column, no migration. Do not route this through the order
mechanism for symmetry's sake.

### 4.3 Deriving the origin — Stripe already shows us how

**The codebase already solves this problem correctly, three times.** Stripe works on both
domains and always has, and understanding why is the whole design:

`StripePaymentProvider.java` contains **no** reference to `returnUrl`, `cancelUrl`,
`publicBaseUrl` or `frontendOrigin`. It ignores the URLs `intentRequest` hands it — exactly
as the comment above that method says a `CLIENT_SDK` adapter does. Stripe still has a
`return_url`; it is just built by the party that actually knows the answer:

```tsx
// ui-demo/src/pages/CheckoutPage.tsx:295
returnUrl={`${window.location.origin}/checkout`}

// ui-demo/src/pages/BalancePage.tsx:172
returnUrl={`${window.location.origin}/profile/balance?topup=${intent.id}`}
```

reaching Stripe at `StripePaymentForm.tsx:30` as `confirmParams: { return_url: returnUrl }`.

| | Stripe | PayPal / balance |
|---|---|---|
| Who builds the return URL | the **browser**, `window.location.origin` | the **server**, from `PUBLIC_BASE_URL` |
| When | at confirm time, in the user's tab | at intent creation, in a Kafka consumer |
| Can it know the origin? | always | never |
| Per-domain correct? | automatically | only for the one hardcoded domain |

A browser cannot get its own origin wrong. That is the entire difference, and it is why the
bug is invisible on Stripe: nothing about Stripe is more carefully configured, it simply
never asks a component that does not know.

**So: take the origin from the request, not from config.** Concretely, in priority order:

1. the `Origin` header — this is literally `window.location.origin`, the same value the
   Stripe path already trusts, and browsers send it on the `POST`s that matter here;
2. `X-Forwarded-Host` + `X-Forwarded-Proto`, as `auth-server` already derives its
   per-request issuer when `AUTH_SERVER_ISSUER` is blank;
3. the configured `FRONTEND_ORIGIN`, for callers that are neither (service-to-service,
   older orders).

Deriving it server-side keeps both SPAs unchanged — the Host/Origin header *is* the
browser's origin, so this is the same source Stripe uses, read one layer down. The gateway's
`server.forward-headers-strategy` is what makes (2) work; read the comments in
`gateway/application.yaml` before touching anything there.

**One origin replaces both keys.** `config.yaml` already notes that `PUBLIC_BASE_URL` is the
"same value as `FRONTEND_ORIGIN` … because one domain serves both the SPA and the API here".
That holds per-domain too: `sichocolate.com` serves its own `/api` and its own SPA. So a
single resolved storefront origin covers hop 1 and hop 2 alike, and the two config keys
become fallbacks rather than the source of truth.

### 4.4 The origin is attacker-controlled — validate it

**This is the part to get right.** `X-Forwarded-Host` is a request header, and the origin
ends up in a `302 Location`. Taken on trust, this is a textbook open redirect: an attacker
places an order with a forged host and gets our payment return to bounce a shopper to a
site of their choosing, with the order id in the URL.

Validate **at the point of use** — in `payment`, where the string becomes a
`302 Location` — and fall back to the configured default on a miss rather than erroring.
`shop` deliberately does not validate: it records and republishes the value but never
redirects anybody, so a second allow-list there would be one more thing to keep in sync
and still not the one that matters. Everything downstream treats the field as untrusted.

The list itself mirrors `CORS_ALLOWED_ORIGINS` — the domains that may call us are the
domains we may return to — rather than being invented separately and drifting from it.

A stored origin is not exempt. It is re-checked every time it is used, because it was
validated against the allow-list as it stood when it was written, and that list changes.

## 5. Things that will bite

- **PayPal may pin return domains.** Some PayPal app configurations require return URLs to
  be pre-registered. Confirm `sichocolate.com` is accepted before assuming the code change
  is sufficient — this could turn into a provider-console task.
- **`PUBLIC_BASE_URL` is not only a redirect.** Check every other use before changing its
  meaning; it is the service's own public identity, not just a landing page.
- **Webhooks are unaffected and must stay that way.** They are server-to-server and resolve
  by order id. Nothing in §4 should touch the webhook path.
- **Orders placed before the change** have no origin. That is what the fallback is for, and
  it is the current behaviour — no regression, just not a fix for them.
- **Two `009` migrations already exist** (`shop/009-outbox-topic.sql`,
  `profile/009-contact-message.sql`). A shop column here is `010`.

## 6. Decisions

| # | Question | Decision |
|---|---|---|
| D1 | Second env var per domain? | **No.** One `payment` deployment serves both domains; the value is per-order, not per-deployment (§2.1). |
| D2 | Where does the origin come from? | **The originating HTTP request** — `Origin` header first, then `X-Forwarded-Host`/`-Proto`, then config. This is the same source the Stripe path already trusts as `window.location.origin` (§4.3). |
| D2a | Change the SPAs to send it explicitly? | **No.** The Host/`Origin` header already *is* the browser's origin, so reading it server-side needs no frontend change and covers non-browser callers too (§4.3). |
| D2b | Keep `PUBLIC_BASE_URL` and `FRONTEND_ORIGIN` separate? | **No — one resolved origin serves both hops.** One domain serves both the SPA and the API, as `config.yaml` already states; the two keys stay only as fallbacks (§4.3). |
| D3 | How does it reach payment? | **On the order and on the `OrderPlaced` event** — optional field, same pattern as `currency`/`provider` (§4.1). |
| D4 | Top-ups too? | **No — read the request directly.** Top-ups are HTTP-driven and need no event (§4.2). |
| D5 | Trust the header? | **Never.** Allow-list it, reuse `CORS_ALLOWED_ORIGINS`, fall back to config on a miss (§4.4). |
| D6 | Missing origin on old orders | **Fall back to `FRONTEND_ORIGIN`.** Current behaviour, no regression. |
| D7 | The path divergence | **Align `ui-demo`'s routes to `/orders/{id}`** rather than teaching payment two path shapes. Payment should not know how a frontend routes. Revisit only if the demo's `/profile/*` grouping is deliberate. |
| D8 | Fix `PUBLIC_BASE_URL` too? | **Yes — both hops.** Fixing only hop 2 still lands the shopper on the wrong domain first (§1). |

## 7. Phases

**Phase 1 — stop the bleeding on top-ups.** §4.2 only: read the forwarded host in
`createTopupIntent`, allow-list it, use it for `balancePageUrl`. Self-contained, one
service, no migration, no event change. Fixes the balance top-up return on sichocolate.

**Phase 2 — order payments.** Shop column (`010`) + `OrderPlaced` field + consumer +
`PaymentService` fallback logic. Spans `shop` and `payment`; deploy shop first so events
start carrying the origin before payment starts reading it (the fallback makes either
order safe, but this way there is no window where it does nothing).

**Phase 3 — the paths.** Align `ui-demo`'s order routes (D7). Until this lands, sichocolate
order returns are *wrong-domain* rather than *broken*, which is the better failure of the
two — hold Phase 3 and Phase 2 together in one release if possible.

## 8. How we verify

Manual, against the real cluster — none of this is reachable by the mock-based tests.

```bash
kubectl config current-context                          # always, before anything
kubectl -n granite get cm granite-config \
  -o jsonpath='{.data.FRONTEND_ORIGIN}{"\n"}{.data.PUBLIC_BASE_URL}{"\n"}'

# Per phase, as a real shopper in a real browser — the redirect chain is the test:
#  1. top-up with balance on sichocolate.com   → lands sichocolate.com/profile/balance
#  2. buy with balance on sichocolate.com      → lands sichocolate.com/<order page>
#  3. buy with PayPal on sichocolate.com       → same
#  4. repeat all three on granite-security.org → unchanged, still lands there
#  5. Stripe on both                           → untouched, never leaves the origin
#
# Watch the whole chain, not the final page — hop 1 (§1) is invisible if you only
# check where you end up after a successful payment.

# The open-redirect check (D5), which no happy-path click will catch:
curl -s -X POST https://sichocolate.com/api/shop/orders \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H 'X-Forwarded-Host: evil.example' -d '{...}' -i
# then confirm the resulting payment's redirect target is the configured fallback,
# NOT evil.example.
```
