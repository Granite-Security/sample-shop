# Granite Security — Master Plan

A roadmap that takes the system from "a working shop backend with no tests" to a
**tested, event-driven, multi-service e-commerce platform** consumable by a web
front end and a mobile app.

This is a planning document. Each step states a **Goal** (why), **Do** (how /
what), and **Done when** (the observable exit criteria). Phases are ordered by
dependency — earlier phases unblock later ones — but Phases 1–2 can proceed in
parallel with planning of Phases 3+.

---

## Current state (baseline)

| Service | Stack | Port | Status |
|---|---|---|---|
| `auth-server` | Spring Authorization Server, JPA, Liquibase, Postgres | 9090 | Working (OIDC, form + Google login, `roles` claim) |
| `gateway` | Spring Cloud Gateway (reactive), OAuth2 client + TokenRelay | 8080 | Working (routes greetings + shop) |
| `greetings` | WebFlux, OAuth2 resource server | 8060 | Working |
| `shop` | WebFlux, R2DBC, Liquibase, OAuth2 resource server | 8061 | **Implemented, but 0 automated tests** |
| `integration-tests/rest` | curl/bash smoke scripts | — | `greetings_secured.sh`, `shop_secured.sh` |

**The most urgent gap is automated test coverage for `shop`.** All test
dependencies (Testcontainers Postgres + R2DBC, `WebTestClient`, `reactor-test`,
`spring-security-test`) are already on the classpath and unused.

---

## Guiding principles

1. **Test with the code.** In-process tests (unit/slice/integration) live in
   each service's `src/test`. Cross-service end-to-end tests live in
   `integration-tests/`. Do **not** spin up a separate "testing project" yet —
   it adds CI/build overhead without payoff until several services exist.
2. **Reactive end to end.** No `.block()` on request paths. Verify async flows
   with `StepVerifier` / `WebTestClient`.
3. **Schema is the source of truth.** Liquibase migrations define tables;
   entities mirror them. R2DBC is not a full ORM (no relationship mapping).
4. **Synchronous command, asynchronous side effects.** Orders are persisted
   synchronously for instant user feedback; downstream work (payment, shipping)
   happens via events.
5. **Never dual-write.** Domain state and outgoing events are committed in the
   **same DB transaction** via the outbox pattern; a relay publishes to Kafka.
6. **Contracts are explicit.** REST APIs documented with OpenAPI; Kafka events
   versioned through a schema registry.

---

# PHASE 1 — Test the shop microservice (highest priority)

Goal of the phase: trustworthy, fast, layered test coverage living inside the
`shop` module. Target the testing pyramid: many unit tests, fewer slice tests,
a handful of full integration tests.

### Step 1.1 — Establish the test harness
- **Goal:** A reusable Testcontainers Postgres that applies the real Liquibase schema.
- **Do:** Create an abstract base test class that starts a `PostgreSQLContainer`,
  wires both R2DBC (`spring.r2dbc.*`) and JDBC/Liquibase (`spring.liquibase.*`)
  dynamic properties via `@DynamicPropertySource`, and lets Liquibase migrate on
  startup. Reuse the container across the suite (singleton pattern) for speed.
- **Done when:** A trivial test boots the context against a containerized DB and
  the schema (Phase-2 migrations) is present.

### Step 1.2 — Repository slice tests (`@DataR2dbcTest`)
- **Goal:** Verify entity↔column mapping and custom finders.
- **Do:** For each repository test CRUD + derived queries against the real schema:
  - `ProductRepository.findByCategoryId`
  - `CustomerOrderRepository.findByUsername`
  - `OrderItemRepository.findByOrderId`
  Assert with `StepVerifier` (counts, ordering, field values, snake_case mapping).
- **Done when:** Mapping bugs (e.g. `created_at`, `unit_price`) would be caught.

### Step 1.3 — Service unit tests (mocked repositories)
- **Goal:** Cover business logic in isolation, especially `OrderService.placeOrder`.
- **Do:** Mock repositories; use `StepVerifier`. Cover:
  - Empty/null item list → `ShopException`.
  - Unknown product id → error.
  - Insufficient stock → error with available/requested detail.
  - Happy path → correct `total`, `unitPrice` snapshot, stock decrement, persistence order.
  - `getOrder(id, username)` ownership enforcement (foreign user → not found).
  - `CatalogService` create/update/get/delete and not-found paths.
