# Packaging — implementation plan

**Status:** built, steps 1–9. Unverified against the cluster — §5 is the check list.
All of it lives in `shop` (plus the two SPAs and one accounting decision). No new microservice.

---

## 1. The model

| Concept | Where |
|---|---|
| Does this product need packaging? | `product.packaging_group_id IS NOT NULL` |
| What can share a box with it? | its `packaging_group` |
| What packaging can it go in, and how many fit? | `packaging_group_option(group, option, capacity)` |
| Which packagings exist? | rows in `packaging_option` — today `FREE` and `PREMIUM` |
| What did this order get? | `order_packaging` rows, one per group, frozen price and cost |

### Packing rule

```
for each packaging group g present in the cart:
    units(g)    = Σ quantity of lines whose product is in g
    packages(g) = ceil( units(g) / capacity(g, chosen option) )
    charge(g)   = packages(g) × price(chosen option)

packaging_total = Σ charge(g)      # products with no group contribute nothing
order.total     = Σ line totals + packaging_total
```

One option chosen per group. No bin-packing optimiser. The server computes this; the client
sends ids only.

---

## 2. Decisions

| # | Decision |
|---|---|
| D36 | One nullable `product.packaging_group_id`. "Requires packaging" is `IS NOT NULL`. |
| D37 | Packaging compatibility is its own axis, never `category`. |
| D38 | Packaging options are table rows. Retire with `active = false`, never DELETE. |
| D39 | Capacity lives on the `(group, option)` pair. |
| D40 | `ceil(units / capacity)`, no optimiser. |
| D41 | Choice is per group in the data; the UI asks once when the cart has one group. |
| D42 | `order_packaging` rows with frozen `unit_price`/`unit_cost`. No item→box assignment stored. |
| D43 | The server prices packaging. Client numbers are never trusted. |
| D44 | Packaging is not a distinct performance obligation: the charge is part of the goods' price, the box cost is a fulfilment cost expensed at delivery. |
| D45 | Box stock is not tracked. |
| D46 | No new microservice. |

Free is a price, not a state: `FREE` is an ordinary row with `price = 0.00` and a real
`unit_cost`. A cart of truffles always needs a box; with `FREE` that box adds nothing to the
total. `FREE` is pre-selected.

---

## 3. Steps

Each step is one PR off `main`.

### Step 1 — schema and seed (`shop`) — built

Three changelogs plus three `include` entries in `db.changelog-master.yaml`. House pattern:
`--liquibase formatted sql`, `--preconditions onFail:MARK_RAN`, a `--rollback` per changeset.

**`015-add-packaging.sql`**

```sql
CREATE TABLE packaging_group (
    id          BIGSERIAL    PRIMARY KEY,
    code        VARCHAR(64)  NOT NULL UNIQUE,
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE packaging_option (
    id          BIGSERIAL     PRIMARY KEY,
    code        VARCHAR(64)   NOT NULL UNIQUE,
    name        VARCHAR(255)  NOT NULL,
    description TEXT,
    price       NUMERIC(10,2) NOT NULL,
    unit_cost   NUMERIC(10,2) NOT NULL,
    image_url   VARCHAR(512),
    active      BOOLEAN       NOT NULL DEFAULT true,
    sort_order  INTEGER       NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT packaging_option_price_nonneg CHECK (price >= 0),
    CONSTRAINT packaging_option_cost_nonneg  CHECK (unit_cost >= 0)
);

CREATE TABLE packaging_group_option (
    packaging_group_id  BIGINT  NOT NULL REFERENCES packaging_group(id),
    packaging_option_id BIGINT  NOT NULL REFERENCES packaging_option(id),
    capacity            INTEGER NOT NULL,
    PRIMARY KEY (packaging_group_id, packaging_option_id),
    CONSTRAINT packaging_capacity_positive CHECK (capacity > 0)
);

ALTER TABLE product ADD COLUMN packaging_group_id BIGINT REFERENCES packaging_group(id);
CREATE INDEX idx_product_packaging_group_id ON product(packaging_group_id);

ALTER TABLE customer_order ADD COLUMN packaging_total NUMERIC(10,2) NOT NULL DEFAULT 0;

CREATE TABLE order_packaging (
    id                  BIGSERIAL     PRIMARY KEY,
    order_id            BIGINT        NOT NULL REFERENCES customer_order(id),
    packaging_group_id  BIGINT        NOT NULL REFERENCES packaging_group(id),
    packaging_option_id BIGINT        NOT NULL REFERENCES packaging_option(id),
    quantity            INTEGER       NOT NULL,
    unit_price          NUMERIC(10,2) NOT NULL,
    unit_cost           NUMERIC(10,2) NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT order_packaging_qty_positive CHECK (quantity > 0),
    CONSTRAINT order_packaging_one_per_group UNIQUE (order_id, packaging_group_id)
);
CREATE INDEX idx_order_packaging_order_id ON order_packaging(order_id);
```

