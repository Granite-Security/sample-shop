# Granite Security

A **roll-your-own commerce stack**: self-hosted, source-available microservices with OAuth2/OIDC baked in end-to-end, 
an async event-driven order/payment/delivery pipeline, and a fully reactive backend — no per-transaction SaaS fees, no vendor lock-in, you own the data and everything!

## Why this stack

- **Customer management, not just checkout.** A dedicated `profile` service owns each user's profile and delivery addresses, auto-provisioned the moment `auth-server` publishes `UserRegistered` — customer data lives in your own database from signup, not a third-party CRM.
- **OAuth2/OIDC integrated everywhere, not bolted on.** `auth-server` is a real Spring Authorization Server (OIDC provider) issuing JWTs; every downstream service — shop, payment, delivery, profile — independently validates the token as a resource server, and the gateway relays it via `TokenRelayGatewayFilterFactory`. One login, enforced consistently at every service boundary, with Google federated login supported alongside form login.
- **Fully asynchronous, event-driven architecture.** Orders, payments, and identity events all flow through Kafka. Shop and payment use the transactional outbox pattern (`OrderPlaced` → `orders.events` → payment/delivery; `PaymentReceived` → `payments.events` → shop) so state transitions are durable and decoupled — no synchronous cross-service calls on the critical path.
- **Stripe integration that doesn't depend on webhooks.** Production uses Stripe webhooks for payment confirmation and refunds, but every flow also has a synchronous fallback (`POST /api/payments/intent/{orderId}/sync`) that advances payment/refund state directly — useful for local dev, restricted network environments, or anywhere inbound webhooks aren't practical.
- **Reactive end-to-end.** Every service except `auth-server` runs Spring WebFlux over R2DBC (non-blocking DB access) rather than blocking JDBC — the whole request path, database included, is non-blocking under load.

## Architecture

```
                            Stripe                              Resend
                              │                                   ▲
                webhook ──────┤                                   │ email
                              ▼                                   │
Browser (5173) ── Gateway (8080) ──┬── Auth Server (9090) ── PostgreSQL (5432)
     ui-shop      (Spring Cloud    │    (OIDC provider)          authdb
     (React +      Gateway)        │        │  produces identity.events
     Stripe                        │        │  (fire-and-forget, no outbox)
     Elements)                     │        │
                                   ├── Greetings (8060)
                                   │
                                   ├── Shop (8061) ─────────── PostgreSQL (5433)
                                   │    (WebFlux + R2DBC)       shopdb
                                   │       │       ▲
                                   │   ┌───▼───────┴───────────────┐
                                   │   │           Kafka           │
                                   │   │  orders.events            │
                                   │   │  payments.events          │
                                   │   │  identity.events  (1h TTL)│
                                   │   └─▲──┬──────┬─────────┬─────┘
                                   │     │  │      │         │
                                   │     │  ▼      ▼         ▼
                                   ├── Payment (8062)   Delivery (8063)
                                   │    (WebFlux + R2DBC  (WebFlux + R2DBC
                                   │     + Stripe API)     + Kafka consumer)
                                   │     │                 │
                                   │     ▼                 ▼
                                   │  PostgreSQL (5434)  PostgreSQL (5435)
                                   │  paymentdb          deliverydb
                                   │
                                   ├── Profile (8064) ─────── PostgreSQL (5436)
                                   │    (WebFlux + R2DBC)      profiledb
                                   │    consumes UserRegistered
                                   │
                                   └── Notification (8066) ── PostgreSQL (5437)
                                        (WebFlux + R2DBC)      notificationdb
                                        consumes identity.events,
                                        sends email via Resend
```

| Service | Port | Stack | Role |
|---------|------|-------|------|
| `gateway` | 8080 | Spring Cloud Gateway (WebFlux) | OAuth2 client, route & token relay |
| `auth-server` | 9090 | Spring Authorization Server | OIDC provider, form + Google login |
| `greetings` | 8060 | Spring WebFlux | Public + secured endpoints (demo) |
| `shop` | 8061 | Spring WebFlux + R2DBC | E-commerce catalog, orders |
| `payment` | 8062 | Spring WebFlux + R2DBC + Stripe API | Payment intent creation, Stripe webhooks |
| `delivery` | 8063 | Spring WebFlux + R2DBC + Kafka | Delivery tracking, consumes `orders.events` |
| `profile` | 8064 | Spring WebFlux + R2DBC | User profile & delivery addresses; provisions profiles from `identity.events` |
| `notification` | 8066 | Spring WebFlux + R2DBC + Kafka | Transactional email (Resend); consumes `identity.events` |
| `ui-shop` | 5173 | React + Vite + oidc-client-ts | SPA storefront with Stripe Elements |
| `postgres` | 5432 | PostgreSQL 17 | Auth server database |
| `shop-postgres` | 5433 | PostgreSQL | Shop database |
| `notification-postgres` | 5437 | PostgreSQL | Notification database |
| `payment-postgres` | 5434 | PostgreSQL | Payment database |
| `delivery-postgres` | 5435 | PostgreSQL | Delivery database |
| `profile-postgres` | 5436 | PostgreSQL | Profile database |

