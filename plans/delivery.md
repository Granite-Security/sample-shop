# Delivery Microservice — Plan

New microservice that manages delivery operations: shipping provider integration, delivery webhooks, tracking, and internal delivery progression simulation.

## Shipping Provider Model

Deliveries are assigned to **shipping providers** (carriers). The system supports multiple providers and can receive status updates from them via webhooks.

| Provider | Identifier | Webhook support |
|---|---|---|
| Internal simulator | `internal` | No (simulated) |
| FedEx | `fedex` | Yes |
| UPS | `ups` | Yes |
| DHL | `dhl` | Yes |

A configurable **provider selection strategy** determines which provider handles a delivery:
- `auto` — pick the next provider in round-robin (dev default)
- `carrier:{name}` — explicitly assign to a specific provider (configured per merchant or order)

---

## Address Ownership: Profile as Source of Truth

Delivery addresses live in **two forms** with different ownership:

| Form | Owned by | Stored where | Purpose |
|---|---|---|---|
| **Saved addresses** (address book) | Profile service | `profile.delivery_address` table | User's reusable addresses, CRUD from UI |
| **Address snapshot** (at order time) | Shop order + Delivery record | `shop.customer_order` columns + `delivery` table | Immutable record of where the package was actually sent |

The **profile microservice** is the canonical store for saved addresses — it's where the user manages their address book. When an order is placed, the UI picks a saved address (or enters a new one) and sends a **copy** to the shop, which embeds it in the `OrderPlaced` event. The delivery service later reads that snapshot from the event and stores it on its own `delivery` record so it can generate shipping labels and show tracking info without calling back to the profile service.

This avoids synchronous coupling between shop and profile during checkout, while keeping the profile service as the place users manage their addresses.

### Checkout flow

```
User manages addresses ──► Profile service (saved addresses)
                               │
UI ──GET──► Profile (list saved addresses)
UI ◄──addresses── Profile
UI ──POST──► Shop (order + inline address snapshot)
     Shop ──emit──► Kafka (OrderPlaced with address snapshot)
                                                        │
                                                        ▼
                                              Delivery service
                                              (stores snapshot on its own
                                               delivery record for tracking)
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
              │  order→PAID)       │  │ (start PREPARING)  │
              └────────────────────┘  └────────┬───────────┘
                                               │ assigns provider
                                               ▼
                                    ┌──────────────────────┐
                                    │ Delivery simulation  │
                                    │ or ShipmentRequest   │
                                    └──────────┬───────────┘
                                               │
                          ┌────────────────────┼────────────────────┐
                          ▼                    ▼                    ▼
                  ┌──────────────┐   ┌──────────────┐   ┌──────────────────┐
                  │ Internal sim │   │ FedEx/UP/DHL │   │ Webhook callback │
                  │ (Mono.delay) │   │ (mock call)  │   │ from provider    │
                  └──────┬───────┘   └──────┬───────┘   └────────┬─────────┘
                         │                  │                    │
                         └──────────────────┼────────────────────┘
                                            ▼
                                   ┌────────────────────┐
                                   │ Delivery status    │
                                   │ transition         │
                                   └────────┬───────────┘
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
| `payments.events` | `PaymentSucceeded` (status=SUCCEEDED) | Start delivery (PREPARING → assign provider) |

| Emitted topic | Events | Consumer |
|---|---|---|
| `delivery.events` | `DeliveryDispatched`, `DeliveryDelivered` | Shop (updates order status → SHIPPED / DELIVERED) |

---

## Inbound Webhook API (Shipping Providers → Delivery Service)

Shipping providers push status updates to the delivery service via webhooks. This is how external carriers report real-world delivery progress.

### Endpoint

```
POST /api/delivery/webhook/shipping-provider
```

### Authentication (OAuth2 Client Credentials)

External carriers are registered as **OAuth2 clients** in the auth-server with a `client_credentials` grant type and `SCOPE_delivery_webhook`. They get a `client_id` + `client_secret`, call the auth-server's token endpoint, and present the resulting access token to the webhook.

**Client registration (auth-server `SecurityConfig.java`):**

```java
RegisteredClient carrierFedEx = RegisteredClient.withId(UUID.randomUUID().toString())
    .clientId("fedex")
    .clientSecret("{noop}fedex-webhook-secret")
    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
    .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
    .scope("delivery_webhook")
    .build();
