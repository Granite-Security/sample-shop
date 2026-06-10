# Shop Microservice — Backend Plan

A small e-commerce shop backend built with **Spring WebFlux** (reactive), **Spring Data R2DBC**, **Liquibase** for schema migrations, and **OAuth2 Resource Server** for security (JWTs issued by `auth-server`). Port **8061**.

This document is a step-by-step plan only. **Do not implement yet.** Each step lists the goal, what to do, the files involved, and how to know it's done.

---

## Guiding architecture decisions (read first)

These shape every step below, so keep them in mind:

1. **Liquibase runs over JDBC, not R2DBC.** R2DBC has no migration tooling, and Liquibase cannot speak R2DBC. So the app uses **two connections to the same database**:
   - **R2DBC** (`spring.r2dbc.*`) — for all runtime data access (reactive).
   - **JDBC** (`spring.liquibase.url` / `spring.datasource.*`) — used **only** by Liquibase at startup to apply migrations.
   The `org.postgresql:postgresql` (JDBC) dependency is already present; it exists for Liquibase, not runtime queries.

2. **Spring Data R2DBC is NOT a full ORM (not JPA).** There is no lazy loading and no automatic relationship mapping — `@OneToMany` / `@ManyToOne` do not work. Entities map to a single table each; relationships are plain foreign-key columns (e.g. `categoryId`), and joins/aggregations are done with explicit queries or assembled in the service layer. Design entities as flat records.

3. **Everything returns `Mono`/`Flux`.** Repositories, services, and controllers are reactive end to end. Never block (no `.block()`, no blocking JDBC calls on the request path).

4. **Security model mirrors `greetings`.** This service is an OAuth2 resource server validating JWTs from `auth-server`. Roles arrive in a custom `roles` claim and must be mapped to `ROLE_*` authorities (reuse the converter pattern from `greetings/security/GreetingsSec`).

---

## Domain model (kept intentionally small)

| Entity | Purpose | Key fields |
|---|---|---|
| `Category` | Groups products | `id`, `name`, `description` |
| `Product` | Sellable item | `id`, `name`, `description`, `price`, `stock`, `categoryId` |
| `CustomerOrder` | A placed order | `id`, `username` (from JWT subject), `status`, `total`, `createdAt` |
| `OrderItem` | Line item in an order | `id`, `orderId`, `productId`, `quantity`, `unitPrice` |

> `username`/owner comes from the authenticated principal (JWT `sub`), not a stored customer table — keeps it small and avoids duplicating the auth-server user store. `OrderItem.unitPrice` is a **price snapshot** taken at order time so later product price changes don't rewrite order history.

---

## Phase 1 — Project & infrastructure setup

### Step 1.1 — Add the Liquibase dependency
- **Goal:** Make Liquibase available for migrations.
- **Do:** In `build.gradle.kts`, add `implementation("org.liquibase:liquibase-core")`. Keep the existing `runtimeOnly("org.postgresql:postgresql")` — Liquibase uses it.
- **Done when:** `./gradlew build` resolves the dependency.

### Step 1.2 — Configure dual datasource (R2DBC + JDBC) in `application.yaml`
- **Goal:** Wire reactive runtime access and the JDBC connection Liquibase needs.
- **Do:** Add to `src/main/resources/application.yaml`:
  - `spring.r2dbc.url` (e.g. `r2dbc:postgresql://localhost:5433/shopdb`), `username`, `password` — externalized via env vars with sensible local defaults (follow the `${ENV:default}` style already used for the issuer URI).
  - `spring.liquibase.url` (the **JDBC** form: `jdbc:postgresql://localhost:5433/shopdb`), `user`, `password`, and `change-log: classpath:/db/changelog/db.changelog-master.yaml`.
- **Reference:** See `auth-server/src/main/resources/application.yaml` for the Liquibase + datasource env-var pattern.
- **Done when:** Config compiles and points both connections at the same DB.



---

