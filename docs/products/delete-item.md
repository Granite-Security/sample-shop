# Product Delete: Archive (soft) + conditional permanent delete

Status: **not started** · Last updated: 2026-07-27

## Problem

Deleting a product that has been ordered fails with a 500: `order_item` has a
foreign key to `product(id)` (`001-create-schema.sql:43`) with no cascade,
PostgreSQL rejects the `DELETE`, and `CatalogService.deleteProduct`
(`CatalogService.java:130`) is a bare `deleteById` with no error handling — so
the constraint violation surfaces as an unhandled 500.

## Solution

Two admin actions:

- **Archive (soft delete)** — sets `archived_at`; hidden from the storefront,
  visible in an admin "Archived" view, reversible. This is the primary action
  and the answer for any product that has ever been ordered.
- **Delete permanently** — only allowed when the product has **no** `order_item`
  rows. Otherwise the API returns **409** telling the admin to archive instead.

### Why not `ON DELETE CASCADE`

The obvious fix — cascading the delete into `order_item` — would destroy order
history. `order_item` stores only `product_id`, `quantity` and `unit_price`
(`001-create-schema.sql:40-47`); there is **no product name snapshot**, and
`OrderService.resolveProductNames` (`OrderService.java:300`) joins live `product`
rows to render past orders, falling back to `"Unknown"`
(`OrderService.java:295`).

Cascading therefore deletes the order lines themselves: a completed order keeps
its `customer_order.total` but loses every line that justifies it — €42.00 and
nothing in it. That corrupts the customer's history, the refund path, and
accounting, and it reaches across services, since payment and delivery hold
records keyed to those orders. No admin should be handed that button.

**So the FK stays `RESTRICT` (unchanged) and hard delete becomes conditional.**
This still fixes the reported 500 — the failure just becomes a clear 409 instead
of a crash.

> If ordered products must one day be permanently removable, the correct form is
> a snapshot, not a cascade: add `product_name VARCHAR(255)` to `order_item`,
> backfill it from `product`, make `product_id` nullable and use
> `ON DELETE SET NULL`. Order history then survives intact but detached. Out of
> scope here; recorded so the cascade idea doesn't come back.

## Database changes

### `008-add-product-archived-at.sql`

```sql
--liquibase formatted sql

--changeset moldo:008-add-product-archived-at
ALTER TABLE product ADD COLUMN archived_at TIMESTAMPTZ NULL;
CREATE INDEX idx_product_archived_at ON product(archived_at) WHERE archived_at IS NOT NULL;
--rollback DROP INDEX idx_product_archived_at; ALTER TABLE product DROP COLUMN archived_at;
```

Register in `db.changelog-master.yaml` after `007-add-product-media.sql`.

**No second changelog** — the FK is deliberately left alone.

Note the Liquibase header/changeset/rollback lines: every changelog in this repo
has all three, and Liquibase rejects a `.sql` file without the
`--liquibase formatted sql` header.

## Backend changes (`shop`)

### Repository (`ProductRepository.java`)

The archive filter must go on the queries the storefront **actually** uses.
Listing does not use a derived query — it's a raw `@Query`
(`ProductRepository.java:12`) plus `productRepository.count()`
(`CatalogService.java:81`). Both need it, or pagination totals disagree with the
rows returned.

```java
@Query("SELECT * FROM product WHERE archived_at IS NULL ORDER BY id LIMIT :size OFFSET :offset")
Flux<Product> findAllPaged(@Param("size") int size, @Param("offset") long offset);

@Query("SELECT COUNT(*) FROM product WHERE archived_at IS NULL")
Mono<Long> countActive();

@Query("SELECT * FROM product WHERE archived_at IS NOT NULL ORDER BY archived_at DESC LIMIT :size OFFSET :offset")
Flux<Product> findArchivedPaged(@Param("size") int size, @Param("offset") long offset);

@Query("SELECT COUNT(*) FROM product WHERE archived_at IS NOT NULL")
Mono<Long> countArchived();

Flux<Product> findByCategoryIdAndArchivedAtIsNull(Long categoryId);   // replaces findByCategoryId
```

### `OrderItemRepository.java`

```java
Mono<Long> countByProductId(Long productId);   // gate for permanent delete
```

### Service (`CatalogService.java`)

| Method | Behaviour |
| --- | --- |
| `getAllProducts(page, size)` | swap `count()` → `countActive()`; `findAllPaged` now filters |
| `getArchivedProducts(page, size)` | new; `findArchivedPaged` + `countArchived()` |
| `getProductsByCategory(id)` | use the `AndArchivedAtIsNull` variant |
| `getProduct(id)` | keep returning archived products (admin edit + order history need it); the storefront simply never links to one. Expose `archivedAt` so the UI can badge it |
| `archiveProduct(id)` | load, 404 if missing, set `archivedAt = now()` + `updatedAt`, save. Idempotent — archiving an archived product is a no-op, not an error |
| `restoreProduct(id)` | same, `archivedAt = null` |
| `deleteProduct(id)` | **rewritten** (see below) |

