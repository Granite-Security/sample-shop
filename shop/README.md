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

The destination is the outbox row's own `topic` column (added in `009`, defaulting
to `orders.events`), so one relay feeds both topics. Placing an order writes **two**
rows in the same transaction: `OrderPlaced` for payment and delivery, and
`OrderPlacedNotice` for profile, which turns it into an in-app message to admin.
That second row is deliberately not `REQUIRES_NEW` or `NESTED` — a notice that can
commit while the order rolls back would announce an order that does not exist.

| Topic | Direction | Event | Fields that matter |
|-------|-----------|-------|--------------------|
| `orders.events` | out | `OrderPlaced` | `eventType`, `orderId` (also the Kafka key), `provider` — which payment provider must handle it, never null; `packaging[]` + `packagingTotal` (empty when nothing needed a box); `voucherCode` + `discountPercent` + `discountTotal` (null/zero when no voucher) |
| `orders.events` | out | `RefundRequested` | `orderId`, `eventType`, `discountTotal` |
| `orders.events` | out | `OrdersPurged` | `orderIds` (plural), `eventType`; keyed by **username**, not order id |
| `shop.notifications` | out | `OrderPlacedNotice` | `username` — that is the whole point of it; `orderId` and `occurredAt` are for profile's dedupe and staleness guard, not for display |
| `payments.events` | in | — | `orderId`, `status` → `SUCCEEDED`→PAID, `FAILED`→PAYMENT_FAILED, `REFUNDED`→REIMBURSED |
| `delivery.events` | in | — | `orderId`, `status` → `DISPATCHED`→SHIPPED, `DELIVERED`→DELIVERED |

**Every event on `orders.events` carries `eventType`, including `OrderPlaced`.**
It was untagged until consumers reached it by falling through the branches for
the tagged types — which meant any type they did not know was processed as an
order and logged as a malformed one. Consumers still treat an absent `eventType`
as `OrderPlaced` so nothing published before the change is lost; that tolerance
comes out once no untagged message can be in flight or PENDING in the outbox.

`OrderPlaced.packaging` names the group and option by **code**, not by id, and
carries frozen `unitPrice` and `unitCost` per box (`docs/packaging/packaging.md`
D42). Codes because an event outlives the row it points at, and the cost because
a free box still costs us something — delivery is where accounting expenses it,
and asking shop later would make the report depend on a live service.

`total` on `OrderPlaced` is what is **payable**, already net of any voucher
discount — which is why adding vouchers changed nothing in payment, balance or
delivery (`docs/finance/vouchers.md` V4). `discountTotal` is carried alongside so
accounting can credit revenue at the list price and debit the discount to `4300`,
keeping the sale visible at what it was priced at rather than at what was paid.
`RefundRequested` carries it for the same reason: the reversal has to unwind that
leg too, or a refunded discounted order leaves contra-revenue standing against a
sale that no longer exists.

`OrdersPurged` is keyed by username while everything else is keyed by order id,
so it is not ordered against that user's own `OrderPlaced`.

`shipments.events` also has a listener, but nothing in this platform publishes
that topic.

Transitions are validated against a whitelist in `OrderStatus`; an illegal one
is rejected, not silently applied.

## Catalogue data, and what survives a namespace delete

The catalogue is Liquibase-seeded, so a rebuilt cluster comes back with the real
products: `002` (generic + Food & Sweets), `019` (the SI Chocolate range),
`016`/`017`/`018` (packaging and which products need it).

`019` exists because the eight placeholders `005` seeded were later renamed and
repriced in place through the admin UI. Liquibase had no idea — `005` is recorded
as applied — so a rebuild would have restored the placeholder names and silently
lost every real product. **Anything created or edited through the admin UI lives
only in the running database until a changelog captures it.** Before deleting the
namespace, diff what is live against the seeds:

```bash
kubectl -n granite exec -i deploy/postgres-shop -- psql -U myuser -d shopdb   -c "SELECT id, name, price, stock, packaging_group_id FROM product ORDER BY id"
```

**Product images are not covered by any of this.** `product.media` holds URLs on
`media.granite-security.org`, served by garage from `garage-pvc` — a `local-path`
volume whose reclaim policy is `Delete`. Deleting the namespace destroys the
photographs, and the seeded rows come back pointing at URLs that 404. Back the
bucket up separately first.

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
