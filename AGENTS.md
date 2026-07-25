# AGENTS.md

Guidance for AI coding agents working in this repository.

## Project overview

**Granite Security** is a microservices demo platform: an OAuth2/OIDC-secured,
reactive e-commerce shop. It consists of seven JVM services (Spring Boot 4 /
Java 25), two front ends, five PostgreSQL databases, and a Kafka event bus —
all orchestrated locally with Docker Compose and deployed to Kubernetes on
Hetzner.

This is a **multi-repo-style monorepo**: each service is a sibling directory
with its own build (Gradle wrapper for JVM services, npm for UIs). **There is
no root build file** — all build/test/run commands are executed from inside a
service's own directory.

## Repository layout

```
granite-security/
├── auth-server/   (9090)  — Spring Authorization Server (OIDC provider, form + Google login)
├── gateway/       (8080)  — Spring Cloud Gateway (WebFlux), OAuth2 client + routing + TokenRelay
├── greetings/     (8060)  — Minimal WebFlux resource-server demo; the reference implementation
├── shop/          (8061)  — E-commerce catalog & orders (WebFlux + R2DBC + Kafka outbox)
├── payment/       (8062)  — Stripe payment intents + webhooks (WebFlux + R2DBC + Kafka)
├── delivery/      (8063)  — Delivery tracking, Kafka consumer (WebFlux + R2DBC)
├── profile/       (8064)  — User profiles & delivery addresses (WebFlux + R2DBC); has its own AGENTS.md
├── ui-shop/       (5173)  — React 19 SPA storefront (Vite + TypeScript + oidc-client-ts + Stripe Elements)
├── ui-demo/               — Static/nginx-served demo frontend, alternate deployment target
├── demo-kot/              — Kotlin learning/demo service; built by CI but never dockerized/deployed (no Dockerfile)
├── k8s/                   — Kubernetes manifests: base + kind overlay for local clusters
├── k8s/hetzner/           — Kustomize overlays + runbooks for the Hetzner VPS deployment (incl. ArgoCD)
├── docs/                  — events.md (Kafka event schemas), observability, plans, runbooks
├── plans/                 — Planning documents and change logs
├── compose.yaml           — Docker Compose: all services + 5 Postgres instances + Kafka/Schema Registry/Kafka-UI
├── README.md              — Source of truth for setup, ports, env vars, event flows, deploy steps
└── Master-Plan.md         — Full development roadmap with guiding principles
```

Read `README.md` before making infra or cross-service changes. `profile/AGENTS.md`
has profile-specific details that apply in addition to this file when working
inside `profile/`.

## Build and test commands

All commands run from a service's own directory. Always use the Gradle wrapper.

```bash
# JVM services (auth-server, gateway, greetings, shop, payment, delivery, profile, demo-kot)
./gradlew build -x test     # compile without tests — this is what CI and the Dockerfiles do
./gradlew test              # full test suite (JUnit 5 / JUnit Platform)
./gradlew bootRun           # run the service locally (databases must be up first)
./gradlew bootJar           # produce the runnable jar under build/libs/

# UI services (ui-shop, ui-demo)
npm ci && npm run build     # TypeScript check (tsc -b) + Vite build — what CI runs
npm run dev                 # Vite dev server
npm run lint                # ESLint (ui-shop)
```

Prerequisites: **Java 25** (Gradle toolchains), **Node 22** (CI), **Docker**
(for databases, Testcontainers, and compose).

### Running the full stack locally

```bash
docker compose up --build   # all services + Postgres + Kafka; gateway on :8080
```

For service-by-service local runs (ports, per-database `docker run` commands,
env vars like `OIDC_CLIENT_SECRET`, `STRIPE_SECRET_KEY`), follow `README.md`.
`ui-shop` runs separately via `npm run dev` on port 5173. Swagger UI for the
shop API is at `http://localhost:8080/swagger-ui/index.html` (via gateway) or
`http://localhost:8061/swagger-ui/index.html` (direct).

## Architecture

### Request flow

```
Browser → gateway:8080 (OAuth2 client, authorization code flow)
             ↓
         auth-server:9090 (issues JWT with a custom `roles` claim)
             ↓
         gateway relays the JWT (TokenRelay) → downstream service
```

- The gateway only decides which routes require a session; downstream services
  (greetings, shop, payment, delivery, profile) are **OAuth2 resource servers**
  that validate the JWT independently.
- `auth-server` supports form login (PostgreSQL `authdb`, Liquibase-managed;
  seed users `user`/`user`, `admin`/`admin`, `manager`/`manager`) and Google
  OAuth2 federation. Its RSA key pair is **generated fresh on each startup** —
  a restart invalidates all existing JWTs.
- The gateway's OIDC client (`oidc-client`) is registered in-memory in
  auth-server.

### Reactive everywhere — with one exception

Every service except `auth-server` is reactive end to end: Spring WebFlux for
HTTP and **R2DBC (not JDBC)** for database access. `auth-server` is the
exception — Spring Authorization Server is servlet/MVC-based, so it uses
`spring-boot-starter-data-jpa` (blocking JDBC). Do not introduce blocking
calls (JDBC, blocking HTTP clients, `Thread.sleep`) on the reactive services'
request paths, and do not apply R2DBC/reactor patterns inside `auth-server`.

### Event-driven order lifecycle (transactional outbox)

- Orders are persisted **synchronously**; downstream work happens via events.
- Shop writes an `OrderPlaced` event to an **outbox table in the same DB
  transaction** (never dual-write); a relay publishes to Kafka topic
  `orders.events`, consumed by **payment** (creates a Stripe PaymentIntent) and
  **delivery**.
