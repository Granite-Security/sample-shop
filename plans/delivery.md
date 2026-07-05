# Delivery Microservice — Plan

New microservice that manages delivery operations. Listens for payment confirmations, simulates delivery progress, and exposes tracking APIs.

## Architecture Decision: Address Management

Delivery addresses are owned by the **profile** microservice, not the shop or delivery service.

- **Profile service** owns the `delivery_address` table and exposes CRUD APIs
- **Shop** accepts an inline address snapshot in `PlaceOrderRequest` (gathered by the UI from the profile service)
- **`customer_order` stores the address as a snapshot** (no FK to an address table)
- **OrderPlaced event** includes the full address snapshot
- **Delivery service** receives the address from the OrderPlaced event

This avoids synchronous coupling between shop and profile during the checkout flow.

### Checkout flow

```
UI ──GET──► Profile (list saved addresses)
UI ◄──addresses── Profile
UI ──POST──► Shop (order + inline address snapshot)
     Shop ──emit──► Kafka (OrderPlaced with address)
```

---

## Event Flow

```
                                          ┌──────────────────┐
                                          │  Shop (checkout) │
                                          │  order placed    │
                                          └──────┬───────────┘
                                                 │ emits
                                                 ▼
                                        Kafka (orders.events)
                                        OrderPlaced {orderId,
                                        items, total, address}
                                                 │
                          ┌──────────────────────┼──────────────────────┐
                          ▼                      ▼                      ▼
              ┌────────────────────┐  ┌────────────────────┐  ┌────────────────────┐
              │  Payment service   │  │ Delivery service   │  │ (future consumers) │
              │  (creates intent)  │  │ (stores address    │  └────────────────────┘
              └────────┬───────────┘  │  for future use)   │
                       │              └────────┬───────────┘
                       ▼                       │
              Kafka (payments.events)           │
              PaymentSucceeded                  │
                       │                       │
                       ▼                       ▼
              ┌────────────────────┐  ┌────────────────────┐
              │  Shop (updates     │  │ Delivery service   │
              │  order→PAID)       │  │ (starts simulation)│
              └────────────────────┘  └────────┬───────────┘
                                               │ emits
                                               ▼
                                      Kafka (delivery.events)
                                      DeliveryDispatched,
                                      DeliveryDelivered
                                               │
                                               ▼
                                      ┌────────────────────┐
                                      │  Shop (updates     │
                                      │  order→SHIPPED,    │
                                      │  DELIVERED)        │
                                      └────────────────────┘
```

| Consumed topic | Events | Action |
|---|---|---|
| `orders.events` | `OrderPlaced` | Store address + order metadata |
| `payments.events` | `PaymentSucceeded` (status=SUCCEEDED) | Start delivery simulation (PREPARING → ... → DELIVERED) |

| Emitted topic | Events | Consumer |
|---|---|---|
| `delivery.events` | `DeliveryDispatched`, `DeliveryDelivered` | Shop (updates order status → SHIPPED / DELIVERED) |

---

## Domain Model

### DeliveryStatus enum

| Status | Meaning |
|---|---|
| `PENDING` | Payment not yet received |
| `PREPARING` | Payment confirmed, packing order |
| `DISPATCHED` | Handed to carrier |
| `IN_TRANSIT` | In transit to destination |
| `DELIVERED` | Delivered successfully |
| `FAILED` | Delivery failed |

Valid transitions:
```
PENDING    → PREPARING, FAILED
PREPARING  → DISPATCHED, FAILED
DISPATCHED → IN_TRANSIT, FAILED
IN_TRANSIT → DELIVERED, FAILED
DELIVERED  → (terminal)
FAILED     → (terminal)
```

### Delivery entity

| Field | Type | Notes |
|---|---|---|
| `id` | `UUID` | Primary key |
| `orderId` | `Long` | Unique index |
| `status` | `DeliveryStatus` (→ `String`) | Default `PENDING` |
| `recipientName` | `String` | Copied from OrderPlaced |
| `addressLine1` | `String` | |
| `addressLine2` | `String` | nullable |
| `city` | `String` | |
| `state` | `String` | nullable |
| `zipCode` | `String` | |
| `country` | `String` | |
| `estimatedDeliveryDate` | `Instant` | Set on DISPATCHED |
| `createdAt` | `Instant` | |
| `updatedAt` | `Instant` | |

