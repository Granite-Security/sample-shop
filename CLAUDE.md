# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository layout

This is a multi-service microservices platform. Each service is a sibling directory with its own build (Gradle wrapper for JVM services, npm for UIs) — there is no root build file.

```
granite-security/
├── auth-server/   (9090)  — Spring Authorization Server (OIDC provider)
├── gateway/       (8080)  — Spring Cloud Gateway (WebFlux), OAuth2 client + routing
├── greetings/     (8060)  — WebFlux demo/resource-server microservice
├── shop/          (8061)  — E-commerce catalog & orders (WebFlux + R2DBC)
├── payment/       (8062)  — Stripe payment intents + webhooks (WebFlux + R2DBC)
├── delivery/      (8063)  — Delivery tracking, Kafka consumer (WebFlux + R2DBC)
├── profile/       (8064)  — User profile & delivery addresses (WebFlux + R2DBC)
├── ui-shop/       (5173)  — React SPA storefront (Vite + oidc-client-ts + Stripe Elements)
├── ui-demo/                — Static/nginx-served demo frontend, alternate deployment target
├── demo-kot/               — Kotlin learning/demo service; excluded from Docker builds & deploys
├── k8s/                    — Kubernetes manifests: base + kind overlay for local clusters
├── cloud/hetzner/          — Kustomize overlays + runbooks for the Hetzner VPS deployment (incl. ArgoCD)
├── cloud/aws/               — AWS deployment notes
├── compose.yaml             — Docker Compose orchestration (all services + Postgres + Kafka)
└── README.md                 — Full quick-start, ports, env vars, event flows, deploy steps
```

`README.md` is the source of truth for local setup, per-service ports/env vars, the event-driven order→payment→delivery flow, and Kubernetes/Hetzner deploy steps — read it before making infra or cross-service changes.

## Commands

All commands run from a service's own directory.

```bash
# JVM services (auth-server, gateway, greetings, shop, payment, delivery, profile, demo-kot)
./gradlew build -x test     # CI's build step — compile without running tests
./gradlew test              # full test suite; repository tests use Testcontainers and need Docker
./gradlew bootRun           # run the service locally

```

Service/core tests use mocks and run without Docker; repository-layer tests use Testcontainers and require Docker to be running.

CI (`.github/workflows/ci.yml`) only builds/pushes services whose directory changed in the last commit (diffs `HEAD^` vs `HEAD`), building each in a matrix and pushing `moldovean/granite-<service>:latest` + `:<sha>` to Docker Hub. `demo-kot` is built but never dockerized/deployed (no Dockerfile).

## Architecture

Every service except `auth-server` is reactive end-to-end: Spring WebFlux for the HTTP layer, and R2DBC (not JDBC) for the four services that own a database (`shop`, `payment`, `delivery`, `profile`). `auth-server` is the one exception — Spring Authorization Server is servlet/MVC-based, so it uses `spring-boot-starter-data-jpa` (blocking JDBC) instead. Keep this in mind when adding code: don't introduce blocking calls (JDBC, blocking HTTP clients, `Thread.sleep`) inside the reactive services' request-handling paths, and don't expect R2DBC/reactive patterns to apply inside `auth-server`.

### Request flow

```
Browser → gateway:8080 (OAuth2 client)
             ↓ authorization code flow
         auth-server:9090 (Spring Authorization Server)
             ↓ JWT issued
         gateway relays JWT (TokenRelayGatewayFilterFactory) → downstream service
```

Downstream services (greetings, shop, payment, delivery, profile) are OAuth2 resource servers that validate the JWT issued by auth-server independently — the gateway does not do authorization itself beyond deciding which routes require a session.

### auth-server

- OIDC provider for the whole system. Supports form login (local DB users) and Google OAuth2 federated login.
- RSA key pair is **generated fresh on each startup** — existing JWTs become invalid after a restart.
- Injects a custom `roles` claim into every issued JWT (`OAuth2TokenCustomizer` in `SecurityConfig`).
- User store: PostgreSQL (`authdb`), schema managed by Liquibase. Seed users: `user`/`user` (ROLE_USER), `admin`/`admin` (ROLE_ADMIN), `manager`/`manager` (ROLE_USER + ROLE_ADMIN).
- `RegisteredClientRepository` is in-memory; the gateway's OIDC client (`oidc-client`) is configured here.

### gateway

- Spring Cloud Gateway (WebFlux, reactive). Routes defined in `RouterConfig`; security policy in `GateSec`.
- `/api/greetings/**` is permit-all, no token relay. Most other `/api/**` routes require an OAuth2 session and relay the JWT as a Bearer token to the downstream service.
- OIDC provider URI defaults to `http://localhost:9090`, overridden via `OIDC_ISSUER_URI` in Docker/K8s.

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
- `cloud/hetzner/` holds three mutually-exclusive-by-domain Kustomize overlays (`app`: ui-shop only, `app-chocolate`: ui-demo only, `app-multi`: both — current default) plus an ArgoCD Application manifest (`cloud/hetzner/argocd/`).
- Images must already be pushed by CI before applying manifests. Pods with `imagePullPolicy: Always` do **not** auto-restart on a new `:latest` push — force it with `kubectl -n granite rollout restart deployment <service>`.
- One-time cluster bootstrap (StorageClass, Traefik/Gateway API, cert-manager, CoreDNS split-horizon) is documented end-to-end in `cloud/hetzner/cloudify.md`; day-2 redeploy steps are in `README.md`.
- Always confirm `kubectl config current-context` before applying — this is a multi-context kubeconfig setup.
