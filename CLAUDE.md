# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository layout

This is a multi-service microservices platform. Each service is a sibling directory with its own build (Gradle wrapper for JVM services, npm for UIs) — there is no root build file.

```
granite-security/
├── auth-server/   (9090)  — Spring Authorization Server (OIDC provider)
├── gateway/       (8080)  — Spring Cloud Gateway (WebFlux), path routing / reverse proxy
├── greetings/     (8060)  — WebFlux demo/resource-server microservice
├── shop/          (8061)  — E-commerce catalog & orders (WebFlux + R2DBC)
├── payment/       (8062)  — Stripe payment intents + webhooks (WebFlux + R2DBC)
├── delivery/      (8063)  — Delivery tracking, Kafka consumer (WebFlux + R2DBC)
├── profile/       (8064)  — User profile & delivery addresses (WebFlux + R2DBC)
├── notification/  (8066)  — Transactional email (Resend), Kafka consumer (WebFlux + R2DBC)
├── ui-shop/       (5173)  — React SPA storefront (Vite + oidc-client-ts + Stripe Elements)
├── ui-demo/                — Static/nginx-served demo frontend, alternate deployment target
├── demo-kot/               — Kotlin learning/demo service; excluded from Docker builds & deploys
├── k8s/                    — Kubernetes manifests: base + kind overlay for local clusters
├── k8s/hetzner/            — Kustomize overlays + runbooks for the Hetzner VPS deployment (incl. ArgoCD)
├── compose.yaml             — Docker Compose orchestration (all services + Postgres + Kafka)
└── README.md                 — Full quick-start, ports, env vars, event flows, deploy steps
```

`README.md` is the source of truth for local setup, per-service ports/env vars, the event-driven order→payment→delivery flow, and Kubernetes/Hetzner deploy steps — read it before making infra or cross-service changes.

## Commands

All commands run from a service's own directory.

```bash
# JVM services (auth-server, gateway, greetings, shop, payment, delivery, profile, notification, demo-kot)
./gradlew build -x test     # CI's build step — compile without running tests
./gradlew test              # full test suite; runs anywhere, no Docker or database needed
./gradlew bootRun           # run the service locally

```

Every test in this repo is a unit test that runs with no external infrastructure — mock-based
(Mockito + `StepVerifier`), plus a handful of `@SpringBootTest` context loads for services whose
context starts without a database. Tests needing a live Postgres, Kafka, Garage or auth-server were
deleted deliberately, not lost: CI runs `build -x test`, so they gated nothing while failing for
anyone without Docker running. **Do not add Testcontainers, `@EmbeddedKafka`, or a `@SpringBootTest`
that reaches a real database back.** Verification of infrastructure-dependent behaviour happens
manually against the deployed cluster.

CI (`.github/workflows/ci.yml`) only builds/pushes services whose directory changed in the last commit (diffs `HEAD^` vs `HEAD`), building each in a matrix and pushing `moldovean/granite-<service>:latest` + `:<sha>` to Docker Hub. `demo-kot` is built but never dockerized/deployed (no Dockerfile).

## Architecture

Every service except `auth-server` is reactive end-to-end: Spring WebFlux for the HTTP layer, and R2DBC (not JDBC) for the five services that own a database (`shop`, `payment`, `delivery`, `profile`, `notification`). `auth-server` is the one exception — Spring Authorization Server is servlet/MVC-based, so it uses `spring-boot-starter-data-jpa` (blocking JDBC) instead. Keep this in mind when adding code: don't introduce blocking calls (JDBC, blocking HTTP clients, `Thread.sleep`) inside the reactive services' request-handling paths, and don't expect R2DBC/reactive patterns to apply inside `auth-server`.

### Request flow

```
Browser (SPA) → gateway:8080 → auth-server:9090 (/auth/**, proxied)
             ↓ authorization code + PKCE, run by the SPA (oidc-client-ts)
         JWT issued to the SPA, held in the browser
             ↓ SPA sends Authorization: Bearer on each call
         gateway:8080 → downstream service (header forwarded untouched)
```

The gateway is a **pass-through reverse proxy**: `GateSec` is `anyExchange().permitAll()`, there is no OAuth2 client configuration and no `TokenRelay` filter. It never obtains, holds or attaches a token — the SPA is the OAuth2 client, and the caller supplies its own `Authorization` header. Downstream services (greetings, shop, payment, delivery, profile, balance, storage) are OAuth2 resource servers that validate that JWT independently, and are the *only* place authorization is enforced. Adding a route to `RouterConfig` therefore protects nothing by itself: the receiving service must guard it.

### auth-server