**`016-seed-packaging.sql`** — one group, two options:

| option `code` | name | price | unit_cost | sort_order |
|---|---|---|---|---|
| `FREE` | Plain box | 0.00 | 0.40 | 0 |
| `PREMIUM` | Premium gift box | 6.00 | 2.20 | 1 |

| group | option | capacity |
|---|---|---|
| `TRUFFLE` | `FREE` | 12 |
| `TRUFFLE` | `PREMIUM` | 12 |

**`017-assign-truffle-packaging.sql`** — explicit name list, not a `LIKE '%Truffle%'`:

```sql
UPDATE product SET packaging_group_id = (SELECT id FROM packaging_group WHERE code = 'TRUFFLE'),
                   updated_at = now()
WHERE name IN ('Sea Salt Caramel Truffles',
               'White Chocolate Truffles',
               'Pistachio & Rose Praline');
```

Everything else stays NULL. `Truffle Collection Box` and `The Signature Gift Box` are already
packaged and must not be caught by a pattern match.

The list is explicit in both directions, which is the whole argument against deriving membership
from the name: `Truffle Collection Box` contains "Truffle" and needs no box, while `Espresso
Ganache Collection` contains neither "truffle" nor "praline" and is one. No pattern gets both
right, and the cost of being wrong is either a box around a box or a loose piece shipped
unprotected.

**`017` matched almost nothing in the live catalogue** — its names came from
`005-seed-choco-products.sql`, but the SI Chocolate range was created through the admin UI with
entirely different ones, and the single match sits in a category the sichocolate storefront
filters out. The result was a storefront where no truffle needed a box and the picker never
appeared. Corrected by `018-assign-si-truffles.sql`. Check the assignment against the real
`product` rows, not against a seed changelog:

```sql
SELECT id, name, packaging_group_id FROM product ORDER BY id;
```

Built as `015-add-packaging.sql`, `016-seed-packaging.sql` and
`017-assign-truffle-packaging.sql`, each changeset guarded and with a `--rollback`.
Revise these three freely **before** they are first applied. After that Liquibase checksums them
and `shop` refuses to start if they change — see §6.

---

### Step 2 — domain and repositories (`shop`) — built

`domain/`: `PackagingGroup`, `PackagingOption`, `PackagingGroupOption`, `OrderPackaging` —
`@Table`, `@Data`, `@Column` for snake_case, matching `Product`/`OrderItem`.

`PackagingGroupOption` has a composite key: no `@Id`, so give it its own
`PackagingGroupOptionRepository` with explicit `@Query` reads and no `save()`. Rows come from
Liquibase and the admin endpoint (step 7).

`repository/`: `PackagingGroupRepository`, `PackagingOptionRepository`,
`PackagingGroupOptionRepository`, `OrderPackagingRepository`. Queries needed:

- active options for a set of group ids, with capacity — one join, one round trip
- `findByOrderId` for order responses

---

### Step 3 — `PackagingService` (`shop`) — built

One class, one public entry point used by both the quote endpoint and `OrderService`:

```java
Mono<PackagingPlan> plan(Map<Long, Product> products, List<LineItem> lines,
                         List<PackagingChoice> choices)   // choices null → quote mode
```

Returns, per group present in the cart: group, units, and every compatible active option with
its capacity, package count and total. In choice mode it also resolves the selected option per
group and the `packaging_total`.

Rules:
- products with `packaging_group_id IS NULL` are skipped entirely
- no group in the cart → empty plan, `packaging_total = 0`
- a group with no active compatible option → error naming the group code (data fault, not a
  shopper fault)
- `BigDecimal`, `setScale(2, HALF_UP)` on every total

---

### Step 4 — quote endpoint (`shop`) — built

`POST /api/shop/packaging/quote`, authenticated. `PackagingHandler` + `ShopRoute` entry +
`ShopSec` `.pathMatchers(HttpMethod.POST, "/api/shop/packaging/quote").authenticated()`.

Request — the cart, not stored:

```json
{ "items": [ { "productId": 7, "quantity": 13 } ] }
```

Response:

```json
{
  "packagingRequired": true,
  "currency": "CHF",
  "groups": [
    {
      "groupId": 1, "code": "TRUFFLE", "name": "Truffles", "units": 13,
      "options": [
        { "optionId": 1, "code": "FREE",    "name": "Plain box",        "capacity": 12,
          "packages": 2, "unitPrice": "0.00", "total": "0.00", "default": true },
        { "optionId": 2, "code": "PREMIUM", "name": "Premium gift box", "capacity": 12,
          "packages": 2, "unitPrice": "6.00", "total": "12.00", "default": false }
      ]
    }
  ]
}
```