- **Done when:** Each branch in the service layer has a test; coverage of
  `OrderService` ≈ 100% of branches.

### Step 1.4 — Web layer tests (`WebTestClient` + mocked services)
- **Goal:** Verify routing, status codes, JSON shapes, and security rules per route.
- **Do:** Bind the `RouterFunction` with mocked handlers/services. Use
  `spring-security-test` (`mockJwt()` with/without `roles`) to assert:
  - Public reads (`GET /api/shop/products`, `/products/{id}`, `/categories`) → 200 anonymously.
  - `POST /api/shop/orders` → 401 anonymous, 200 with a user JWT.
  - Admin paths (`POST/PUT/DELETE products|categories`) → 403 for `ROLE_USER`, 200/204 for `ROLE_ADMIN`.
  - Validation/error mapping (`ShopException` → 400, missing product → 404).
- **Done when:** Every route's auth rule and happy/error response is asserted.

### Step 1.5 — Full integration test (`@SpringBootTest`)
- **Goal:** One real end-to-end pass through HTTP → service → R2DBC → DB.
- **Do:** Boot the whole app against Testcontainers Postgres with a mock-issued
  JWT. Place an order, assert stock decremented in the DB and the order is
  retrievable and owner-scoped.
- **Done when:** Green run proves migrations + wiring + persistence cohere.

### Step 1.6 — Wire tests into the build / CI
- **Goal:** Tests run automatically.
- **Do:** Ensure `./gradlew test` runs the suite; document Docker requirement for
  Testcontainers. (Optionally add a GitHub Actions / CI workflow later.)
- **Done when:** `./gradlew :shop:test` is green locally from a clean checkout.

---

# PHASE 2 — API readiness for front end & mobile

Goal: make the shop a clean, documented, browser/mobile-friendly API **before**
clients are built against it.

### Step 2.1 — OpenAPI documentation
- **Goal:** A machine-readable contract for FE/mobile codegen.
- **Do:** Add `springdoc-openapi` (WebFlux flavor); annotate routes/DTOs; expose
  `/v3/api-docs` + Swagger UI. Keep it behind the gateway appropriately.
- **Done when:** FE/mobile can generate a client from the spec.

### Step 2.2 — CORS & gateway exposure for SPA/mobile
- **Goal:** Allow browser/mobile origins to call through the gateway safely.
- **Do:** Configure CORS at the gateway; confirm the OAuth2 login redirect works
  for a SPA (PKCE public client) and decide token strategy for mobile.
- **Done when:** A browser app on another origin completes login + a secured call.

### Step 2.3 — Pagination, filtering, consistent error model
- **Goal:** Production-grade list endpoints and predictable errors.
- **Do:** Add pagination/sort to product/category/order listings; standardize an
  error response body (RFC 7807 `application/problem+json`).
- **Done when:** Large catalogs paginate; all errors share one shape.

### Step 2.4 — Contract tests for the public API
- **Goal:** Prevent breaking changes for clients.
- **Do:** Snapshot/contract tests against the OpenAPI spec (or consumer-driven
  contracts) in `integration-tests/`.
- **Done when:** A breaking schema change fails a test.

---

# PHASE 3 — Kafka infrastructure

Goal: a running, observable Kafka cluster integrated into local + compose dev.

### Step 3.1 — Add Kafka (+ schema registry) to `compose.yaml`
- **Goal:** Local broker for development.
- **Do:** Add a Kafka broker (KRaft mode, no ZooKeeper) and a schema registry
  service; optionally a UI (e.g. AKHQ/Kafka-UI). Wire health checks + depends_on.
- **Done when:** `docker compose up` brings up a reachable broker + registry.

### Step 3.2 — Define event taxonomy & topic conventions
- **Goal:** Agree on the event contracts first.
- **Do:** Document topics and event schemas (Avro/JSON-Schema/Protobuf):
  - `orders.events` → `OrderPlaced`, `OrderCancelled`
  - `payments.events` → `PaymentReceived`, `PaymentFailed`
  - `shipments.events` → `ShipmentDispatched`, `ShipmentDelivered`
  Define keys (order id), partitioning, retention, naming (`<domain>.<type>` or
  versioned subjects). Decide event envelope (id, type, version, occurredAt,
  correlationId/causationId).
