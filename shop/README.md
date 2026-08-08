# shop

Port **8061**. Catalog and orders, WebFlux + R2DBC over `shopdb`. Owns the
order lifecycle: it is the only service that writes `OrderStatus`.

## API

`ShopRoute` (functional routing); rules in `ShopSec`.

| Endpoint | Auth |
|----------|------|
| `GET /api/shop/products`, `/products/{id}`, `/categories` | public |
| `POST /api/shop/orders` | authenticated |
| `GET /api/shop/orders` | authenticated — own orders only |
| `GET /api/shop/orders/{id}` | authenticated |
| `GET /api/shop/orders/all` | ADMIN, MANAGER |
| `POST /api/shop/orders/{id}/refund` | authenticated (admin: any paid order; user: own, failed delivery only) |
| `GET /api/shop/users/{username}/orders` | ADMIN |
| `POST`/`PUT`/`DELETE /api/shop/products`, `/categories` | POST: ADMIN, MANAGER — PUT/DELETE: ADMIN |
| `/api/shop/internal/**` | `SCOPE_internal` — service-to-service, no user |

Two ordering traps in the router, both commented in place: `/orders/all` is
registered before `/orders/{id}`, and orders-by-username lives under a separate
`/users/{username}/orders` root so the segment can't shadow an order id.

`POST /orders` returns immediately with the order in `PENDING`. Payment is not
awaited — the response carries the `orderId` the client then polls payment with.
The only synchronous cross-service call is outbound: `PaymentClient` asks
payment for statuses when deciding purge eligibility.

## Events

Produced through a **transactional outbox** — the order row and the event row
commit together, then `OutboxRelay` polls (`app.outbox.poll-interval`, 5s) and
publishes. Nothing is sent from the request path.

| Topic | Direction | Event | Fields that matter |
|-------|-----------|-------|--------------------|
| `orders.events` | out | `OrderPlaced` | `eventType`, `orderId` (also the Kafka key), `provider` — which payment provider must handle it, never null |
| `orders.events` | out | `RefundRequested` | `orderId`, `eventType` |
| `orders.events` | out | `OrdersPurged` | `orderIds` (plural), `eventType`; keyed by **username**, not order id |
| `payments.events` | in | — | `orderId`, `status` → `SUCCEEDED`→PAID, `FAILED`→PAYMENT_FAILED, `REFUNDED`→REIMBURSED |
| `delivery.events` | in | — | `orderId`, `status` → `DISPATCHED`→SHIPPED, `DELIVERED`→DELIVERED |

**Every event on `orders.events` carries `eventType`, including `OrderPlaced`.**
It was untagged until consumers reached it by falling through the branches for
the tagged types — which meant any type they did not know was processed as an
order and logged as a malformed one. Consumers still treat an absent `eventType`
as `OrderPlaced` so nothing published before the change is lost; that tolerance
comes out once no untagged message can be in flight or PENDING in the outbox.

`OrdersPurged` is keyed by username while everything else is keyed by order id,
so it is not ordered against that user's own `OrderPlaced`.

`shipments.events` also has a listener, but nothing in this platform publishes
that topic.

Transitions are validated against a whitelist in `OrderStatus`; an illegal one
is rejected, not silently applied.

## Configuration

| Variable | Purpose |
|----------|---------|
| `SHOP_R2DBC_URL` / `_USERNAME` / `_PASSWORD` | Runtime DB access (`SHOP_JDBC_*` is Liquibase only) |
| `KAFKA_BOOTSTRAP_SERVERS` | Broker |
| `PAYMENT_SERVICE_URI` | Target of the one outbound call |
| `INTERNAL_CLIENT_ID` / `_SECRET`, `AUTH_SERVER_TOKEN_URI` | Mints the `internal` scope token for that call — straight to auth-server, not via the gateway |
| `JWT_JWK_SET_URI`, `TRUSTED_JWT_ISSUERS` | Token validation (see greetings) |

```bash
./gradlew bootRun
./gradlew test          # repository tests need Docker (Testcontainers)
```
