# Granite Security — Master Plan


This is a planning document. Each step states a **Goal** (why), **Do** (how /
what), and **Done when** (the observable exit criteria). Phases are ordered by
dependency — earlier phases unblock later ones — but Phases 1–2 can proceed in
parallel with planning of Phases 3+.

---

## Current state (baseline)

| Service       | Stack | Port | Status |
|---------------|---|---|---|
| `auth-server` | Spring Authorization Server, JPA, Liquibase, Postgres | 9090 | Working (OIDC, form + Google login, `roles` claim) |
| `gateway`     | Spring Cloud Gateway (reactive), OAuth2 client + TokenRelay | 8080 | Working (routes greetings + shop) |
| `greetings`   | WebFlux, OAuth2 resource server | 8060 | Working |
| `shop`        | WebFlux, R2DBC, Liquibase, OAuth2 resource server | 8061 | **Implemented, but 0 automated tests** |
| `payment`     | WebFlux, R2DBC, Liquibase, OAuth2 resource server | 8062 | Not yet implemented |
| `profile`     | WebFlux, R2DBC, Liquibase, OAuth2 resource server | 8063 | Working (user profile CRUD) |
| `delivery`    | WebFlux, R2DBC, Liquibase, OAuth2 resource server | 8064 | Working (delivery + tracking CRUD) |



---

## Guiding principles

1.**Reactive end to end.** No `.block()` on request paths. Verify async flows
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

# PHASE 1

Set up Env

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
  `PAYMENT_FAILED`, `CANCELLED`, as well as order `RETURNED` → `REINBURSED` . Encode allowed transitions; reject invalid ones.
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
