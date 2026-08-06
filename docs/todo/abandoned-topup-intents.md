# Abandoned top-up intents accumulate as `CREATED` forever

Status: **not started.** Harmless today; recorded so it is a decision rather than
a surprise.

## What happens

Starting a top-up opens a payment at the provider and writes a `payment` row with
`status = CREATED`. Nothing else moves — no ledger rows exist until capture, which
is deliberate (`docs/finance/finance.md` D7). If the shopper never completes it,
that row stays `CREATED` for good.

Observed in production within ninety seconds of one user:

```
paymentdb → payment, WHERE username = 'davide.listello'

07:43:51  paypal  CREATED    20.00   ← started
07:44:47  paypal  CREATED    20.00   ← started again
07:44:55  stripe  SUCCEEDED  20.00   ← gave up on PayPal, paid by card
```

Two abandoned PayPal intents, then Stripe succeeding eight seconds later. **No money
moved on either abandoned one** — `CREATED` for a PayPal top-up means the order was
opened and never captured.

## Why it is harmless

- The ledger is untouched, so no invariant is affected and no balance is wrong.
- `GET /admin/reconcile` is unaffected: it reads `ledger_entry`, not `payment`.
- A `CREATED` row is not a claim that anything is owed.

## Why it is still worth fixing

**`CREATED` cannot be told apart from "in progress" without asking the provider.**
That matters in two places:

1. **Support.** "Did this top-up go through?" cannot be answered from our own data
   for a `CREATED` row — someone has to call `/sync` or open the provider dashboard.
2. **Volume.** One user produced three rows in ninety seconds while switching
   providers. Every abandoned checkout leaves one, permanently.

## Options

- **A sweep.** Age out top-up intents left `CREATED` beyond some window (a day, say)
  by calling `/sync` once and marking the still-uncaptured ones `CANCELED`. That
  makes the status honest rather than deleting evidence, and `PaymentStatus` already
  has `CANCELED`.
- **Do nothing, and document it.** Acceptable while volume is low, which is the
  current state.

Deleting the rows outright is the option to avoid: an abandoned intent is a real
thing that happened at a provider, and the row is the only record of it on our side.

## Where to look

| What | DB | Pod | Table |
|---|---|---|---|
| Top-up and order payments | `paymentdb` | `postgres-payment` | `payment` |
| Webhook deliveries | `paymentdb` | `postgres-payment` | `provider_event` |
| Balances and movements | `balancedb` | `postgres-balance` | `account`, `ledger_entry` |

```bash
# Every top-up still sitting at CREATED
kubectl -n granite exec deploy/postgres-payment -- psql -U myuser -d paymentdb -c \
  "SELECT id, username, amount, provider, created_at
   FROM payment WHERE purpose = 'TOPUP' AND status = 'CREATED'
   ORDER BY created_at;"

# Ask the provider what actually happened to one
curl -X POST https://granite-security.org/api/payments/topup/<paymentId>/sync \
  -H "Authorization: Bearer $TOKEN"
```
