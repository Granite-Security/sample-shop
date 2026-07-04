# Polling Race Condition: Payment Intent Not Yet Created

## Symptom

After placing an order, the UI polls `GET /api/payments/intent/{orderId}` and gets a **404 Not Found**. After retries, the user sees:

> The payment could not be completed. Please try again or contact support.

## Root Cause

The payment intent is created **asynchronously** via Kafka:

```
Place order → Shop saves outbox → OutboxPoller (poll every 5s)
↓
Kafka topic `orders.events`
↓
Payment service consumer → Stripe API → save payment record
```

The UI starts polling **immediately** after `placeOrder` returns, but the payment record may not exist for **5–10 seconds** (outbox poll interval + Kafka delivery + Stripe API + DB write). The previous retry logic allowed only 3 consecutive 404s before giving up (~4 seconds total).

## Fix Applied

In `Checkout.tsx`, the polling logic now:

| Scenario | Behavior |
|---|---|
| **404 Not Found** | Keep polling (up to `POLL_TIMEOUT` = 30s) |
| **Non-404 errors** (network, 5xx) | Retry up to `MAX_RETRIES` (3) then fail |
| **Timeout** (30s elapsed) | Show "Payment intent not created after timeout" |

## Remaining Concerns

### 1. Retry count for 404 is unlimited

This is intentional — 404 is the expected state during async processing. The 30s `POLL_TIMEOUT` acts as the safety net. If the payment still doesn't exist after 30s, something is wrong (consumer crashed, Stripe down, DB issue).

### 2. No intermediate state feedback

The user sees a spinner with "Preparing payment…" for up to 30 seconds. No progress indication (e.g. "Retrying… attempt 3/30"). This is acceptable for now but could be improved.

### 3. Outbox poll interval (5s) is a bottleneck

The shop's `app.outbox.poll-interval: 5000` means up to 5 seconds before the Kafka message is even sent. If we need faster payments, we could lower this or switch to sending Kafka messages synchronously (inline with the order creation transaction).

## Testing

1. Place an order in the UI
2. Observe the "Preparing payment…" spinner
3. Within ~5-10 seconds, the Stripe payment form should appear
4. Complete the payment with Stripe test card (`4242 4242 4242 4242`)
5. Verify redirect to order page
