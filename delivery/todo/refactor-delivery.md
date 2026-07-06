# Refactor Delivery Microservice

## Goal
Keep the delivery service as a **persistent, event-sourced microservice** but strip it down to:
- Accept a **delivery request** from `orders.events` (address + items)
- Track **payment status** independently from `payments.events` (separate consumer group)
- Let an **operator manually advance** delivery statuses after checking payment is cleared
- Use the **outbox pattern** to emit `delivery.events`
- Expose a **read endpoint** for the UI and an **operator endpoint** for status changes
- Remove: simulator, webhooks, shipping provider, separate tracking route

---

## Current State: All Interactions

### API endpoints (called by)

| Endpoint | Caller | Purpose |
|----------|--------|---------|
| `GET /api/delivery/{orderId}` | **UI** (OrderDetail.tsx via gateway) | Fetch delivery status, provider, tracking number, address, ETA |
| `GET /api/delivery/{orderId}/tracking` | **UI** (OrderDetail.tsx via gateway) | Fetch tracking timeline (status progression events) |
| `POST /api/delivery/webhook/shipping-provider` | **External carriers** (nobody yet) | Carrier pushes `picked_up`/`in_transit`/`delivered`/`failed` updates |

### Kafka consumers (in delivery service)

| Topic | Consumer | Reaction |
|-------|----------|----------|
| `orders.events` | `OrderEventConsumer` | Address snapshot → create `PENDING` delivery record |
| `payments.events` | `PaymentEventConsumer` | Payment succeeded → assign `internal` provider → transition to `PREPARING` |

### Kafka producers (in delivery service)

| Topic | Producer | Payload |
|-------|----------|---------|
| `delivery.events` | `OutboxRelay` (outbox table → topic) | `DELIVERY_CREATED`, `PROVIDER_ASSIGNED`, `STATUS_UPDATE` |

### Consumer of delivery events (other services)

| Service | Listens to | Reaction |
|---------|-----------|----------|
| **Shop** | `delivery.events` | `DISPATCHED` → order `SHIPPED`; `DELIVERED` → order `DELIVERED` |

### Producers of events that delivery consumes

| Service | Topic | Contents |
|---------|-------|----------|
| **Shop** | `orders.events` | `{orderId, username, items[], total, orderedAt, address{...}}` |
| **Payment** | `payments.events` | `{orderId, stripePaymentIntentId, status}` |

### Internal machinery (to remove or keep)

| Component | Decision |
|-----------|----------|
| `DeliverySimulator` | **REMOVE** — operator drives progression manually |
| `DeliveryTracking` + repo | **KEEP** — tracking entries created on each operator action |
| `ShippingProvider` + repo | **REMOVE** — no carrier integration |
| `WebhookEvent` + repo | **REMOVE** — no webhook endpoint |
| `WebhookHandler`, `WebhookService` | **REMOVE** |
| `DeliveryEvent` + repo + `OutboxRelay` | **KEEP** — outbox pattern for `delivery.events` |
| `PaymentEventConsumer` | **KEEP** — but simplified: only updates `paymentStatus` on existing delivery, does NOT auto-advance delivery status |
| `OrderEventConsumer` | **KEEP** — but simplified: creates delivery request with items + address, does NOT start any progression |

---

## Proposed Future State

### Diagram

```
Shop produces ──orders.events──→┐
                                ├──→ delivery service (2 independent consumer groups)
Payment produces ──payments.events──→┘
                                          │
                                          ▼
                              ┌──────────────────────────┐
                              │   delivery (DB)           │
                              │   delivery_event (outbox) │
                              │   delivery_tracking       │
                              └──────────────────────────┘
                                          │
                              OutboxRelay ──delivery.events──→ Shop EventConsumer
                                          │                    (DISPATCHED → SHIPPED
                                          │                     DELIVERED → DELIVERED)
                                          │
                              Operator ──PUT /api/delivery/{orderId}/status
                                          (PENDING → DISPATCHED → DELIVERED)
                                          │
                              UI ────GET /api/delivery/{orderId}
                                          (delivery status + tracking)
```

### What stays

