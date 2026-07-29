# Stripe Integration: How Payment Confirmation Actually Works

This explains how the app finds out a Stripe payment succeeded — because the
answer is not "a webhook," even though a webhook exists in the code. There are
two independent confirmation paths, and in practice the second one is doing
the work.

## The two mechanisms

### 1. Webhook (`POST /api/payments/webhook`)

Stripe calls **your** server when an event happens (`payment_intent.succeeded`,
`.payment_failed`, `.canceled`). Handled by `WebhookHandler`
(`payment/src/main/java/org/granitesecurity/payment/handler/WebhookHandler.java`),
routed at `payment/.../route/PaymentRoute.java:26`. The signature is verified
with `com.stripe.net.Webhook.constructEvent(...)` using `STRIPE_WEBHOOK_SECRET`.

This direction requires Stripe to know your endpoint exists: someone has to
register `https://<your-domain>/api/payments/webhook` in the Stripe Dashboard
(Developers → Webhooks). Nobody has done that for `granite-security.org` yet.

### 2. Direct poll/sync (`POST /api/payments/intent/{orderId}/sync`)

Your server calls **Stripe**, not the other way around.
`PaymentService.syncPaymentStatus` (`payment/.../service/PaymentService.java:65-76`)
calls `PaymentIntent.retrieve(stripePiId)` against Stripe's API using only the
`STRIPE_SECRET_KEY` already present in the deployment's secrets — an outbound
call your infra initiates, over an authenticated connection you started.

This is the path that's actually working in production right now.

## The end-to-end flow (what happens when you click "Pay")

1. `stripe.confirmPayment(...)` (`ui-shop/src/pages/Checkout.tsx:36-40`) runs
   in the **browser**, via Stripe.js/Elements. Card details go straight from
   the browser to Stripe's servers (`js.stripe.com`) over HTTPS. Your backend
   and your domain are not involved in this step at all.
2. Once that resolves successfully, `handlePaymentConfirmed`
   (`Checkout.tsx:214-223`) immediately calls the `/sync` endpoint above.
3. `PaymentService.syncPaymentStatus` asks Stripe directly "what's the status
   of this PaymentIntent?" and updates the local `payment` row from the
   answer.
4. The frontend polls `pollOrderStatus` (`Checkout.tsx:135-158`, 1s interval)
   against `GET /api/shop/orders/{id}` and `GET /api/payments/intent/{orderId}`
   until the order leaves `PENDING` and shows `PAID`/`FAILED`.

Every one of these calls is **outbound** — browser→Stripe, or your
server→Stripe. None of them require Stripe to know your domain exists, no DNS
or domain verification, no webhook registration. That's why payments show as
"paid" on `granite-security.org` even though nothing was ever configured on
Stripe's side beyond the API keys.

## Why the local Stripe CLI tunnel exists (kind / localhost)

On kind, the *webhook* path (mechanism 1) can't work at all — Stripe's servers
have no way to reach `localhost`. The Stripe CLI tunnel
(`stripe listen --forward-to gateway:8080/api/payments/webhook`, documented in
`k8s/instructions.md:147-148`) exists purely to make that webhook path
reachable for local testing. It has nothing to do with mechanism 2 — the
`/sync` polling flow works locally with or without the tunnel running, exactly
as it does in production.

## The gap this leaves

The polling flow depends on the browser tab staying open and actually making
the `/sync` call. If the tab closes right after Stripe confirms the payment
but before `/sync` fires (or before `pollOrderStatus` observes the change),
Stripe will show the PaymentIntent as succeeded while the local order stays
stuck at `PENDING` forever — nothing will ever come back and tell the backend.

This is exactly the gap a webhook is meant to close: it's Stripe reliably
telling you the outcome regardless of whether the browser is still around.
The webhook code already exists and works (verified locally via the Stripe
CLI tunnel) — it just isn't registered against `granite-security.org` in the
Stripe Dashboard yet. Registering
`https://granite-security.org/api/payments/webhook` there would close this gap.
(The `secrets-patch.yaml.example` files used to document this as
`/api/secured/payment/webhook`; that was wrong and has been corrected.)