- Payment writes `PaymentReceived` to its own outbox → topic `payments.events`
  → shop consumes it and transitions the order to `PAID`.
- Order status machine: `PENDING → PAID → SHIPPED → DELIVERED`, plus
  `PAYMENT_FAILED`, `CANCELLED`, `RETURNED → REINBURSED`.
- For local dev without a Stripe webhook, the frontend calls
  `POST /api/payments/intent/{orderId}/sync` to advance payment status
  synchronously. Event schemas are documented in `docs/events/events.md`.

## Code style and conventions

- **Functional WebFlux routing** (`RouterFunction` beans + handler classes), not
  `@RestController` — follow the `greetings` service as the minimal reference.
  Typical layering in shop/payment/delivery/profile:
  `route/` → `handler/` (HTTP concerns, principal extraction) → `service/`
  (business logic, entity↔DTO mapping) → `repository/`
  (`ReactiveCrudRepository` interfaces), with `domain/` (Lombok
  `@Getter/@Setter` `@Table` entities) and `dto/` (Java records).
- **Base package** is `org.granitesecurity.<service>`; Gradle group
  `org.granite-security`.
- **Constructor injection** throughout; no field `@Autowired`. Lombok is used
  (annotation processor configured).
- **Liquibase manages every schema** (formatted SQL changesets, numbered
  sequentially, registered in a master changelog). Entities mirror the schema —
  R2DBC is not a full ORM. Runtime access is R2DBC; migrations run over JDBC,
  so both drivers are on the classpath. Never edit already-applied changesets.
- **No `.block()` on request paths**; verify async flows with `StepVerifier` /
  `WebTestClient`.
- Each service has its own `application.yaml`, fully overridable via
  environment variables (see the table in `README.md`), and a `docker` Spring
  profile used by compose.
- The GraalVM native buildtools plugin is applied to JVM services, but no
  native build is wired into CI/Docker — images ship regular JVM jars.
- Config follows per-service env-var prefixes, e.g. `SHOP_R2DBC_URL`,
  `PAYMENT_SERVER_PORT`, `PROFILE_JDBC_URL`.

## Testing

- JUnit 5 / JUnit Platform in every JVM service (`useJUnitPlatform()`).
- **Service/core tests use mocks** and run without Docker (e.g. shop's
  `OrderServiceTest`, `CatalogServiceTest`, `OrderStatusTest`,
  `OutboxRelayTest`, `EventConsumerTest`).
- **Repository/integration tests use Testcontainers** (PostgreSQL, Kafka via
  `spring-kafka-test`) and require Docker running — e.g. shop's
  `RepositoryTest`, `ShopIntegrationTest`, `EventConsumerIntegrationTest`
  (see `shop/src/test/java/org/granitesecurity/shop/AbstractTestcontainers.java`).
- Test coverage is uneven: `shop` has a real suite; other services range from a
  few tests down to a single `contextLoads` smoke test (e.g. `profile`), which
  may boot the full context and require a reachable database.
- `ui-shop` has **no test runner** — verification is `npm run build`
  (TypeScript + Vite) and `npm run lint`.

## CI/CD and deployment

- CI (`.github/workflows/ci.yml`, on push to `main`) detects which service
  directories changed in the last commit, builds each in a matrix
  (`./gradlew build -x test` for JVM, `npm ci && npm run build` for UI), and
  pushes `moldovean/granite-<service>:latest` + `:<sha>` images to Docker Hub
  (linux/amd64). `demo-kot` is built but excluded from dockerize/deploy. A
  final GitOps job bumps image tags in `k8s/hetzner/app-multi/kustomization.yaml`.
- Kubernetes: `k8s/` holds base manifests + a `kind` overlay for local clusters;
  `k8s/hetzner/` holds three mutually-exclusive-by-domain Kustomize overlays
  (`app`, `app-chocolate`, `app-multi` — current default) plus an ArgoCD
  Application manifest.
- Pods with `imagePullPolicy: Always` do **not** restart on a new `:latest`
  push — force it with `kubectl -n granite rollout restart deployment <service>`.
  Always confirm `kubectl config current-context` before applying; this is a
  multi-context kubeconfig setup.
- One-time cluster bootstrap (StorageClass, Traefik/Gateway API, cert-manager,
  CoreDNS split-horizon, secrets) is documented in `k8s/hetzner/cloudify.md`;
  the repeatable redeploy loop is in `README.md`.

## Security considerations

- Tokens are only issued by `auth-server`; every other service validates JWTs
  via JWKS. Downstream services derive authorities from the `scope`/`scp` claim
  (`SCOPE_*`) and a custom `roles` claim (`ROLE_*`).
- Gateway route policy: `/api/greetings/**` and payment intent/webhook paths
  are public; most `/api/**` routes require an OAuth2 session with token relay;
  product/category writes require `ROLE_ADMIN`. Full route table in `README.md`.
- Stripe webhooks are verified by signature (`STRIPE_WEBHOOK_SECRET`); the
  webhook endpoint must stay public.
- CORS is configured per service/gateway with explicit origin allow-lists and
  credentials allowed — do not widen to `*`.
- Secrets and DB credentials in `compose.yaml` / `application.yaml` are
  **local-dev only**; production values come from environment variables /
  cluster secrets (`STRIPE_SECRET_KEY`, `GOOGLE_CLIENT_ID/SECRET`, DB
  passwords). Never commit real credentials.
- When changing security config (e.g. `GateSec` in gateway, `SecurityConfig` in
  auth-server, `*Sec` classes in resource servers), preserve the existing
  validators — e.g. profile keeps both a timestamp validator and a
  trusted-issuer allow-list in its `DelegatingOAuth2TokenValidator`.
