# Payment Service Logic Analysis

## Symptom

- Outbox table has `PaymentIntentCreated` events
- Payment table is **completely empty**
- `GET /api/payments/intent/{orderId}` returns 404
- Stripe health check passes (`{"stripe":"connected","status":"UP","mode":"live"}`)

## Root Cause

**`Payment` entity does not implement `Persistable<UUID>`.**

### How Spring Data R2DBC decides INSERT vs UPDATE

| Class | Implements `Persistable` | `isNew()` behavior | `save()` issues |
|---|---|---|---|
| `OutboxEvent` | ✅ Yes | Explicitly returns `isNew = true` | **INSERT** ✅ |
| `Payment` | ❌ No | Falls back to `@Id == null` check | **UPDATE** ❌ |

`Payment` generates `this.id = UUID.randomUUID()` in the constructor, so `@Id` is **never null**. Spring Data R2DBC sees a non-null ID, assumes the entity already exists, and issues an **UPDATE** — which matches zero rows, returns the entity with no error, but inserts nothing.

Meanwhile `OutboxEvent` implements `Persistable<UUID>` with a `@Transient boolean isNew = true` and `isNew()` returning `true`, so it correctly issues an **INSERT**.

### Flow trace

```
OrderPlacedConsumer.onOrderPlaced()
  → paymentService.processOrderPlaced(orderId, total, username).block()
    → doCreatePaymentIntent(...)
      → PaymentIntent.create(params, options)          ✅ Stripe API succeeds
      → paymentRepository.save(payment)                 ❌ issues UPDATE (0 rows affected, no error)
      → outboxRepository.save(outboxEvent)              ✅ issues INSERT
```

## Issues to Fix

### 1. `Payment` entity not persisted (Critical) — `Payment.java`

**Fix:** Make `Payment` implement `Persistable<UUID>` with `@Transient isNew` flag.

### 2. Outbox events written for never-persisted payments

If a payment fails to save (e.g. constraint violation), the outbox event should not be written. Currently both saves are in the same flatMap chain — but Spring Data R2DBC does not run a transaction by default. The payment UPDATE silently succeeds (no INSERT, no error), so the chain continues and the outbox INSERT happens.

**Fix:** Either:
- Use `@Transactional` (requires `@EnableR2dbcTransactionManagement` and proper transaction manager)
- Or restructure the chain so the outbox write only happens after verifying the payment was actually inserted

### 3. `processOrderPlaced` discards the result with `.then()`

```java
return doCreatePaymentIntent(...).then();
```

`.then()` subscribes but discards the `Payment` result. If `doCreatePaymentIntent` fails, the error still propagates, so this is not directly harmful — but it makes logging/observability harder.

### 4. No idempotency check when payment already exists

```java
return paymentRepository.findByOrderId(orderId)
    .switchIfEmpty(Mono.defer(() -> doCreatePaymentIntent(...)))
    .doOnNext(existing -> log.info("Payment already exists for order {}, skipping", orderId))
    .then();
```

When a payment already exists, the consumer silently "skips" without comparing the Stripe PaymentIntent status. If the Stripe PaymentIntent ever expires or gets cancelled, a new OrderPlaced event wouldn't retry.

**Fix:** Consider verifying the existing Stripe PaymentIntent is still usable, or at least logging its status.

### 5. `clientSecret` leaked in outbox payload

```java
Map.of(
    "orderId", saved.getOrderId(),
    "stripePaymentIntentId", saved.getStripePaymentIntentId(),
    "clientSecret", saved.getClientSecret(),  // ← sensitive
    ...
);
```

The `clientSecret` is written to the outbox_event payload. Any outbox poller that processes `PaymentIntentCreated` events will have access to the client secret. Consider omitting it if the event is only used for internal pub/sub.

### 6. Payment service security is too restrictive for GET

`PaymentSec.java` required authentication for `GET /api/payments/intent/{orderId}` (now fixed by adding `.pathMatchers("/api/payments/intent/**").permitAll()`).

## Files to Modify

| File | Change |
|---|---|
| `domain/Payment.java` | Implement `Persistable<UUID>` with `@Transient isNew` |
| `domain/OutboxEvent.java` | No change needed (already correct) |
| `service/PaymentService.java` | Fix the `.then()` pattern if needed, add idempotency checks |
| `security/PaymentSec.java` | Already fixed (added permitAll for `/api/payments/intent/**`) |