- OIDC provider for the whole system. Supports form login (local DB users) and Google OAuth2 federated login.
- RSA key pair is **generated fresh on each startup** — existing JWTs become invalid after a restart.
- Injects a custom `roles` claim into every issued JWT (`OAuth2TokenCustomizer` in `SecurityConfig`).
- User store: PostgreSQL (`authdb`), schema managed by Liquibase. Seed users: `user`/`user` (ROLE_USER), `admin`/`admin` (ROLE_ADMIN), `manager`/`manager` (ROLE_USER + ROLE_ADMIN).
- `RegisteredClientRepository` is in-memory: the SPA clients (`spa-client-shop`, `spa-client-chocolate`, public + PKCE), the service-to-service clients (`internal-service`, `identity-admin`, `external-service`) and a legacy `oidc-client` no longer used by the gateway.

### gateway

- Spring Cloud Gateway (WebFlux, reactive). Routes defined in `RouterConfig`; `GateSec` permits every exchange.
- Routes `/api/**` by path to each service and `/auth/**` to auth-server, so the SPA reaches the OIDC endpoints on the same origin as the API. Downstream URIs come from `microservices.*.uri` (`MICROSERVICES_*_URI` env vars) — no OIDC issuer setting is read here.
- It still pulls in `spring-boot-starter-security-oauth2-client`, but nothing configures a client registration. Don't infer from that dependency that a session or token relay exists.
- `application.yaml` carries hard-won comments about connection-pool eviction (dead pods after a deploy), `server.forward-headers-strategy` and the absent `globalcors`. Read them before changing anything there.

### notification

- Owns **all** transactional messaging. Producers publish domain facts; they never send rendered text. All copy lives here as Mustache templates under `resources/templates/<channel>/`, keyed by event type — `{{ }}` auto-escaping is what keeps a hostile password-reset token from becoming markup.
- Consumes `identity.events` (produced by auth-server). Idempotent: a `processed_event` row is inserted **before** sending, and every outcome lands in `notification_log`.
- Events older than a per-type threshold are dropped as `DROPPED_STALE` and committed, never retried — Kafka retention deletes messages but does not stop a consumer acting on one still in the log, and a replayed reset event would mail an expired link.
- **Not an OAuth2 resource server** — it has no inbound API, so it validates no tokens. Do not add a `SecurityWebFilterChain` guarding nothing. It gains one when the in-app inbox lands.
- `kafka-ui` is deployed alongside it but has **no HTTPRoute in any overlay** and must not get one: the topic carries reset tokens and kafka-ui allows writes by default. Reach it with `kubectl -n granite port-forward deploy/kafka-ui 8090:8080`.

### auth-server → identity.events

auth-server publishes `PasswordChanged`, `PasswordResetRequested` and `UserRegistered` **fire-and-forget, with no outbox** — a deliberate departure from the outbox pattern used by shop/payment/delivery. Message loss is accepted: the courtesy mails are invisible when lost, and a lost reset link is recovered by the user requesting another. `max.block.ms=2000` so a dead broker cannot pin an `@Async` worker. Do not "fix" this into an outbox; see `docs/notification/notification-microservice.md` §2.

`notification` (email) and `profile` (profile provisioning) both consume this topic independently.

### shop / payment / delivery / profile

- All WebFlux + R2DBC (reactive, non-blocking DB access), each with its own Postgres instance and Liquibase-managed schema.
- Functional routing style (`RouterFunction` / handler classes) rather than `@RestController` — follow this convention for new endpoints, matching `greetings`.
- Event-driven order lifecycle via Kafka, using the transactional outbox pattern (not direct produce-on-write):
  - Shop writes an `OrderPlaced` event to an outbox table → relayed to Kafka topic `orders.events` → consumed by payment (creates a Stripe PaymentIntent) and delivery.
  - Payment writes a `PaymentReceived` event to its outbox → Kafka topic `payments.events` → shop consumes it and transitions the order to `PAID`.
  - For local dev without a working Stripe webhook, `POST /api/payments/intent/{orderId}/sync` synchronously advances payment status instead of waiting on the webhook.
- `greetings` remains the minimal reference implementation for OAuth2 resource-server + functional routing conventions used across these services.

### ui-shop

- React 19 + Vite + `oidc-client-ts` (authorization code flow against auth-server via the gateway) + Stripe Elements for client-side payment confirmation.
- Polls for the payment `clientSecret` from the payment service, then completes payment client-side.

## Deploying (Kubernetes / Hetzner)

- `k8s/` holds base manifests + a `kind` overlay for local cluster testing.
- `k8s/hetzner/` holds three mutually-exclusive-by-domain Kustomize overlays (`app`: ui-shop only, `app-chocolate`: ui-demo only, `app-multi`: both — current default) plus an ArgoCD Application manifest (`k8s/hetzner/argocd/`).
- Images must already be pushed by CI before applying manifests. Pods with `imagePullPolicy: Always` do **not** auto-restart on a new `:latest` push — force it with `kubectl -n granite rollout restart deployment <service>`.
- One-time cluster bootstrap (StorageClass, Traefik/Gateway API, cert-manager, CoreDNS split-horizon) is documented end-to-end in `k8s/hetzner/cloudify.md`; day-2 redeploy steps are in `README.md`.
- Always confirm `kubectl config current-context` before applying — this is a multi-context kubeconfig setup.