### DeliveryEvent entity (outbox)

Reuses the existing `OutboxEvent` pattern from payment/shop (same fields: `aggregateType`, `aggregateId`, `eventType`, `payload`, `status`).

### DeliveryTracking (optional, for timeline view)

| Field | Type |
|---|---|
| `id` | `UUID` |
| `deliveryId` | `UUID` |
| `status` | `DeliveryStatus` |
| `timestamp` | `Instant` |
| `description` | `String` |

---

## REST APIs

All proxied through the gateway at `/api/delivery/**`.

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `GET` | `/api/delivery/{orderId}` | JWT | Get delivery status + address for an order |
| `GET` | `/api/delivery/{orderId}/tracking` | JWT | Get delivery tracking timeline |

### GET /api/delivery/{orderId}

Response:
```json
{
  "id": "uuid",
  "orderId": 42,
  "status": "PREPARING",
  "recipientName": "Alice",
  "addressLine1": "123 Main St",
  "city": "Springfield",
  "zipCode": "12345",
  "country": "US",
  "estimatedDeliveryDate": "2026-07-08T12:00:00Z",
  "createdAt": "2026-07-05T10:00:00Z"
}
```

Returns `404` if no delivery record exists yet.

### GET /api/delivery/{orderId}/tracking

Response:
```json
{
  "deliveryId": "uuid",
  "orderId": 42,
  "currentStatus": "IN_TRANSIT",
  "estimatedDelivery": "2026-07-08T12:00:00Z",
  "events": [
    { "status": "PREPARING", "timestamp": "2026-07-05T10:05:00Z", "description": "Payment confirmed, order being prepared" },
    { "status": "DISPATCHED", "timestamp": "2026-07-05T12:00:00Z", "description": "Handed to carrier" },
    { "status": "IN_TRANSIT", "timestamp": "2026-07-06T08:00:00Z", "description": "In transit to destination" }
  ]
}
```

---

## OrderPlaced Event Payload (with address)

The `OrderPlaced` outbox payload now includes the address snapshot:

```json
{
  "orderId": 1,
  "username": "alice",
  "items": [...],
  "total": 159.98,
  "orderedAt": "2026-07-05T10:00:00Z",
  "address": {
    "recipientName": "Alice",
    "addressLine1": "123 Main St",
    "city": "Springfield",
    "zipCode": "12345",
    "country": "US"
  }
}
```

The address is **not** stored as columns on `customer_order` — it's embedded directly in the event payload. The shop stores it as a JSON column or ignores it and relies on the event.

---

## Shop Changes (minimal)

The shop no longer manages delivery addresses. It just accepts an inline address snapshot and forwards it.

### Modify `PlaceOrderRequest`

```java
public record PlaceOrderRequest(
    List<LineItem> items,
    @Schema(description = "Delivery address snapshot") DeliveryAddress address
) {
    public record LineItem(Long productId, int quantity) {}
    public record DeliveryAddress(
        String recipientName,
        String addressLine1,
        String addressLine2,
        String city,
        String state,
        String zipCode,
        String country
    ) {}
}
```

The `address` field is required (every order needs a delivery destination).

### Modify `OrderService.placeOrder`

Include `address` in the `OrderPlaced` payload's `buildPayload()` method. No DB changes to `customer_order` — no new columns, no FK.

### Modify `OrderResponse`

Optionally include the delivery address (populated from the order creation context). The shop doesn't persist the address in its own DB — it only sends it in the outbox event. If the OrderResponse needs the address, store it as a JSON column or a separate `order_address` table snapshot.

---

## Implementation Steps (Delivery Service)

### Step 1 — Update `build.gradle.kts`

Add missing dependencies: `spring-security-oauth2-resource-server`, `spring-jdbc` (for Liquibase).

### Step 2 — Scaffold package structure