`deleteProduct` replaces the bare `deleteById`:

1. `productRepository.findById(id)` → 404 `ShopException` if missing (matches
   `getProduct`'s existing style).
2. `orderItemRepository.countByProductId(id)` → if `> 0`, throw
   `new ShopException("Product '" + name + "' appears in " + n + " order(s) and cannot be "
   + "deleted. Archive it instead.", HttpStatus.CONFLICT, "Conflict")`.
   `ShopException` already carries a status + title and `GlobalErrorHandler`
   (`GlobalErrorHandler.java:30`) renders it as a `ProblemDetail` — no new error
   plumbing.
3. `productRepository.deleteById(id)`.
4. **Then** delete the product's media objects from storage (below), using the
   `media` value read in step 1.

Order matters: the row goes first. If media cleanup ran first and the row delete
then failed — a concurrent order landing between the count and the delete — the
product would still exist and still be sellable, with its images stripped.
Deleting the row first means the keys are already unreferenced when cleanup runs,
and a cleanup failure leaves recoverable orphans instead of a broken product.

Keep the FK violation handled as a backstop: if that concurrent order does land,
map `DataIntegrityViolationException` to the same 409 rather than letting it
become a 500. Media is untouched in that path.

### Media cleanup

`product.media` (`007-add-product-media.sql`) holds a JSON array of `MediaItem`
(`key`, `url`, `contentType`, `isDefault`) — one entry per uploaded image, so a
product with five pictures needs five `DELETE /api/storage/objects` calls
(storage's `DeleteObjectRequest` takes a single key). A permanent delete that
skips this orphans them forever; nothing else references those keys.

**Drive deletion off `media[].key` only — never `image_url`.** That column is a
free-text "Fallback image URL" field in the admin form
(`ui-shop/src/pages/ProductForm.tsx:148`) and the seed products point it at
`picsum.photos` (`002-seed-products.sql:38`). There is no storage key behind it,
and it may reference a third party's URL. The `media` array is the only
authoritative record of the objects we own.

**Archive deletes nothing.** The row keeps `media` and `image_url` intact so
restore brings the product back whole.

`shop` has no storage client today, so it becomes the third **`internal-service`
client** alongside `profile` and `auth-server` — see
[`../users/user-profile.md`](../users/user-profile.md) Phase 3b for the shared
registered client, how an internal token differs from a user token, and the
401-retry gotcha. Copy `InternalClientConfig` from `profile` verbatim; only the
base URL differs.

New in `shop`:

- `build.gradle.kts`: `spring-boot-starter-oauth2-client`.
- `config/InternalClientConfig.java` — `ReactiveOAuth2AuthorizedClientManager`
  (client-credentials) + a `WebClient` pointed at `${microservices.storage.uri}`,
  including the evict-and-retry-once on 401 (auth-server regenerates its signing
  key on every restart, which invalidates cached internal tokens).
- `client/StorageClient.java` — `Mono<Void> delete(String key)` →
  `DELETE /api/storage/objects`.
- Config/env: `MICROSERVICES_STORAGE_URI`, `INTERNAL_CLIENT_ID`,
  `INTERNAL_CLIENT_SECRET` in `application.yaml`, `compose.yaml` and the k8s
  config/secret — same keys the `profile` plan adds.

Note `storage`'s presign/delete rules are `hasAnyRole("ADMIN","MANAGER")` plus
`SCOPE_internal` after that plan's Phase 3a; shop's cleanup calls arrive as
`SCOPE_internal`, and the existing key-prefix guard already restricts deletes to
allowed scope prefixes, so `products/…` keys are covered with no further change.

**Sequencing: land the profile plan's Phase 3b first**, or ship this delete
without cleanup and open a follow-up. Don't invent a second mechanism.

Media deletion must not fail the request: log a warning and proceed. An orphaned
object is recoverable; a product row that won't delete is a stuck admin.

### Checkout must reject archived products

`OrderService.validateAndBuild` looks products up with `findAllById`
(`OrderService.java:65`), and ui-shop carts are **client-side** — a user can hold
an archived product in their cart and order it. Add the check next to the
existing stock check (`OrderService.java:81`):

```java
if (product.getArchivedAt() != null) {
    return Mono.error(new ShopException("Product is no longer available: " + product.getName()));
}
```

This is the gap most likely to be missed; it's the whole point of archiving.

### Handler (`CatalogHandler.java`)

`archiveProduct` / `restoreProduct` → 204. `getArchivedProducts` → 200 with
`PagedResult<ProductResponse>`. `deleteProduct` keeps its 204 and gains a
documented 409 in its `@ApiResponses`.

### Route (`ShopRoute.java`)

| Endpoint | Method | Action |
| --- | --- | --- |
| `/api/shop/products/archived` | GET | Archived list (admin) |
| `/api/shop/products/{id}/archive` | PUT | Soft delete |
| `/api/shop/products/{id}/restore` | PUT | Restore |
| `/api/shop/products/{id}` | DELETE | Permanent delete — 409 if ordered |

**No `/permanent` endpoint.** The existing `DELETE /api/shop/products/{id}`
becomes the conditional hard delete; making it safe is the fix, and it keeps the
surface one route smaller.

**Ordering matters:** `/api/shop/products/archived` must be registered **before**
`/api/shop/products/{id}`, or `{id}` swallows `"archived"` and
`Long.valueOf("archived")` throws. `ShopRoute.java:141` already carries exactly
this comment for `/orders/all` — mirror it.

Add matching `@RouterOperation` entries; this service documents every route in
springdoc and the annotations are the only reason the OpenAPI page is accurate.

### Security (`ShopSec.java`)

`ShopSec.java:55-57` makes **all** GET `/api/shop/products/**` permitAll — so
`/api/shop/products/archived` would be public unless a rule precedes it:

```java
.pathMatchers(HttpMethod.GET, "/api/shop/products/archived").hasAnyRole("ADMIN", "MANAGER")
// ...existing permitAll for the rest of GET /api/shop/products/**
```

`/archive` and `/restore` are PUTs and already inherit
`PUT /api/shop/products/**` → `hasRole("ADMIN")` (`ShopSec.java:67`); `DELETE`
likewise (`ShopSec.java:69`).

One deliberate choice to make: today POST products is `ADMIN|MANAGER` while
PUT/DELETE are `ADMIN`-only. Archiving is closer to an edit than a deletion, so
**allow MANAGER to archive/restore** but keep permanent delete ADMIN-only. That
needs its own rule before the blanket PUT rule:

```java
.pathMatchers(HttpMethod.PUT, "/api/shop/products/*/archive",
        "/api/shop/products/*/restore").hasAnyRole("ADMIN", "MANAGER")
```

### Domain & DTO

- `Product.java` — `@Column("archived_at") private Instant archivedAt;`
- `ProductResponse.java` — add `archivedAt` (with its `@Schema` annotation, as
  every other component has). This churns `toProductResponse` and every test
  constructing the record — same constructor-churn lesson as the refund and
  media features.

## Frontend changes (`ui-shop`)

- `api/catalog.ts` — `getArchivedProducts()`, `archiveProduct(id)`,
  `restoreProduct(id)`; `deleteProduct(id)` stays but must surface the 409 body's
  `detail` verbatim rather than a generic failure toast.
- `ProductsManagement.tsx` — "Active" / "Archived" tabs; Restore per archived row.
- Per-product actions: **Archive** (primary) and **Delete permanently**
  (destructive, confirmation modal). New modal text, since the old one described
  the cascade that no longer exists:
  > "Permanently delete this product? This cannot be undone. Products that have
  > been ordered cannot be deleted — archive them instead."
- Storefront (`Catalog.tsx`, `ProductDetail.tsx`) needs no change: the API no
  longer returns archived products. Check that a stale client-side cart entry
  renders the checkout 400 legibly.

## Tests

| Level | What |
| --- | --- |
| `CatalogServiceTest` | archive sets/clears `archivedAt`; delete with 0 order items succeeds; delete with N > 0 throws 409 `ShopException`; 404 on missing id |
| `OrderServiceTest` | ordering an archived product fails with the "no longer available" message |
| Repository (Testcontainers) | `countByProductId`; `findAllPaged`/`countActive` exclude archived; `findArchivedPaged` includes only archived |
| Manual | archive → gone from storefront, visible in admin tab → restore → back; delete an ordered product → 409 with a readable message; delete a never-ordered one → 204 and its media gone from Garage |

## Files to modify

| File | Action |
| --- | --- |
| `shop/src/main/resources/db/changelog/008-add-product-archived-at.sql` | Create |
| `shop/src/main/resources/db/changelog/db.changelog-master.yaml` | Edit |
| `shop/src/main/java/.../domain/Product.java` | Edit |
| `shop/src/main/java/.../dto/ProductResponse.java` | Edit |
| `shop/src/main/java/.../repository/ProductRepository.java` | Edit |
| `shop/src/main/java/.../repository/OrderItemRepository.java` | Edit |
| `shop/src/main/java/.../service/CatalogService.java` | Edit |
| `shop/src/main/java/.../service/OrderService.java` | Edit (archived check at checkout) |
| `shop/src/main/java/.../handler/CatalogHandler.java` | Edit |
| `shop/src/main/java/.../route/ShopRoute.java` | Edit |
| `shop/src/main/java/.../security/ShopSec.java` | Edit |
| `shop/src/main/java/.../client/StorageClient.java` | Create (media cleanup; see Phase 3b of the profile plan) |
| `ui-shop/src/api/catalog.ts`, `src/pages/ProductsManagement.tsx` | Edit |