## Prerequisites

- **Java 25** (each service builds with its own Gradle wrapper)
- **Docker** (for PostgreSQL databases and full-stack compose)
- **Google OAuth2 credentials** (optional — for Google login)
- **Stripe API key** (optional - for payments)
- **RESEND API key** (optional - for sending emails)

## Quick start — Docker Compose

```bash
# 1. Add hosts entries (required for Docker networking)
echo '127.0.0.1 auth greetings gateway' | sudo tee -a /etc/hosts

# 2. Set Google OAuth2 credentials (or leave empty to skip Google login)
export GOOGLE_CLIENT_ID=your-client-id
export GOOGLE_CLIENT_SECRET=your-client-secret

# 3. Build and start everything
docker compose up --build
```

Swagger UI: [http://localhost:8080/swagger-ui/index.html](http://localhost:8080/swagger-ui/index.html)

**Seed users** (form login):

| Username | Password | Roles |
|----------|----------|-------|
| `user` | `user` | ROLE_USER |
| `admin` | `admin` | ROLE_ADMIN |
| `manager` | `manager` | ROLE_USER, ROLE_ADMIN |

## Running locally (service-by-service)

Start the databases first, then run each service in its own terminal.

### 1. Start databases

```bash
docker run -d --name auth-postgres -p 5432:5432 \
  -e POSTGRES_DB=authdb -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres \
  postgres:17-alpine

docker run -d --name shop-postgres -p 5433:5432 \
  -e POSTGRES_DB=shopdb -e POSTGRES_USER=myuser -e POSTGRES_PASSWORD=secret \
  postgres:latest

docker run -d --name payment-postgres -p 5434:5432 \
  -e POSTGRES_DB=paymentdb -e POSTGRES_USER=myuser -e POSTGRES_PASSWORD=secret \
  postgres:latest

docker run -d --name delivery-postgres -p 5435:5432 \
  -e POSTGRES_DB=deliverydb -e POSTGRES_USER=myuser -e POSTGRES_PASSWORD=secret \
  postgres:latest

docker run -d --name profile-postgres -p 5436:5432 \
  -e POSTGRES_DB=profiledb -e POSTGRES_USER=myuser -e POSTGRES_PASSWORD=secret \
  postgres:latest
```

### 2. Start services (in separate terminals)

```bash
# Auth server (port 9090)
cd auth-server && ./gradlew bootRun

# Gateway (port 8080)
cd gateway && OIDC_CLIENT_SECRET=secret ./gradlew bootRun

# Greetings (port 8060)
cd greetings && ./gradlew bootRun

# Shop (port 8061)
cd shop && ./gradlew bootRun

# Payment (port 8062) — requires STRIPE_SECRET_KEY
cd payment && STRIPE_SECRET_KEY=sk_test_... ./gradlew bootRun

# Delivery (port 8063)
cd delivery && ./gradlew bootRun

# Profile (port 8064)
cd profile && ./gradlew bootRun

# Notification (port 8066) — email sending needs RESEND_API_KEY;
# without it EmailChannel logs and skips, which is fine for local work.
cd notification && RESEND_API_KEY=re_... ./gradlew bootRun

# UI shop (port 5173)
cd ui-shop && npm install && npm run dev
```

Swagger UI is proxied through the gateway at [http://localhost:8080/swagger-ui/index.html](http://localhost:8080/swagger-ui/index.html) or direct at [http://localhost:8061/swagger-ui/index.html](http://localhost:8061/swagger-ui/index.html).

## Testing

```bash
# Run a service's full test suite (requires Docker for DB-backed tests)
cd shop && ./gradlew test

# Repository tests use Testcontainers and require Docker
# Service/core tests use mocks and run without Docker
```

## Event-driven flows

```
Order placed ──► Outbox (shop DB) ──► Kafka (orders.events) ──► Payment service
                                                                      │
                                                     ┌────────────────┤
                                                     ▼                ▼
                                              Stripe API        emit payment
                                              (create PI)       event (Kafka)
                                                                      │
                                                                      ▼
                                                              Shop consumes
                                                              → order PAID
```

- **Order → Payment:** The shop writes an `OrderPlaced` event to an outbox table, which Kafka relays to the payment service. The payment service creates a Stripe PaymentIntent asynchronously.
- **Payment → Order:** After Stripe confirms the payment (via webhook or sync endpoint), the payment service emits a `PaymentReceived` event to Kafka. The shop consumes it and transitions the order to `PAID`.
- **Frontend:** The React SPA polls for the `clientSecret`, then completes payment client-side with Stripe Elements. A sync endpoint (`POST /api/payments/intent/{orderId}/sync`) is used for local development when Stripe webhooks aren't available.
- **Refunds:** `POST /api/shop/orders/{id}/refund` (shop) transitions the order to `RETURNED` and emits a `RefundRequested` event. The payment service consumes it, performs the Stripe refund, and emits `PaymentRefunded`; the shop then transitions the order `RETURNED → REIMBURSED`. No Stripe webhook is used — the sync endpoint above also reconciles refund state.

### Identity events

```
Register / change password / request reset
            │
            ▼
      auth-server ──► Kafka (identity.events) ──┬──► Notification ──► Resend (email)
   (fire-and-forget,                            │
    no outbox — see below)                      └──► Profile (provisions the profile row)
```

- **One fact, two independent consumers.** auth-server publishes `UserRegistered`, `PasswordChanged` and `PasswordResetRequested` and knows about neither consumer. `notification` turns them into email; `profile` uses `UserRegistered` to populate a user's profile with the email and name captured at registration.
- **Producers never send rendered text.** All copy lives in `notification` as Mustache templates under `resources/templates/<channel>/`. That is what makes adding SMS or WhatsApp a matter of adding a channel plus templates, rather than touching every producer.
- **`PasswordResetRequested` carries the raw token, not a link.** `notification` builds the URL from its own configured frontend origin, so auth-server never needs to know the frontend's URL shape.
- **No outbox here, deliberately.** Unlike shop/payment/delivery, auth-server publishes fire-and-forget and accepts message loss: the courtesy mails are invisible when lost, and a lost reset link is recovered by requesting another. `max.block.ms=2000` so a dead broker can't pin an async worker. Don't "fix" this into the outbox pattern — the reasoning is in [`docs/notification/notification-microservice.md`](docs/notification/notification-microservice.md) §2.

| Kafka topic | Producer | Consumers | Retention |
|-------------|----------|-----------|-----------|
| `orders.events` | Shop (outbox) | Payment, Delivery | 7 days (broker default) |
| `payments.events` | Payment (outbox) | Shop | 7 days (broker default) |
| `identity.events` | auth-server (fire-and-forget) | Notification, Profile | **1 hour** — it carries live password-reset tokens |

> `identity.events` is declared explicitly with `segment.ms=600000` alongside `retention.ms=3600000`. Kafka only deletes *closed* segments and the default roll is 7 days, so on a low-volume topic `retention.ms` on its own deletes nothing.

## API routes

| Path | Auth | Proxied to |
|------|------|------------|
| `/api/greetings/**` | Public | Greetings service |
| `/api/secured/**` | JWT required | Greetings service (token relayed) |
| `/api/shop/products` | GET public, POST/DELETE ADMIN | Shop service |
| `/api/shop/categories` | GET public, POST/DELETE ADMIN | Shop service |
| `/api/shop/orders` | JWT required | Shop service (token relayed) |
| `/api/shop/orders/{id}/refund` | JWT required (admin: any paid order; user: own order, failed delivery only) | Shop service (token relayed) |
| `/api/payments/intent/**` | Public (clientSecret fetch) | Payment service |
| `/api/payments/webhook` | Public (Stripe signature) | Payment service |
| `/api/delivery/**` | JWT required | Delivery service (token relayed) |
| `/api/profiles/**` | JWT required | Profile service (token relayed) |
| `/v3/api-docs/**`, `/swagger-ui/**` | Public | Shop service |

## Stripe setup (payment service)

The payment service requires two env vars for Stripe integration:

```bash
export STRIPE_SECRET_KEY=sk_test_...   # from Stripe dashboard (API keys)
export STRIPE_WEBHOOK_SECRET=whsec_... # from Stripe CLI (see below)
```

To get `STRIPE_WEBHOOK_SECRET`, run the Stripe CLI in a terminal:

```bash
stripe listen --forward-to localhost:8080/api/payments/webhook
```

It prints `Your webhook signing secret is whsec_...` on startup — use that value.

These variables are passed through from the host to Docker in `compose.yaml`.

For local development without webhooks, the frontend calls `POST /api/payments/intent/{orderId}/sync` to synchronously advance payment status after Stripe confirms the payment client-side.

## Key environment variables

| Variable | Default | Service |
|----------|---------|---------|
| `OIDC_ISSUER_URI` | `http://localhost:9090` | gateway |
| `OIDC_CLIENT_SECRET` | `secret` | gateway |
| `MICROSERVICES_GREETINGS_URI` | `http://localhost:8060` | gateway |
| `MICROSERVICES_SHOP_URI` | `http://localhost:8061` | gateway |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | — | auth-server |
| `AUTH_ISSUER_URI` | `http://localhost:9090` | greetings, shop |
| `SHOP_R2DBC_URL` | `r2dbc:postgresql://localhost:5433/shopdb` | shop |
| `STRIPE_SECRET_KEY` / `STRIPE_WEBHOOK_SECRET` | — | payment |
| `STRIPE_CURRENCY` | `chf` | payment |
| `DELIVERY_R2DBC_URL` | `r2dbc:postgresql://localhost:5435/deliverydb` | delivery |
| `PROFILE_R2DBC_URL` | `r2dbc:postgresql://localhost:5436/profiledb` | profile |
| `NOTIFICATION_R2DBC_URL` | `r2dbc:postgresql://localhost:5437/notificationdb` | notification |
| `RESEND_API_KEY` | — (blank = log and skip, no mail sent) | notification |
| `RESEND_FROM` | `Granite Security <no-reply@notify.granite-security.org>` | notification |
| `FRONTEND_ORIGIN` | `http://localhost:5173` | notification (builds the password-reset link) |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | shop, payment, delivery, auth-server, notification, profile |

## Deploying to Kubernetes (Hetzner)

This assumes `kubectl` is already configured with a context pointing at the cluster, and
the CI/CD pipeline (`.github/workflows/ci.yml`) has already built and pushed images to
Docker Hub as `docker.io/moldovean/granite-<service>:latest`.

```bash
# 1. Point kubectl at the right cluster (don't skip this on a multi-context kubeconfig)
kubectl config use-context <your-hetzner-context>
kubectl config current-context   # confirm before applying

# 2. Apply the manifests
kubectl apply -k k8s/hetzner/app-multi

# 3. Pods with imagePullPolicy: Always won't restart on their own just because a new
#    :latest was pushed — the pod template hash hasn't changed. Force a re-pull + rollout:
kubectl -n granite rollout restart deployment <service>
# ...or restart every deployment in the namespace at once:
kubectl -n granite get deployments -o name | xargs -n1 kubectl -n granite rollout restart

# 4. Watch it come up
kubectl -n granite get pods -w
kubectl -n granite rollout status deployment/gateway
```

`k8s/hetzner/` has three overlays, mutually exclusive on domain (each cluster only
trusts one OAuth2 redirect/issuer host at a time — see `sichocolate.md`):

| Overlay | Front end(s) | Domain |
|---|---|---|
| `app` | `ui-shop` only | `granite-security.org` |
| `app-chocolate` | `ui-demo` only | `sichocolate.com` |
| `app-multi` | both `ui-shop` and `ui-demo` | current default, runs both simultaneously |

One-time cluster bootstrap — StorageClass, Traefik/Gateway API, cert-manager, the CoreDNS
split-horizon override, and populating `secrets-patch.yaml` — is already covered end to
end in `k8s/hetzner/cloudify.md`; the steps above are just the repeatable redeploy loop
once that's done.

## Project layout

```
granite-security/
├── auth-server/         — Spring Authorization Server
├── gateway/             — Spring Cloud Gateway
├── greetings/           — WebFlux demo microservice
├── shop/                — E-commerce shop (WebFlux + R2DBC)
├── payment/             — Stripe payment service (WebFlux + R2DBC)
├── delivery/            — Delivery tracking service (WebFlux + R2DBC + Kafka)
├── profile/             — User profile & address service (WebFlux + R2DBC)
├── ui-shop/             — React SPA storefront (Vite + oidc-client-ts)
├── k8s/                 — Kubernetes manifests (base + kind overlay for local clusters)
├── k8s/hetzner/         — Kustomize overlays + runbooks for the Hetzner VPS deployment
├── smoke-tests/         — Smoke test scripts
├── plans/               — Planning documents and change logs
├── compose.yaml         — Docker Compose orchestration (all services + Kafka)
├── events.md            — Kafka event schema documentation
└── Master-Plan.md       — Full development roadmap
```

## OpenAPI documentation

Swagger UI is available at `/swagger-ui/index.html` on both the shop (8061) and proxied through the gateway (8080). The OpenAPI spec is at `/v3/api-docs`.

export TAG=latest

for s in auth-server gateway greetings shop payment profile delivery; do
(cd $s && ./gradlew build -x test)
docker buildx build --platform linux/amd64 -t docker.io/moldovean/granite-$s:$TAG --push $s/
done

docker buildx build --platform linux/amd64 -t docker.io/moldovean/granite-ui-shop:$TAG --push ui-shop/
docker buildx build --platform linux/amd64 -t docker.io/moldovean/granite-ui-demo:$TAG --push ui-demo/