```
delivery/src/main/java/org/granitesecurity/delivery/
├── DeliveryApplication.java
├── domain/
│   ├── Delivery.java (implements Persistable<UUID>)
│   ├── DeliveryStatus.java
│   ├── DeliveryEvent.java (outbox event)
│   └── DeliveryTracking.java
├── repository/
│   ├── DeliveryRepository.java
│   ├── DeliveryEventRepository.java (outbox)
│   └── DeliveryTrackingRepository.java
├── service/
│   ├── DeliveryService.java (business logic)
│   └── DeliverySimulator.java (simulated progress transitions)
├── consumer/
│   ├── OrderEventConsumer.java (listens to orders.events → stores address)
│   └── PaymentEventConsumer.java (listens to payments.events → starts sim)
├── handler/
│   └── DeliveryHandler.java (functional endpoints)
├── route/
│   └── DeliveryRoute.java (RouterFunction)
├── security/
│   └── DeliverySec.java (SecurityWebFilterChain)
├── relay/
│   └── OutboxRelay.java (same pattern as payment/shop)
└── config/
    └── DeliveryConfig.java (optional: scheduling, etc.)
```

### Step 3 — Database: Liquibase changelogs

```sql
CREATE TABLE delivery (
    id UUID PRIMARY KEY,
    order_id BIGINT NOT NULL UNIQUE,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    recipient_name VARCHAR(255),
    address_line1 VARCHAR(255),
    address_line2 VARCHAR(255),
    city VARCHAR(128),
    state VARCHAR(64),
    zip_code VARCHAR(16),
    country VARCHAR(64),
    estimated_delivery_date TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE delivery_event (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE delivery_tracking (
    id UUID PRIMARY KEY,
    delivery_id UUID NOT NULL REFERENCES delivery(id),
    status VARCHAR(32) NOT NULL,
    timestamp TIMESTAMPTZ NOT NULL,
    description VARCHAR(512)
);
```

### Step 4 — Domain entities

`Delivery`, `DeliveryStatus`, `DeliveryEvent` (outbox), `DeliveryTracking`.

### Step 5 — OrderEventConsumer

Listens to `orders.events`, consumer group `delivery.orders.events.consumer`. On `OrderPlaced`, extracts the address from the payload and stores it in the delivery record. Creates delivery record in `PENDING` status with the address.

### Step 6 — PaymentEventConsumer

Listens to `payments.events`, consumer group `delivery.payments.events.consumer`. On `PaymentSucceeded`, finds the delivery record (created by OrderEventConsumer in step 5) and transitions it to `PREPARING`, triggering the simulation chain.