```

**Webhook request:**

```
POST /api/delivery/webhook/shipping-provider
Authorization: Bearer {access_token_from_auth_server}
X-Provider: fedex
X-Idempotency-Key: {unique_event_id}
```

The delivery service validates the JWT as usual via `oauth2ResourceServer` and checks `hasAuthority("SCOPE_delivery_webhook")`. The `X-Provider` header identifies the carrier (matched against the JWT's `sub` or `azp` claim).

No custom API key management needed — the auth-server handles credentials, token issuance, and expiry uniformly.

### Request Payload

```json
{
  "trackingNumber": "FDX-1234567890",
  "event": "picked_up" | "in_transit" | "out_for_delivery" | "delivered" | "failed",
  "timestamp": "2026-07-06T08:00:00Z",
  "description": "Package picked up at origin facility",
  "location": "Memphis, TN"
}
```

### Status Mapping

External provider events are mapped to internal delivery statuses:

| Provider event | Delivery status |
|---|---|
| `picked_up` | `DISPATCHED` |
| `in_transit` | `IN_TRANSIT` |
| `out_for_delivery` | `IN_TRANSIT` |
| `delivered` | `DELIVERED` |
| `failed` | `FAILED` |

### Response

```json
{
  "status": "accepted",
  "deliveryId": "uuid",
  "currentStatus": "IN_TRANSIT"
}
```

Returns `401` on missing/invalid JWT, `403` if missing `SCOPE_delivery_webhook`, `404` if tracking number unknown, `409` on idempotent duplicate.

### Idempotency

The `X-Idempotency-Key` header ensures the same event is not processed twice. The delivery service stores processed idempotency keys per provider for at least 24 hours.

---

## Outbound API (Delivery Service → Shipping Provider)

When a delivery is ready for dispatch (`PREPARING` → `DISPATCHED`), the delivery service can optionally call out to the assigned provider's API to register the shipment.

This is **simulated** in development (mock response with a generated tracking number). In production, this would be a real HTTP call to the carrier's shipment creation API.

### Mock provider call

When transitioning to `DISPATCHED`, the delivery service simulates calling the provider:

1. Generates a tracking number: `{provider-abbrev}-{delivery-id-prefix}`
2. Stores the tracking number on the delivery record
3. Transitions to `DISPATCHED`

### Future provider integration (not implemented)

Each provider would need:
- A `ShipmentRequest` DTO (from → to → package dimensions → service level)
- An HTTP client call to the provider's API
- Response parsing to extract the provider tracking number

```
POST https://api.fedex.com/ship/v1/shipments
Authorization: Bearer {fedex_oauth_token}
{
  "from": { "warehouse address" },
  "to": { "delivery address from OrderPlaced" },
  "packages": [ { "weight": "1.5kg" } ],
  "service": "GROUND"
}
```

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

The delivery record stores a **snapshot** of the address (copied from the `OrderPlaced` event, which itself was copied from the profile service at checkout time). This is not a reference back to the profile service — the address is frozen at order time so it never changes even if the user later updates their profile address book.

| Field | Type | Notes |
|---|---|---|
| `id` | `UUID` | Primary key |
| `orderId` | `Long` | Unique index |
| `status` | `DeliveryStatus` (→ `String`) | Default `PENDING` |
| `provider` | `String` | e.g. `internal`, `fedex`, `ups` |
| `trackingNumber` | `String` | nullable, set on DISPATCHED |
| `recipientName` | `String` | Snapshot from OrderPlaced event |
| `addressLine1` | `String` | Snapshot from OrderPlaced event |
| `addressLine2` | `String` | nullable, snapshot |
| `city` | `String` | Snapshot from OrderPlaced event |
| `state` | `String` | nullable, snapshot |
| `zipCode` | `String` | Snapshot from OrderPlaced event |
| `country` | `String` | Snapshot from OrderPlaced event |
| `estimatedDeliveryDate` | `Instant` | Set on DISPATCHED |
| `createdAt` | `Instant` | |
| `updatedAt` | `Instant` | |

### ShippingProvider entity

| Field | Type | Notes |
|---|---|---|
| `id` | `UUID` | Primary key |
| `name` | `String` | Unique identifier, matches OAuth2 client `sub`/`azp` |
| `displayName` | `String` | Human-readable, e.g. `FedEx` |
| `enabled` | `boolean` | Whether this provider is active |
| `createdAt` | `Instant` | |

Auth is handled by the auth-server (not stored here). The provider's OAuth2 `client_id` matches this entity's `name`.

Seeded with `internal` (for simulation) and optionally `fedex`, `ups`, `dhl` for future use.

### DeliveryEvent entity (outbox)

Reuses the existing `OutboxEvent` pattern from payment/shop (same fields: `aggregateType`, `aggregateId`, `eventType`, `payload`, `status`).

### DeliveryTracking entity (timeline)

| Field | Type | Notes |
|---|---|---|
| `id` | `UUID` | Primary key |
| `deliveryId` | `UUID` | FK to delivery |
| `status` | `DeliveryStatus` | |
| `timestamp` | `Instant` | |
| `description` | `String` | Human-readable event description |

### WebhookEvent entity (idempotency)

| Field | Type | Notes |
|---|---|---|
| `id` | `UUID` | Primary key |
| `provider` | `String` | e.g. `fedex` |
| `idempotencyKey` | `String` | Unique per provider |
| `receivedAt` | `Instant` | |
| `processingStatus` | `String` | `PROCESSED` / `FAILED` |

---

## REST APIs

All proxied through the gateway at `/api/delivery/**`.

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `GET` | `/api/delivery/{orderId}` | JWT | Get delivery status + address for an order |
| `GET` | `/api/delivery/{orderId}/tracking` | JWT | Get delivery tracking timeline |
| `POST` | `/api/delivery/webhook/shipping-provider` | JWT (client credentials, `SCOPE_delivery_webhook`) | Receive delivery status updates from shipping providers |

### GET /api/delivery/{orderId}

Returns the delivery details including the **address snapshot** (stored on the delivery record, not fetched live from the profile service). The address is the same as what was entered at checkout.

Response:
```json
{
  "id": "uuid",
  "orderId": 42,
  "status": "PREPARING",
  "provider": "fedex",
  "trackingNumber": "FDX-a1b2c3",
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
  "provider": "fedex",
  "trackingNumber": "FDX-a1b2c3",
  "estimatedDelivery": "2026-07-08T12:00:00Z",
  "events": [
    { "status": "PREPARING", "timestamp": "2026-07-05T10:05:00Z", "description": "Payment confirmed, order being prepared" },
    { "status": "DISPATCHED", "timestamp": "2026-07-05T12:00:00Z", "description": "Handed to FedEx" },
    { "status": "IN_TRANSIT", "timestamp": "2026-07-06T08:00:00Z", "description": "In transit to destination" }
  ]
}
```

### POST /api/delivery/webhook/shipping-provider

Authenticated with a JWT obtained via client credentials grant (see [Authentication section](#authentication-oauth2-client-credentials)).

Request:
```json
{
  "trackingNumber": "FDX-1234567890",
  "event": "in_transit",
  "timestamp": "2026-07-06T08:00:00Z",
  "description": "Package arrived at sorting facility",
  "location": "Memphis, TN"
}
```

Response:
```json
{
  "status": "accepted",
  "deliveryId": "uuid",
  "currentStatus": "IN_TRANSIT"
}
```

---

## OrderPlaced Event Payload (with address)

The `OrderPlaced` outbox payload includes the address as a **snapshot** — it was copied from the profile service at checkout time and is now frozen for this order. Even if the user later changes their saved address in the profile service, this event retains the original delivery destination.

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

The delivery service reads this snapshot from the event and stores it on its own `delivery` record — no call to the profile service is needed at delivery time.

---

## Delivery State Machine (with Provider)

```
       PaymentSucceeded         assign provider         webhook: picked_up
PENDING ─────────────────► PREPARING ─────────────────► DISPATCHED ─────────────► IN_TRANSIT
  │                            │                            │                        │
  │ (FAILED)                   │ (FAILED)                   │ (FAILED)               │ (webhook: delivered
  ▼                            ▼                            ▼                        │  or sim completes)
FAILED ◄───────────────────────┴────────────────────────────┴────────────────────────┘
                                                                                    │
                                                                                    ▼
                                                                               DELIVERED
```

---

## Simulated vs. Webhook-driven flow

In development (`internal` provider), the delivery simulator drives all status transitions via `Mono.delay`:

| Time | Event | Delivery status |
|---|---|---|
| 0s (PaymentSucceeded) | Start simulation | `PREPARING` |
| +20s | Simulate dispatch | `DISPATCHED` (tracking: `INT-{id}`) |
| +40s | Simulate in transit | `IN_TRANSIT` |
| +60s | Simulate delivery | `DELIVERED` |

When integrated with a real shipping provider, the provider's webhook callbacks drive the same transitions. The `internal` provider is the default for development; real providers are configured via `application.yaml`:

```yaml
delivery:
  providers:
    default: internal
    fedex:
      enabled: false
    ups:
      enabled: false
```

---

## Provider Selection Strategy

Configurable via `delivery.providers.selection-strategy`:

| Strategy | Behavior |
|---|---|
| `auto` | All deliveries handled by `internal` simulator (dev default) |
| `carrier:{name}` | Assign to a specific provider, e.g. `carrier:fedex` |

In production, this could be extended to:
- Weight-based routing (light → USPS, heavy → FedEx)
- Geographic routing
- Merchant-specific provider configuration

---

## Implementation Steps (Delivery Service)

### Step 1 — Scaffold

- `build.gradle.kts` (WebFlux, R2DBC, OAuth2 resource server, Liquibase, Kafka, PostgreSQL)
- `Dockerfile`
- `settings.gradle.kts`

### Step 2 — Package structure

```
delivery/src/main/java/org/granitesecurity/delivery/
├── DeliveryApplication.java
├── domain/
│   ├── Delivery.java (implements Persistable<UUID>)
│   ├── DeliveryStatus.java
│   ├── DeliveryEvent.java (outbox)
│   ├── DeliveryTracking.java
│   ├── ShippingProvider.java
│   └── WebhookEvent.java (idempotency)
├── repository/
│   ├── DeliveryRepository.java
│   ├── DeliveryEventRepository.java
│   ├── DeliveryTrackingRepository.java
│   ├── ShippingProviderRepository.java
│   └── WebhookEventRepository.java
├── service/
│   ├── DeliveryService.java (business logic)
│   ├── DeliverySimulator.java (simulated transitions)
│   └── WebhookService.java (validate + process webhooks)
├── consumer/
│   ├── OrderEventConsumer.java (orders.events → store address)
│   └── PaymentEventConsumer.java (payments.events → start delivery)
├── handler/
│   ├── DeliveryHandler.java (functional endpoints)
│   └── WebhookHandler.java (webhook endpoint)
├── route/
│   └── DeliveryRoute.java (RouterFunction)
├── security/
│   └── DeliverySec.java (SecurityWebFilterChain)
├── relay/
│   └── OutboxRelay.java (same pattern as payment/shop)
└── config/
    └── DeliveryConfig.java (provider settings, etc.)
```

### Step 3 — Database: Liquibase changelogs

```sql
CREATE TABLE delivery (
    id UUID PRIMARY KEY,
    order_id BIGINT NOT NULL UNIQUE,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    provider VARCHAR(64),
    tracking_number VARCHAR(128),
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

CREATE TABLE shipping_provider (
    id UUID PRIMARY KEY,
    name VARCHAR(64) NOT NULL UNIQUE,
    display_name VARCHAR(128) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE webhook_event (
    id UUID PRIMARY KEY,
    provider VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(256) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processing_status VARCHAR(32) NOT NULL DEFAULT 'PROCESSED',
    UNIQUE (provider, idempotency_key)
);
```

### Step 4 — Seed shipping providers

```sql
INSERT INTO shipping_provider (id, name, display_name, enabled)
VALUES
    (gen_random_uuid(), 'internal', 'Internal Simulator', true),
    (gen_random_uuid(), 'fedex',    'FedEx',    false),
    (gen_random_uuid(), 'ups',      'UPS',      false),
    (gen_random_uuid(), 'dhl',      'DHL',      false);
```

### Step 5 — Domain entities

`Delivery`, `DeliveryStatus`, `DeliveryEvent`, `DeliveryTracking`, `ShippingProvider`, `WebhookEvent`.

### Step 6 — OrderEventConsumer

Listens to `orders.events`. On `OrderPlaced`, extracts the address **snapshot** from the payload (the address was originally gathered from the **profile** service by the UI at checkout, then passed through the shop into the event) and creates a delivery record in `PENDING` status with the address.

### Step 7 — PaymentEventConsumer

Listens to `payments.events`. On `PaymentSucceeded`, finds the delivery record, transitions to `PREPARING`, assigns the configured provider (default `internal`), and triggers the simulation (or creates a shipment request).

### Step 8 — DeliverySimulator

For `internal` provider: `Mono.delay` chain simulating DISPATCHED → IN_TRANSIT → DELIVERED with tracking records and outbox events.

### Step 9 — WebhookService + WebhookHandler

- `WebhookService`: resolve provider from JWT `sub`/`azp` claim (mapped via `ShippingProviderRepository`), check idempotency, map external event to delivery status, update delivery + create tracking record + outbox event. No custom API key validation — JWT auth is handled by Spring Security before the handler runs.
- `WebhookHandler`: functional endpoint for `POST /api/delivery/webhook/shipping-provider`. Reads `X-Provider` header and `X-Idempotency-Key` header from request, delegates to `WebhookService`.

### Step 10 — OutboxRelay

Same pattern as `payment/relay/OutboxRelay.java` — polls `delivery_event` table, publishes to `delivery.events` Kafka topic.

### Step 11 — REST handlers

`DeliveryHandler`:
- `getDeliveryByOrderId` → `GET /api/delivery/{orderId}`
- `getTracking` → `GET /api/delivery/{orderId}/tracking`

`DeliveryRoute`:
```java
GET("/api/delivery/{orderId}", handler::getDeliveryByOrderId)
GET("/api/delivery/{orderId}/tracking", handler::getTracking)
POST("/api/delivery/webhook/shipping-provider", webhookHandler::handleWebhook)
```

### Step 12 — Security: DeliverySec

```java
.authorizeExchange(auth -> auth
    .pathMatchers(HttpMethod.POST, "/api/delivery/webhook/**").hasAuthority("SCOPE_delivery_webhook")
    .pathMatchers("/api/delivery/**").authenticated()
    .anyExchange().permitAll()
)
.oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
```

The webhook endpoint requires `SCOPE_delivery_webhook` — a scope issued by the auth-server to carrier OAuth2 clients via the client credentials grant. Non-webhook endpoints (user-facing) require any valid user JWT.

The webhook endpoint is **not** `permitAll` — it uses the same JWT infrastructure as everything else. Carriers authenticate through the auth-server like any other client.

### Step 13 — Gateway route

Add `delivery-service` route in `RouterConfig.java`:
```java
@Value("${microservices.delivery.uri:http://localhost:8063}")
private String deliveryServiceUri;

.route("delivery-service", r -> r
    .path("/api/delivery/**")
    .uri(deliveryServiceUri))
```

### Step 14 — Docker Compose

Add `delivery` service:
- Port 8063
- DB env vars, Kafka bootstrap, auth issuer
- Delivery provider config (default: internal)
- Depends on delivery-postgres, gateway

### Step 15 — Shop: Add `delivery.events` listener

Modify `EventConsumer.java` to listen to `delivery.events`:

| Event | Action |
|---|---|
| `DeliveryDispatched` (status=DISPATCHED) | `orderService.updateOrderStatus(orderId, SHIPPED)` |
| `DeliveryDelivered` (status=DELIVERED) | `orderService.updateOrderStatus(orderId, DELIVERED)` |

### Step 16 — UI: Delivery info in OrderDetail

Show delivery status, provider, tracking number, and tracking timeline on the OrderDetail page.

---

---

## Address Data Flow (End-to-End)

```
              ┌──────────────────────────────────────────────────────────┐
              │                    PROFILE SERVICE                      │
              │  delivery_address (saved address book, user-managed)    │
              │  user_profile (email, name)                             │
              └────────────────────┬─────────────────────────────────────┘
                                   │ UI fetches saved addresses at checkout
                                   ▼
              ┌──────────────────────────────────────────────────────────┐
              │                      UI (Checkout)                      │
              │  User selects a saved address OR enters a new one       │
              │  Address snapshot sent with PlaceOrderRequest           │
              └────────────────────┬─────────────────────────────────────┘
                                   │
                                   ▼
              ┌──────────────────────────────────────────────────────────┐
              │                     SHOP SERVICE                        │
              │  Stores address snapshot on customer_order columns      │
              │  Embeds full address in OrderPlaced Kafka event         │
              └────────────────────┬─────────────────────────────────────┘
                                   │
                                   ▼  Kafka (orders.events)
              ┌──────────────────────────────────────────────────────────┐
              │                   DELIVERY SERVICE                      │
              │  OrderEventConsumer: reads address snapshot from event  │
              │  Stores snapshot on delivery record (for shipping labels│
              │  and tracking display)                                  │
              │  NEVER calls profile service at delivery time           │
              └──────────────────────────────────────────────────────────┘
```

The profile service is the **authoritative source** for address book management. The delivery service is a **consumer** of an address snapshot — it never writes back to the profile service, and never reads from it at runtime.

---

## Implementation Order

### Phase 1 — Done
- Profile microservice
- Shop changes (address in PlaceOrderRequest, buildPayload, OrderResponse)
- UI: Address management, checkout address picker, OrderDetail address display

### Phase 2 — Delivery service
1. Scaffold module (build.gradle.kts, Dockerfile, structure)
2. Database changelog + seed providers
3. Domain entities + repositories
4. OrderEventConsumer (store address from OrderPlaced)
5. PaymentEventConsumer (start delivery on PaymentSucceeded)
6. DeliverySimulator (internal provider, Mono.delay transitions)
7. OutboxRelay (publish delivery.events)
8. WebhookService + WebhookHandler (shipping provider callbacks)
9. REST handlers (get delivery, get tracking)
10. Security config
11. Gateway route + Docker Compose
12. Shop delivery.events listener
13. UI: delivery info + tracking on OrderDetail

---

## Files to create / modify

| File | Action |
|---|---|
| `delivery/` (various) | Create all (build.gradle.kts, Dockerfile, settings.gradle.kts, all Java sources, resources) |
| `gateway/.../config/RouterConfig.java` | Modify (add delivery route) |
| `shop/.../consumer/EventConsumer.java` | Modify (add delivery.events listener) |
| `ui-shop/src/pages/OrderDetail.tsx` | Modify (show delivery info, tracking) |
| `ui-shop/src/api.ts` | Modify (add delivery API calls) |
| `ui-shop/src/types.ts` | Modify (add delivery types) |
| `compose.yaml` | Modify (add delivery service + DB) |