| Component | Reason |
|-----------|--------|
| `Delivery` entity (with new `paymentStatus`, `items` fields) | Persistent delivery request |
| `DeliveryEvent` + repo | Outbox table for `delivery.events` |
| `DeliveryTracking` + repo | Tracking timeline entries per status change |
| `OutboxRelay` | Polls `delivery_event` → publishes to `delivery.events` |
| `OrderEventConsumer` | Creates delivery record from `orders.events` (address + items) |
| `PaymentEventConsumer` | Updates `paymentStatus` on delivery from `payments.events` |
| `GET /api/delivery/{orderId}` | UI reads delivery status + tracking |
| `KafkaConfig` | Required for Kafka producer/consumer infrastructure |

### What goes

| Component | Reason |
|-----------|--------|
| `DeliverySimulator` | Operator drives progression manually |
| `ShippingProvider` + repo | No carrier integration |
| `WebhookEvent` + repo + handler + service | No webhook endpoint |
| `POST /api/delivery/webhook/shipping-provider` route | Removed |
| `GET /api/delivery/{orderId}/tracking` route | Merged into the main delivery response (tracking events included inline) |
| `DeliveryStatus` enum simplified | Only: `PENDING`, `DISPATCHED`, `DELIVERED`, `FAILED` |

### What's new

| Component | Purpose |
|-----------|---------|
| `PUT /api/delivery/{orderId}/status` | Operator changes delivery status (body: `{status, description?}`) |
| `paymentStatus` field on `Delivery` entity | `UNPAID`, `PAID`, `REFUNDED` — set from `payments.events` |
| `items` field on `Delivery` entity | Summary of items being shipped (from `orders.events`) |
| Operator list endpoint `GET /api/delivery` | List all delivery requests with payment status, filterable by status |

### Event flow

**Two independent consumers, same delivery service, separate consumer groups:**

1. `orders.events` → `OrderEventConsumer` (group: `delivery.order.consumer`)
   - Creates `Delivery` record in `PENDING` status
   - Stores: orderId, address snapshot, items summary
   - `paymentStatus` starts as `UNPAID` (default)

2. `payments.events` → `PaymentEventConsumer` (group: `delivery.payment.consumer`)
   - Finds delivery by `orderId`
   - Updates `paymentStatus` to `PAID` (or `REFUNDED`)
   - Does **NOT** advance delivery status — operator decides when to ship

```
orders.events payload (unchanged):
{
  "orderId": 1,
  "username": "alice",
  "address": { "recipientName": "...", ... },
  "items": [ { "productId": 1, "name": "...", "quantity": 1 } ],
  "total": 159.98,
  "orderedAt": "..."
}

payments.events payload (unchanged):
{
  "orderId": 1,
  "stripePaymentIntentId": "pi_xxx",
  "status": "SUCCEEDED"
}
```

---

## Refactoring Steps

### Step 1: Simplify domain model

**`Delivery.java`** — add fields, simplify entity:
- Add `paymentStatus` (String: UNPAID/PAID/REFUNDED)
- Add `items` (String or JSON — summary of items being shipped)
- Remove `provider` field
- Remove `trackingNumber` field
- Keep: `id`, `orderId`, `status`, `recipientName`, `addressLine1/2`, `city`, `state`, `zipCode`, `country`, `estimatedDeliveryDate`, `createdAt`, `updatedAt`
- Keep `markNotNew()` pattern for updates

**`DeliveryStatus.java`** — simplify enum:
- `PENDING` (waiting for operator)
- `DISPATCHED` (operator confirmed shipment)
- `DELIVERED` (operator confirmed delivery)
- `FAILED` (operator marked as failed)

### Step 2: Add operator endpoints

**DeliveryRoute.java** — new routes:
- `GET /api/delivery` — list all deliveries (query params: `status`, `paymentStatus`)
- `GET /api/delivery/{orderId}` — single delivery with tracking events inline
- `PUT /api/delivery/{orderId}/status` — operator changes status

**Operator status change handler:**
- Validates transition (PENDING → DISPATCHED → DELIVERED)
- Creates a `DeliveryTracking` entry with status + timestamp + operator note
- Creates a `DeliveryEvent` outbox entry (eventType: STATUS_UPDATE, payload: `{orderId, deliveryId, status, previousStatus}`)
- Returns updated delivery with tracking