- **Done when:** A short `events.md` describes every topic, schema, and key.

### Step 3.3 — Shared events module (optional)
- **Goal:** Avoid copy-pasted DTOs across services.
- **Do:** Decide between a shared `events` library vs. per-service generated
  classes from the registry. (Generated-from-registry is looser-coupled.)
- **Done when:** A single documented mechanism for producing/consuming schemas.

---

# PHASE 4 — Make `shop` event-driven (transactional outbox)

Goal: when an order is placed, reliably emit `OrderPlaced` exactly once, without
dual-write hazards, and reflect lifecycle in an explicit status machine.

### Step 4.1 — Order status state machine
- **Goal:** Model the order lifecycle.
- **Do:** Define states `PENDING → PAID → SHIPPED → DELIVERED`, plus
  `PAYMENT_FAILED`, `CANCELLED`. Encode allowed transitions; reject invalid ones.
- **Done when:** Status transitions are centralized and unit-tested.

### Step 4.2 — Outbox table + write-on-commit
- **Goal:** Atomic domain change + event intent.
- **Do:** Add an `outbox` table (id, aggregate, type, payload, status, createdAt)
  via Liquibase. In `placeOrder`'s transaction, persist the order **and** an
  outbox row in the same commit.
- **Done when:** A test shows order + outbox row commit/rollback together.

### Step 4.3 — Outbox relay → Kafka
- **Goal:** Publish committed events to Kafka.
- **Do:** Add `spring-kafka`; a relay (poller or CDC) reads unsent outbox rows,
  publishes to `orders.events`, marks them sent. Ensure idempotent publishing
  (dedupe by event id) and at-least-once with consumer idempotency downstream.
- **Done when:** Placing an order results in an `OrderPlaced` message on Kafka.

### Step 4.4 — Consume lifecycle events to advance order status
- **Goal:** Close the loop in `shop`.
- **Do:** Consume `PaymentReceived`/`PaymentFailed` and `ShipmentDispatched`/
  `ShipmentDelivered`; transition the order status accordingly (idempotently).
- **Done when:** Simulated downstream events move an order through its lifecycle.

### Step 4.5 — Test the event flow
- **Goal:** Confidence without a real broker in unit tests.
- **Do:** Use `spring-kafka-test` (embedded Kafka) or Testcontainers Kafka.
  Assert outbox→publish and consume→transition. Cover duplicate-delivery idempotency.
- **Done when:** Event paths are covered in `shop/src/test`.

---

# PHASE 5 — Payment microservice

Goal: a new service that reacts to `OrderPlaced`, processes (mock) payment, and
emits `PaymentReceived` / `PaymentFailed`.

### Step 5.1 — Scaffold the service
- **Goal:** A consistent new module.
- **Do:** New `payment/` module mirroring `shop` conventions (WebFlux or plain
  consumer, R2DBC + Liquibase if it persists, OAuth2 resource server if it
  exposes any API). Pick a port (e.g. 8062). Add to `compose.yaml`.
- **Done when:** Service boots and connects to Kafka.

### Step 5.2 — Consume `OrderPlaced`, persist a payment intent
- **Do:** Idempotent consumer keyed by order id; store payment record with status.
- **Done when:** Each order yields exactly one payment record (dedupe verified).

### Step 5.3 — Process payment (mock provider) + emit result
- **Do:** Simulate success/failure (configurable); publish `PaymentReceived` or
  `PaymentFailed` via its own outbox/relay.
- **Done when:** A placed order produces a payment outcome event.

### Step 5.4 — Tests
- **Do:** Embedded/Testcontainers Kafka; assert consume→persist→emit and
  idempotency. Add a payment slice/integration test.
- **Done when:** Payment service suite is green.

---

# PHASE 6 — Shipment microservice

Goal: react to `PaymentReceived`, create a shipment, emit shipment events.

