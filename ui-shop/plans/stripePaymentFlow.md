# Stripe Payment Flow — End-to-End Plan

## Current State

```
UI place order → shop (OrderPlaced outbox) → Kafka `orders.events`
  → payment consumer → Stripe API → payment DB
  → outbox (`PaymentIntentCreated`) → OutboxRelay → Kafka `payments.events`
  → shop EventConsumer (logs "awaiting completion")
```

UI polls `GET /api/payments/intent/{orderId}` → clientSecret returned ✅ (works now)

## The Gap

When the user clicks **Pay Now** in the Stripe PaymentElement:

```
stripe.confirmPayment() → succeeds (no redirect for test cards)
  → UI polls GET /api/shop/orders/{id}  ← order still PENDING
  → times out after 30s → shows "Order Placed!" (step='done')
```

The order status never reaches PAID because:

```
stripe.confirmPayment() success (browser side)
  → Stripe sends webhook (server-to-server)
  → payment webhook handler updates payment → outbox (`PaymentSucceeded`)
  → OutboxRelay → Kafka `payments.events`
  → shop EventConsumer → order status = PAID
```

**Problem:** The webhook requires Stripe CLI running locally to forward events from Stripe's servers to `localhost:8080/api/payments/webhook`.

## Plan

### Phase 1: Immediate — UI checks payment status directly after confirmPayment

After `stripe.confirmPayment()` succeeds, poll `GET /api/payments/intent/{orderId}` to check if the payment status is `SUCCEEDED` (via Stripe API, no webhook needed). This gives near-immediate feedback.

**File:** `Checkout.tsx`

Instead of starting `pollOrderStatus` immediately, do a short poll on the payment status first (check `payment.status === 'SUCCEEDED'`). If it becomes SUCCEEDED, then start `pollOrderStatus` to wait for the order to reflect PAID.

### Phase 2: Webhook setup for local dev

Need Stripe CLI to forward webhooks:

```bash
# Install: brew install stripe/stripe-cli/stripe
stripe login
stripe listen --forward-to localhost:8080/api/payments/webhook
# Set the webhook signing secret in your env:
export STRIPE_WEBHOOK_SECRET=whsec_...
```

This enables the full async flow: Stripe → payment webhook → outbox → Kafka → shop → order PAID.

### Phase 3: Fix pollOrderStatus timeout bug

Currently on timeout it sets `step = 'done'` regardless of actual status. It should:
1. Check payment status via `GET /api/payments/intent/{orderId}`
2. If payment is SUCCEEDED → show success (may still need to wait for order status)
3. If payment is not SUCCEEDED → show failure

### Phase 4: Handle redirect for 3D Secure cards

For cards requiring 3D Secure (e.g. `4000002500003155`), `stripe.confirmPayment()` redirects the browser. On return to the `return_url`, the app needs to:
1. Check `GET /api/payments/intent/{orderId}` for the payment status
2. If SUCCEEDED, start `pollOrderStatus` for the order

## Files to Modify

| File | Phase | Change |
|---|---|---|
| `ui-shop/src/pages/Checkout.tsx` | 1 | After `confirmPayment` succeeds, poll payment status directly |
| `ui-shop/src/pages/Checkout.tsx` | 3 | Fix timeout to check actual payment status |
| `ui-shop/src/pages/Checkout.tsx` | 4 | Handle redirect return flow |
| `.env` or terminal | 2 | Set `STRIPE_WEBHOOK_SECRET` |
| — | 2 | Run `stripe listen --forward-to ...` |

## Dependencies

- Stripe CLI (for webhook forwarding in local dev)
- `STRIPE_WEBHOOK_SECRET` env var (from `stripe listen` output)