If no delivery record exists yet (OrderEventConsumer hasn't processed yet), creates one in `PREPARING` (address will be filled in when OrderPlaced arrives).

### Step 7 — DeliverySimulator

`Mono.delay` chain: PREPARING → DISPATCHED (~20s) → IN_TRANSIT (~40s) → DELIVERED (~60s). Each transition writes an outbox event and a tracking record.

### Step 8 — OutboxRelay

Same pattern as `payment/relay/OutboxRelay.java` — polls `delivery_event` table, publishes to `delivery.events` Kafka topic.

### Step 9 — REST handlers

`DeliveryHandler` with functional endpoints:
- `getDeliveryByOrderId(ServerRequest) → ServerResponse`
- `getTracking(ServerRequest) → ServerResponse`

`DeliveryRoute`:
```java
GET("/api/delivery/{orderId}", handler::getDeliveryByOrderId)
GET("/api/delivery/{orderId}/tracking", handler::getTracking)
```

### Step 10 — Security: DeliverySec

```java
.authorizeExchange(auth -> auth
    .pathMatchers("/api/delivery/**").authenticated()
    .anyExchange().permitAll()
)
.oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
```

### Step 11 — Gateway route

Add `delivery-service` route in `RouterConfig.java`:
```java
@Value("${microservices.delivery.uri:http://localhost:8063}")
private String deliveryServiceUri;

.route("delivery-service", r -> r
    .path("/api/delivery/**")
    .uri(deliveryServiceUri))
```

### Step 12 — Docker Compose

Already has `delivery-postgres`. Add `delivery` service:
- Port 8063
- DB env vars, Kafka bootstrap, auth issuer
- Depends on delivery-postgres, gateway

### Step 13 — Shop: Add `delivery.events` listener

Modify `EventConsumer.java` to listen to `delivery.events`:

| Event | Action |
|---|---|
| `DeliveryDispatched` (status=DISPATCHED) | `orderService.updateOrderStatus(orderId, SHIPPED)` |
| `DeliveryDelivered` (status=DELIVERED) | `orderService.updateOrderStatus(orderId, DELIVERED)` |

### Step 14 — UI: Delivery info in OrderDetail

Show delivery status, address, and tracking timeline on the OrderDetail page below the order items.

---

## Implementation Order

### Phase 1 — Profile microservice
1. Scaffold profile module (build.gradle.kts, Dockerfile, structure)
2. Database changelog (user_profile + delivery_address tables)
3. Domain entities + repositories
4. Profile service (get/update profile, auto-create from JWT)
5. Address service (CRUD for saved addresses)
6. REST handlers + routes
7. Security config
8. Gateway route + Docker Compose

### Phase 2 — Shop changes
9. Modify `PlaceOrderRequest` to accept inline address
10. Modify `OrderService.buildPayload` to include address in OrderPlaced event
11. Modify `OrderResponse` to optionally include delivery address
12. Update checkout UI to fetch addresses from profile service

### Phase 3 — Delivery service
13. Build delivery service scaffold, domain, DB
14. Kafka consumers (orders.events + payments.events)
15. Delivery simulation
16. Outbox relay
17. REST handlers
18. Gateway route
19. Shop delivery.events listener

---

## Files to create / modify

| File | Action |
|---|---|
| `profile/...` (various) | Create per `user-service.md` plan |
| `shop/.../dto/PlaceOrderRequest.java` | Modify (add address field, remove addressId/NewAddress) |
| `shop/.../service/OrderService.java` | Modify (include address in OrderPlaced payload) |
| `shop/.../dto/OrderResponse.java` | Modify (optionally add deliveryAddress) |
| `ui-shop/src/types.ts` | Modify (add address types, update PlaceOrderRequest) |
| `ui-shop/src/api.ts` | Modify (add profile/address API calls) |
| `ui-shop/src/pages/Checkout.tsx` | Modify (load addresses from profile, show picker) |
| `ui-shop/src/pages/OrderDetail.tsx` | Modify (show delivery info) |
| `delivery/build.gradle.kts` | Modify (add missing deps) |
| `delivery/src/main/java/.../DeliveryApplication.java` | Create |
| `delivery/src/main/java/.../domain/Delivery.java` | Create |
| `delivery/src/main/java/.../domain/DeliveryStatus.java` | Create |
| `delivery/src/main/java/.../domain/DeliveryEvent.java` | Create |
| `delivery/src/main/java/.../domain/DeliveryTracking.java` | Create |
| `delivery/src/main/java/.../repository/DeliveryRepository.java` | Create |
| `delivery/src/main/java/.../repository/DeliveryEventRepository.java` | Create |
| `delivery/src/main/java/.../repository/DeliveryTrackingRepository.java` | Create |
| `delivery/src/main/java/.../service/DeliveryService.java` | Create |
| `delivery/src/main/java/.../service/DeliverySimulator.java` | Create |
| `delivery/src/main/java/.../consumer/OrderEventConsumer.java` | Create |
| `delivery/src/main/java/.../consumer/PaymentEventConsumer.java` | Create |
| `delivery/src/main/java/.../handler/DeliveryHandler.java` | Create |
| `delivery/src/main/java/.../route/DeliveryRoute.java` | Create |
| `delivery/src/main/java/.../security/DeliverySec.java` | Create |
| `delivery/src/main/java/.../relay/OutboxRelay.java` | Create |
| `delivery/src/main/java/.../config/DeliveryConfig.java` | Create |
| `delivery/src/main/resources/application.yaml` | Create |
| `delivery/src/main/resources/db/changelog/001_create_delivery_tables.sql` | Create |
| `gateway/.../config/RouterConfig.java` | Modify (add delivery + profile routes) |
| `shop/.../consumer/EventConsumer.java` | Modify (add delivery.events listener) |
| `compose.yaml` | Modify (add profile service + DB, add delivery service) |
| `events.md` | Already up-to-date |