`packagingRequired: false` with `groups: []` for a cart that needs nothing. `default: true` is
the lowest `sort_order` active option — the server decides what "shopper did nothing" means.

---

### Step 5 — checkout (`shop`) — built

`PlaceOrderRequest` gains an optional component, added via a second constructor delegating to
the canonical one (same move `CreateProductRequest` made for `unitCost`):

```java
List<PackagingChoice> packaging   // record PackagingChoice(Long groupId, Long optionId)
```

`OrderService.validateAndBuild` calls `PackagingService.plan(...)` and rejects:

| Condition | Result |
|---|---|
| cart has a group with no choice | 400, naming the group |
| choice for a group not in the cart | 400 |
| option not allowed for that group | 400 |
| option inactive | 400 |
| two choices for one group | 400 |
| no packaged products but `packaging` sent | ignored |

Then, in the existing transaction: add `packaging_total` to `customer_order.total`, set
`customer_order.packaging_total`, insert `order_packaging` rows with `unit_price`/`unit_cost`
copied from the option at this moment.

`OrderResponse` gains `packagingTotal` and a `packaging` list. `payment` is untouched — it reads
`total` and `currency`.

---

### Step 6 — `OrderPlaced` (`shop`) — built

`OrderService.buildPayload` gains, alongside `items`:

```json
"packaging": [
  { "groupCode": "TRUFFLE", "optionCode": "PREMIUM",
    "quantity": 2, "unitPrice": "6.00", "unitCost": "2.20" }
]
```

Absent or empty when the order needed none. Frozen values (D26). Consumers read the payload as a
map and branch on `eventType`, so `payment`, `delivery` and `profile` need no change.

---

### Step 7 — admin (`shop`) — built

- `CreateProductRequest` gains nullable `packagingGroupId`; null on update means "leave alone"
  (as with `discontinued`). `ProductResponse` exposes it.
- CRUD under `/api/shop/admin/packaging/groups`, `/options`, `/groups/{id}/options`
  (capacity upsert/delete), all `ROLE_ADMIN` in `ShopSec` — the gateway guards nothing.
- Deactivating an option must refuse if it is the last active option for any group.

---

### Step 8 — checkout UI (`ui-shop`, then `ui-demo`) — built

Step between cart and address:

1. `POST /api/shop/packaging/quote` with the cart
2. `packagingRequired: false` → render nothing, skip
3. one group → one row of option cards, `FREE` pre-selected
   ("Plain box · 2 boxes · included" / "Premium gift box · 2 boxes · CHF 12.00")
4. several groups → one row per group, labelled with the group name
5. send `{groupId, optionId}` pairs in `PlaceOrderRequest.packaging`
6. show the order response's `total` as authoritative if it differs from the quote

---

### Step 9 — accounting — doc written; the service consumes it after its steps 3–10

`order_packaging.unit_cost × quantity` is a fulfilment cost, expensed at delivery alongside COGS
and the CHF 1.00 shipping. The packaging charge is part of goods revenue — no new revenue
account.

Written up in `accounting.md` as §2.11, D44 and a fifth row in the §2.8 cost table. Nothing in
the `accounting` service reads `OrderPlaced.packaging` yet; it lands there with its step 5.

`FREE` is why `unit_cost` is stored and not derived: it charges 0.00 and costs 0.40.

---

## 4. Invariants

- `SUM(order_packaging.quantity × unit_price) = customer_order.packaging_total` per order
- `customer_order.total = SUM(order_item.quantity × unit_price) + packaging_total`
- every `(group, option)` on an order exists in `packaging_group_option`
- no product sits in a group with zero active options

---

## 5. Verify

Manually against the cluster. `kubectl config current-context` first. SQL checks run through:

```bash
kubectl -n granite exec -it deploy/postgres-shop -- psql -U myuser -d shopdb
```

HTTP checks go through the gateway with a bearer token from the SPA login.

| # | After | Check | Expect |
|---|---|---|---|
| 1 | 1 | `\d product`, `\d order_packaging` | columns and constraints present |
| 2 | 1 | `SELECT name, packaging_group_id FROM product ORDER BY 1` | every truffle **actually on sale** is set — not just the names in `017`; `Truffle Collection Box` NULL |
| 3 | 1 | `SELECT code, price, unit_cost, active FROM packaging_option` | `FREE` 0.00/0.40, `PREMIUM` 6.00/2.20, both active |
| 4 | 4 | quote a cart of bars only | `packagingRequired: false`, `groups: []` |
| 5 | 4 | quote 13 truffles | both options, `packages: 2`; `FREE` total 0.00 and `default: true`; `PREMIUM` total 12.00 |
| 6 | 5 | order a truffle with no `packaging` | 400 naming the group |
| 7 | 5 | order with `PREMIUM` against a group that doesn't allow it | 400 |
| 8 | 5 | order 13 truffles with `FREE` | total = items only; one `order_packaging` row, `unit_price` 0.00, `unit_cost` 0.40 |
| 9 | 5 | same order with `PREMIUM` | total = items + 12.00 |
| 10 | 5 | change `PREMIUM` price, re-read the old order | old total and `unit_price` unchanged |
| 11 | 6 | `kubectl -n granite port-forward deploy/kafka-ui 8090:8080`, read `orders.events` | `packaging` array with frozen values |
| 12 | 6 | `kubectl -n granite logs deploy/payment deploy/delivery` | no deserialisation errors, order processed |
| 13 | 5 | §4 invariant queries across all orders | zero rows |