### Step 3: Remove `DeliverySimulator`

- Delete `DeliverySimulator.java`
- No auto-progression logic

### Step 4: Simplify both consumers

**`OrderEventConsumer`** (stays, simplified):
- Creates `Delivery` record in `PENDING` status from `orders.events`
- Stores: orderId, address snapshot, items summary
- `paymentStatus` defaults to `UNPAID`
- No progression triggered

**`PaymentEventConsumer`** (stays, simplified):
- Listens to `payments.events` as separate consumer group (`delivery.payment.consumer`)
- Finds delivery by `orderId`
- Updates `paymentStatus` to `PAID` (when `status=SUCCEEDED`) or `REFUNDED`
- Does **NOT** advance delivery status — no provider assignment, no PREPARING transition
- Does **NOT** create outbox events — payment status is just metadata for the operator

### Step 5: Keep outbox pattern unchanged

- `DeliveryEventRepository.markPublished()` stays (`@Modifying @Query`)
- `OutboxRelay` stays (@Scheduled polling)
- On operator status change → `DeliveryEvent` created → relay publishes to `delivery.events`

### Step 6: Clean up removed components

- Delete: `ShippingProvider.java`, `ShippingProviderRepository.java`
- Delete: `WebhookEvent.java`, `WebhookEventRepository.java`
- Delete: `WebhookHandler.java`, `WebhookService.java`
- Delete: `TrackingResponse.java` (DTO for individual tracking entries — merge into `TrackingDetailResponse`)
- Update `DeliveryResponse.java` — add `paymentStatus`, `items`; remove `provider`, `trackingNumber`
- Update `TrackingDetailResponse.java` — still has `events[]`

### Step 7: Database changes (Liquibase)

**New migration** (`002-refactor-delivery.sql`):
- `ALTER TABLE delivery ADD COLUMN payment_status VARCHAR(20) DEFAULT 'UNPAID'`
- `ALTER TABLE delivery ADD COLUMN items TEXT` (JSON summary)
- `ALTER TABLE delivery DROP COLUMN provider`
- `ALTER TABLE delivery DROP COLUMN tracking_number`
- `DROP TABLE shipping_provider`
- `DROP TABLE webhook_event`
- `DROP INDEX delivery_order_id_idx` (if exists — keep the index on order_id)

### Step 8: No changes to Shop

- **Shop stays as-is** — it already produces `orders.events` (which delivery consumes to create delivery requests) and consumes `payments.events` (to update order status on its side)
- Shop does **not** need to produce any delivery-specific events — delivery service independently listens to `payments.events`

### Step 9: Update UI

- `OrderDetail.tsx` — keep `getDelivery()` call; remove `getTracking()` (tracking is now inline in delivery response)
- `TrackingDetailResponse` still works (events array is part of the delivery response)
- Delivery section shows `paymentStatus` so customer knows if payment cleared
- Delivery section shows `items` summary

### Step 10: Update Gateway (if needed)

- No changes needed — delivery routes already proxied
- Could optionally add operator routes under a different path if needed

---

## New Delivery statuses

```
PENDING ──→ DISPATCHED ──→ DELIVERED
   │                          
   └──→ FAILED ←─────────────┘
```

- **PENDING**: Awaiting operator review. Operator checks `paymentStatus`.
- **DISPATCHED**: Operator shipped the order. Outbox → `delivery.events:DISPATCHED` → Shop sets order to SHIPPED.
- **DELIVERED**: Operator confirmed delivery. Outbox → `delivery.events:DELIVERED` → Shop sets order to DELIVERED.
- **FAILED**: Operator marked as failed (e.g., address issue, inventory).

---

## Tracking entries

Every status change creates a `DeliveryTracking` entry:

| status | description (operator-provided or default) |
|--------|-------------------------------------------|
| `PENDING` | "Delivery request created, awaiting processing" |
| `DISPATCHED` | "Order dispatched — {operator note}" |
| `DELIVERED` | "Order delivered — {operator note}" |
| `FAILED` | "Delivery failed — {operator note}" |

---

## Open Questions
1. **Operator auth**: Should operator endpoints require `SCOPE_admin` or a separate `SCOPE_operator` role?
2. **Items in response**: Should items be a full list or just a count + summary?