## Phase 2 — Database schema (Liquibase)

> Schema comes **before** entities so the entities can mirror an agreed-upon, migration-controlled schema. Schema is the source of truth; `ddl-auto` does not exist in R2DBC.

### Step 2.1 — Create the changelog skeleton
- **Goal:** Establish the migration layout.
- **Do:** Create `src/main/resources/db/changelog/db.changelog-master.yaml` that `include`s ordered changeset files. Mirror the structure under `auth-server/src/main/resources/db/changelog/`.
- **Done when:** Master changelog exists and references (initially empty) child files.

### Step 2.2 — Changeset: core tables
- **Goal:** Define the schema.
- **Do:** Create `001-create-schema.sql` (liquibase formatted SQL) with tables for `category`, `product`, `customer_order`, `order_item`. Include:
  - Primary keys (`bigint generated always as identity` or `uuid` — pick one and stay consistent).
  - Foreign keys: `product.category_id → category.id`, `order_item.order_id → customer_order.id`, `order_item.product_id → product.id`.
  - `NOT NULL` / sensible defaults; `product.price` and `order_item.unit_price` as `numeric(10,2)`; `customer_order.status` as text/enum-like; timestamps.
- **Done when:** Migration applies cleanly on a fresh DB at app startup.

### Step 2.3 — Changeset: seed data (optional, dev only)
- **Goal:** A few categories/products to test against.
- **Do:** Create `002-seed-products.sql` with `--preconditions onFail:MARK_RAN` guards (same pattern as `auth-server`'s `002-seed-users.sql`) so it's idempotent.
- **Done when:** Re-running startup does not duplicate rows.

---

## Phase 3 — Entities & repositories

### Step 3.1 — Define entities
- **Goal:** Java types mapping 1:1 to the tables.
- **Do:** Under a `domain` (or `model`) package, create `Category`, `Product`, `CustomerOrder`, `OrderItem`. For each:
  - Annotate with `@Table("...")`; mark the id with `@Id`.
  - Use `@Column(...)` where Java camelCase must map to snake_case columns (or configure a naming strategy once).
  - **No relationship annotations** — store `categoryId`, `orderId`, `productId` as plain fields.
  - Consider Lombok (`@Data`/`@Builder`) — already on the classpath — or Java records.
- **Done when:** Each entity reflects exactly one table from Phase 2.

### Step 3.2 — Define repositories
- **Goal:** Reactive data access.
- **Do:** One interface per aggregate extending `ReactiveCrudRepository<Entity, IdType>`. Add derived/`@Query` methods as needed: e.g. `Flux<Product> findByCategoryId(id)`, `Flux<CustomerOrder> findByUsername(username)`, `Flux<OrderItem> findByOrderId(id)`.
- **Done when:** Repositories compile; methods return `Mono`/`Flux`.

### Step 3.3 — Repository slice tests
- **Goal:** Verify mapping and queries against a real schema.
- **Do:** Use `@DataR2dbcTest` (the `spring-boot-starter-data-r2dbc-test` dependency is present). Run against a disposable Postgres (Testcontainers) or an embedded option, applying the Liquibase schema. Verify CRUD + the custom finders with `StepVerifier` (from `reactor-test`).
- **Done when:** Tests pass and confirm column mapping is correct.

---

## Phase 4 — Service layer (business logic)

### Step 4.1 — Catalog service
- **Goal:** Read/manage products & categories.
- **Do:** Service exposing list/get/create/update for products and categories, returning `Mono`/`Flux`. Keep mapping to DTOs here (don't leak entities to controllers).

### Step 4.2 — Order service (the only non-trivial logic)
- **Goal:** Place and read orders.
- **Do:** `placeOrder(username, items)` should, reactively and **in a transaction** (`@Transactional` works with R2DBC via `TransactionalOperator`/reactive tx manager):
  1. Load each referenced product, validate it exists and `stock >= quantity`.
  2. Snapshot `unitPrice` from the current product price into each `OrderItem`.
  3. Compute the order `total`.
  4. Decrement `product.stock`.
  5. Persist `CustomerOrder` then its `OrderItem`s.
  Provide `getOrdersForUser(username)` and `getOrder(id, username)` (enforce ownership).
- **Done when:** Stock validation, price snapshotting, and totals are covered by unit tests with mocked repositories + `StepVerifier`.

### Step 4.3 — DTOs & mapping
- **Goal:** Stable API contract decoupled from the schema.
- **Do:** Request/response records (e.g. `ProductResponse`, `PlaceOrderRequest`, `OrderResponse` with nested line items). Map entity↔DTO in the service layer.

---

## Phase 5 — Web layer (reactive endpoints)

> ** routing style: stay consistent.** `greetings` uses functional `RouterFunction`s; Always delegate to a specialized handler.

### Step 5.1 — Catalog endpoints (public read)
- **Do:** `GET /api/shop/products`, `GET /api/shop/products/{id}`, `GET /api/shop/categories`. Read-only browsing can be public (align with the gateway's `permitAll` style for `/api/greetings/**`).

### Step 5.2 — Order endpoints (authenticated)
- **Do:** `POST /api/shop/orders` (place), `GET /api/shop/orders` (current user's), `GET /api/shop/orders/{id}`. Derive `username` from the JWT principal (`@AuthenticationPrincipal Jwt` / `ServerSecurityContext`) — never trust a client-supplied owner.

### Step 5.3 — Admin endpoints (ROLE_ADMIN)
- **Do:** Create/update/delete products & categories, restricted to `ROLE_ADMIN`.

---

## Phase 6 — Security

### Step 6.1 — Resource-server security config
- **Goal:** Validate JWTs and map roles.
- **Do:** Add a `SecurityWebFilterChain` (reactive) that:
  - Configures `oauth2ResourceServer().jwt(...)` against the configured issuer (already in `application.yaml`).
  - Maps the custom `roles` claim → `ROLE_*` authorities using a `ReactiveJwtAuthenticationConverter`. **Copy the converter pattern from `greetings/security/GreetingsSec.java`.**
  - Authorization rules: catalog reads `permitAll`; `/api/shop/orders/**` authenticated; admin paths `hasRole("ADMIN")`.
  - Disable CSRF (stateless API), as `greetings` does.
- **Done when:** A valid JWT from `auth-server` reaches order endpoints; an admin-only endpoint rejects a `ROLE_USER` token.

### Step 6.2 — Wire into the gateway
- **Goal:** Reach the shop through the edge.
- **Do:** Add a route in `gateway`'s `RouterConfig` for `/api/shop/**` → shop service URI, with `TokenRelay` on the authenticated paths (mirror the `greetings-secured` route). Add the shop service to the root `compose.yaml` with its issuer/datasource env vars.
- **Done when:** A browser-authenticated request through the gateway carries the JWT to the shop.

---

## Phase 7 — Verification & wrap-up

### Step 7.1 — Integration test (happy path)
- **Do:** With `@SpringBootTest` + `WebTestClient` and a Testcontainers Postgres: authenticate (mock JWT), place an order, assert stock decremented and order retrievable. Confirm migrations run in-test.

### Step 7.2 — Run the full stack
- **Do:** `docker compose up` from the repo root (after Step 6.2). Manually walk product browse → login via gateway → place order. Document the flow in `HELP.md` or this file.

---

## Suggested implementation order (summary)

1. Phase 1 (deps, dual datasource, compose)
2. Phase 2 (schema via Liquibase) — verify it applies on startup
3. Phase 3 (entities + repos + slice tests)
4. Phase 4 (services, especially order logic + tx)
5. Phase 5 (controllers/DTOs)
6. Phase 6 (security + gateway route)
7. Phase 7 (integration test + manual run)

UI work (Kotlin shop UI) is a **separate, later effort** and is out of scope for this backend plan.