Check 10 is the one a code read cannot prove — the freeze only fails on the second read, after a
price change.

---

## 6. Iterating on the changelogs, and resetting

No step forces a reset. Liquibase only errors when an **already-applied** changeset is edited,
so the whole question is confined to step 1 — steps 2–9 add no DDL. It is also scoped to
`shopdb`: shop has its own `postgres-shop` Deployment and `postgres-shop-pvc`, so nothing here
touches auth, payment, delivery, profile or notification.

Confirm the context before every block below:

```bash
kubectl config current-context
```

### The loop while `015`–`017` are still moving

Changelogs are baked into the image, so editing a `.sql` file changes nothing in the cluster
until CI has built and pushed it — and `imagePullPolicy: Always` does **not** restart a pod on a
new `:latest`. Full cycle:

```bash
# 1. edit shop/src/main/resources/db/changelog/01[567]-*.sql, commit, push
#    wait for CI to push moldovean/granite-shop:latest

# 2. undo the packaging changesets so the edited ones can run again
kubectl -n granite scale deploy/shop --replicas=0
kubectl -n granite exec -i deploy/postgres-shop -- psql -U myuser -d shopdb <<'SQL'
ALTER TABLE product        DROP COLUMN IF EXISTS packaging_group_id;
ALTER TABLE customer_order DROP COLUMN IF EXISTS packaging_total;
DROP TABLE IF EXISTS order_packaging, packaging_group_option, packaging_option, packaging_group;
DELETE FROM databasechangelog WHERE filename LIKE '%packaging%';
SQL

# 3. bring it back on the new image
kubectl -n granite scale deploy/shop --replicas=1
kubectl -n granite rollout status deploy/shop
kubectl -n granite logs -f deploy/shop | grep -i liquibase
```

Orders, products and every other table survive this. Scale to 0 first so nothing writes to a
half-dropped schema. Before the `DELETE`, check what it will match:

```bash
kubectl -n granite exec -it deploy/postgres-shop -- psql -U myuser -d shopdb \
  -c "SELECT id, filename FROM databasechangelog ORDER BY orderexecuted DESC LIMIT 10"
```

To inspect the schema at any point:

```bash
kubectl -n granite exec -it deploy/postgres-shop -- psql -U myuser -d shopdb -c '\d order_packaging'
```

### Once the schema is settled

Prices, costs and capacities are **data**. Change them without touching `015`–`017`:

```bash
kubectl -n granite exec -it deploy/postgres-shop -- psql -U myuser -d shopdb \
  -c "UPDATE packaging_option SET price = 7.00, updated_at = now() WHERE code = 'PREMIUM'"
```

Make it permanent as an UPDATE in `018`, or through the step-7 admin endpoint once it exists.
No restart, no downtime. This is the normal path after step 1 has landed.

### Wider resets, if it comes to that

**`shopdb` only** — loses orders and products; the catalogue reseeds from `002`/`005`/`011`,
order history does not come back:

```bash
kubectl -n granite scale deploy/shop --replicas=0
kubectl -n granite delete deploy/postgres-shop pvc/postgres-shop-pvc
kubectl apply -k k8s/hetzner/app-multi
kubectl -n granite rollout status deploy/postgres-shop
kubectl -n granite scale deploy/shop --replicas=1
```

**The namespace** — `kubectl delete namespace granite`, then reapply the overlay. Only worth it
when several services' schemas are being redone at once: it rebuilds every database and Kafka,
and auth-server generates a fresh RSA key pair on startup, so every existing JWT is dead and
every session is logged out.

`spring.liquibase.clear-checksums=true` exists and accepts an edited file without re-running it.
Safe only for comment edits, never SQL — the database keeps the old schema while the file claims
the new one.

---

## 7. Not building

Box stock; dimensions and weight (shipping is flat CHF 1.00); bin-packing optimisation; per-box
personalisation (gift messages, engraving); packaging sold as a product. A `FIGURINE` group for
the chocolate rabbit is one `packaging_group` row plus its capacities — no code change.
