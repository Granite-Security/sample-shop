# Fix Plan: Eliminate Duplicate PaymentIntent Creation

## Problem Summary
When a user places an order in the UI shop, **two** Stripe PaymentIntents are created in the payment microservice:
1. **Direct API call**: Shop's `OrderService` → `PaymentServiceClient` → `POST /api/payments/intent` (idempotency key: `payment-order-{orderId}`)
2. **Kafka event**: Shop's outbox → `orders.events` topic → `OrderPlacedConsumer` → `processOrderPlaced()` (idempotency key: `payment-order-async-{orderId}`)

The different idempotency key prefixes cause Stripe to create two separate PaymentIntents despite the DB check in `PaymentService`.

## Solution: Pure Event-Driven Architecture
Remove the synchronous REST call from shop to payment service. The shop only emits the `OrderPlaced` Kafka event. The payment service's Kafka consumer creates the PaymentIntent asynchronously.

---

## Changes by Service

### 1. Shop Microservice (`shop/`)

#### 1.1 Delete `PaymentServiceClient`
**File:** `shop/src/main/java/org/granitesecurity/shop/client/PaymentServiceClient.java`  
**Action:** DELETE this file entirely.

#### 1.2 Remove PaymentServiceClient dependency from `OrderService`
**File:** `shop/src/main/java/org/granitesecurity/shop/service/OrderService.java`  
**Changes:**
- Remove `PaymentServiceClient` from constructor parameters and field
- Remove `createPaymentAndRespond()` method (lines 133-142)
- Remove call to `createPaymentAndRespond()` in `persistOrder()` (line 124)
- Modify `persistOrder()` to build response with `clientSecret = null`
- Remove `jwtToken` parameter propagation (no longer needed for payment call)

```java
// Before (line 122-128):
.flatMap(order -> {
    CustomerOrder co = order;
    return createPaymentAndRespond(co, itemsWithOrderId, jwtToken)
            .flatMap(response -> {
                OutboxEvent outbox = createOutboxEvent(co, itemsWithOrderId);
                return outboxRepository.save(outbox).thenReturn(response);
            });
});

// After:
.flatMap(order -> {
    CustomerOrder co = order;
    OutboxEvent outbox = createOutboxEvent(co, itemsWithOrderId);
    return outboxRepository.save(outbox)
            .thenReturn(buildOrderResponse(co, itemsWithOrderId, null));
});
```

- Update `buildOrderResponse()` to accept `null` clientSecret (already supported)

#### 1.3 Verify `OrderResponse` DTO handles null clientSecret
**File:** `shop/src/main/java/org/granitesecurity/shop/dto/OrderResponse.java`  
**Check:** `clientSecret` field should be nullable (already is - no change needed)

#### 1.4 Ensure `OrderPlaced` outbox event includes all required fields
**File:** `shop/src/main/java/org/granitesecurity/shop/service/OrderService.java`  
**Method:** `buildPayload()` (line 153-169)  
**Verify:** Payload includes `orderId`, `customerId` (username), `items`, `total`, `orderedAt`  
**Current:** ✅ Already includes all fields needed by `OrderPlacedConsumer`

---

### 2. Payment Microservice (`payment/`)

#### 2.1 Add GET endpoint to fetch Payment by orderId (for frontend polling)
**File:** `payment/src/main/java/org/granitesecurity/payment/handler/PaymentHandler.java`  
**Add:** New method `getPaymentByOrderId(ServerRequest request)`

```java
public Mono<ServerResponse> getPaymentByOrderId(ServerRequest request) {
    Long orderId = Long.valueOf(request.pathVariable("orderId"));
    return paymentRepository.findByOrderId(orderId)
            .flatMap(payment -> ServerResponse.ok().bodyValue(
                    new CreatePaymentIntentResponse(
                            payment.getId(),
                            payment.getOrderId(),
                            payment.getStripePaymentIntentId(),
                            payment.getClientSecret(),
                            payment.getStatus(),
                            payment.getAmount(),
                            payment.getCurrency(),
                            payment.getCreatedAt()
                    )))
            .switchIfEmpty(ServerResponse.notFound().build());
}
```

**File:** `payment/src/main/java/org/granitesecurity/payment/route/PaymentRoute.java`  
**Add route:** `GET("/api/payments/intent/{orderId}", paymentHandler::getPaymentByOrderId)`

#### 2.2 Ensure `OrderPlacedConsumer` works correctly (already does)
**File:** `payment/src/main/java/org/granitesecurity/payment/consumer/OrderPlacedConsumer.java`  
**Verify:** Uses `processOrderPlaced()` with `payment-order-async-` prefix (works correctly with DB check)