### Step 6.1 — Scaffold (mirror Phase 5).
### Step 6.2 — Consume `PaymentReceived` → create shipment (idempotent).
### Step 6.3 — Emit `ShipmentDispatched` then `ShipmentDelivered` (simulated progression).
### Step 6.4 — Tests (consume→create→emit, idempotency).
- **Done when:** A paid order flows automatically to a delivered shipment, and
  `shop` reflects `SHIPPED`/`DELIVERED` (closing the Phase 4.4 loop).

---

# PHASE 7 — Saga, consistency & failure handling

Goal: make the choreography robust against partial failures.

### Step 7.1 — Compensation paths
- **Do:** On `PaymentFailed`, cancel the order and **restock** products
  (compensating action). Define compensations for each failure point.
- **Done when:** A forced payment failure leaves consistent state (order
  CANCELLED, stock restored).

### Step 7.2 — Dead-letter topics & retries
- **Do:** Configure retry + DLT for each consumer; poison messages land in DLT
  with context.
- **Done when:** A deliberately bad message is retried then dead-lettered, not lost.

### Step 7.3 — Correlation & tracing across events
- **Do:** Propagate `correlationId`/`causationId` through events; add distributed
  tracing (Micrometer/OpenTelemetry) across HTTP + Kafka.
- **Done when:** One order's full journey is traceable end to end.

---

# PHASE 8 — Observability, resilience & end-to-end testing

### Step 8.1 — Metrics, health, logs
- **Do:** Actuator + Micrometer metrics (consumer lag, outbox backlog), structured
  logs, health checks for all services in compose.
- **Done when:** A dashboard shows per-service health + Kafka lag.

### Step 8.2 — End-to-end scenario tests
- **Do:** In `integration-tests/`, script/automate the full journey: login →
  browse → place order → payment → shipment → status `DELIVERED`. Consider
  Testcontainers-Compose or a JUnit E2E module spinning the whole stack.
- **Done when:** One command verifies the entire cross-service happy path + a
  failure path.

### Step 8.3 — Load / resilience checks (optional)
- **Do:** Basic load test on order placement; chaos on a downstream consumer to
  prove the system degrades gracefully (orders still accepted, processed later).
- **Done when:** Broker/consumer outage doesn't lose orders.

---

# PHASE 9 — Front end & mobile delivery

### Step 9.1 — Web front end
- **Do:** SPA (the planned Kotlin/JS or other) using PKCE login through the
  gateway; consume the OpenAPI client; show catalog, cart, order status (driven
  by the lifecycle states).
- **Done when:** A user completes a purchase and watches status progress.

### Step 9.2 — Mobile app
- **Do:** Mobile OAuth2 (Authorization Code + PKCE, system browser/AppAuth);
  reuse the same API contract; handle token storage/refresh.
- **Done when:** The mobile app completes the same purchase journey.

### Step 9.3 — Real-time order updates (optional)
- **Do:** Push lifecycle changes to clients (SSE/WebSocket from `shop`, fed by the
  events it already consumes).
- **Done when:** Clients reflect `PAID/SHIPPED/DELIVERED` without polling.

---

## Recommended execution order (summary)

1. **Phase 1** — shop tests (do this first; it protects everything after).
2. **Phase 2** — API readiness (parallelizable with Phase 1 tail).
3. **Phase 3** — Kafka infra.
4. **Phase 4** — shop outbox + events + status machine.
5. **Phase 5** — payment service.
6. **Phase 6** — shipment service.
7. **Phase 7** — saga/compensation/DLT.
8. **Phase 8** — observability + E2E.
9. **Phase 9** — FE + mobile.

## Open decisions to confirm before building

- **Event serialization:** Avro + Schema Registry (strong contracts, codegen) vs.
  JSON Schema (simpler). Recommendation: Avro/Protobuf with a registry.
- **Choreography vs. orchestration:** start choreographed (services react to
  events); revisit an orchestrator (e.g. a dedicated saga/order-orchestrator) only
  if flows grow complex.
- **Shared events lib vs. registry codegen:** prefer registry-generated to keep
  services loosely coupled.
- **Separate E2E test project:** defer until Phase 8; the existing
  `integration-tests/` folder covers the interim.