#### 2.3 (Optional) Standardize idempotency key prefix
**File:** `payment/src/main/java/org/granitesecurity/payment/service/PaymentService.java`  
**Option:** Use same prefix for both paths if keeping direct call temporarily  
**Decision:** Not needed with pure event-driven approach

---

### 3. Gateway (`gateway/`)

#### 3.1 No changes required
**File:** `gateway/src/main/java/org/granitesecurity/gateway/config/RouterConfig.java`  
**Current routes:**
- `/api/shop/**` → shop service
- `/api/payments/**` → payment service

**Verification:** Both routes already exist (lines 42-51). The new `GET /api/payments/intent/{orderId}` will automatically route via the existing `payment-service` route.

#### 3.2 Security config check
**File:** `gateway/src/main/java/org/granitesecurity/gateway/config/GateSec.java`  
**Verify:** `/api/payments/**` requires authentication (same as shop)  
**Current:** ✅ Should already be protected by OAuth2 login

---

### 4. UI Shop SPA (`ui-shop/`)

#### 4.1 Update order placement flow
**File:** `ui-shop/src/pages/Checkout.tsx` (or equivalent)  
**Changes:**
1. After successful `POST /api/shop/orders` → get `orderId` from response
2. **Poll** `GET /api/payments/intent/{orderId}` until `clientSecret` is available
3. Use `clientSecret` to mount Stripe Elements `<PaymentElement>`
4. Call `stripe.confirmPayment()` on submit

**Polling logic:**
```typescript
async function waitForClientSecret(orderId: number): Promise<string> {
  const maxAttempts = 30; // ~30 seconds
  for (let i = 0; i < maxAttempts; i++) {
    const response = await fetch(`/api/payments/intent/${orderId}`, {
      headers: { Authorization: `Bearer ${token}` }
    });
    if (response.ok) {
      const data = await response.json();
      if (data.clientSecret) return data.clientSecret;
    }
    await new Promise(r => setTimeout(r, 1000));
  }
  throw new Error('PaymentIntent not ready');
}
```

#### 4.2 Add Stripe.js dependencies
**File:** `ui-shop/package.json`  
**Add:**
```json
"@stripe/stripe-js": "^x.x.x",
"@stripe/react-stripe-js": "^x.x.x"
```

#### 4.3 Create Stripe Elements checkout component
**File:** `ui-shop/src/components/PaymentForm.tsx` (new)  
**Implement:** `<Elements>` + `<PaymentElement>` + `confirmPayment()` flow using `clientSecret`

#### 4.4 Environment variable for Stripe publishable key
**File:** `ui-shop/.env` (or `.env.local`)  
**Add:**
```
VITE_STRIPE_PUBLISHABLE_KEY=pk_test_xxxxx
```
I have added the VITE_STRIPE_PUBLISHABLE_KEY in the .env
---

## Implementation Order

1. ✅ **Shop**: Remove `PaymentServiceClient` and direct payment call
2. ✅ **Payment**: Add `GET /api/payments/intent/{orderId}` endpoint
3. ✅ **Gateway**: Verify routing (no changes expected)
4. ✅ **UI Shop**: Implement polling + Stripe Elements payment form

---

## Testing Checklist

- [x] Place order in UI → verify single `OrderPlaced` event in Kafka (`orders.events` topic)
- [x] Payment service consumes event → creates single PaymentIntent in Stripe
- [x] Payment service outbox publishes `PaymentIntentCreated` event
- [x] Frontend polls `/api/payments/intent/{orderId}` → receives `clientSecret`
- [x] Frontend completes payment with Stripe Elements
- [x] Stripe webhook → `payment_intent.succeeded` → shop updates order to `PAID`
- [x] Verify NO duplicate PaymentIntents in Stripe dashboard for single order

---

## Rollback Plan

If issues arise:
1. Revert shop service changes (restore `PaymentServiceClient` and direct call)
2. Revert payment service changes (remove GET endpoint)
3. Both services can run simultaneously during transition (idempotency keys differ)

---

## Notes

- The `OrderPlacedConsumer` uses `.block()` which is acceptable for Kafka listener (runs on separate thread)
- The shop's `OrderPlaced` outbox event is the **single source of truth** for payment creation
- Frontend polling is temporary; can be replaced with SSE/WebSocket when Master-Plan Step 9.3 is implemented
- Gateway routes are path-based; no config changes needed for new payment endpoints