# Granite Security

A **roll-your-own commerce stack**: self-hosted, source-available microservices with OAuth2/OIDC baked in end-to-end, 
an async event-driven order/payment/delivery pipeline, and a fully reactive backend — no per-transaction SaaS fees, no vendor lock-in, you own the data and everything!

## Why this stack

- **Customer management, not just checkout.** A dedicated `profile` service owns each user's profile and delivery addresses, auto-provisioned the moment `auth-server` publishes `UserRegistered` — customer data lives in your own database from signup, not a third-party CRM.
- **OAuth2/OIDC integrated everywhere, not bolted on.** `auth-server` is a real Spring Authorization Server (OIDC provider) issuing JWTs; every downstream service — shop, payment, delivery, profile — independently validates the token as a resource server. The SPA holds the token (authorization code + PKCE) and sends it as a `Bearer` header; the gateway forwards it untouched and decides nothing. Authorization is enforced at each service boundary, never in one place that could be bypassed, with Google federated login supported alongside form login.
- **Fully asynchronous, event-driven architecture.** Orders, payments, and identity events all flow through Kafka. Shop and payment use the transactional outbox pattern (`OrderPlaced` → `orders.events` → payment/delivery; `PaymentReceived` → `payments.events` → shop) so state transitions are durable and decoupled — no synchronous cross-service calls on the critical path.
- **Stripe integration that doesn't depend on webhooks.** Production uses Stripe webhooks for payment confirmation and refunds, but every flow also has a synchronous fallback (`POST /api/payments/intent/{orderId}/sync`) that advances payment/refund state directly — useful for local dev, restricted network environments, or anywhere inbound webhooks aren't practical.
- **Reactive end-to-end.** Every service except `auth-server` runs Spring WebFlux over R2DBC (non-blocking DB access) rather than blocking JDBC — the whole request path, database included, is non-blocking under load.

## Architecture

Everything enters through the gateway, a pass-through reverse proxy: it routes
by path and enforces no authorization of its own. Each service behind it
validates the caller's JWT itself.

```
ui-shop (5173) ┐                          ┌─ Auth Server (9090) ── OIDC provider,
ui-demo        ├─> Gateway (8080) ────────┤   (servlet + JPA)      JWT + roles claim
Stripe/PayPal  ┘   path routing,          │
  webhooks         no auth of its own     ├─ Greetings 8060   Shop 8061   Payment 8062
                                          └─ Delivery 8063    Profile 8064
                                             Storage 8065     Balance 8067
                                             Notification 8066 (no inbound API)
                                             Accounting 8068 (admin-only books)
```

Every service except `auth-server` is WebFlux + R2DBC end to end. Seven own a
database (`authdb` 5432, `shopdb` 5433, `paymentdb` 5434, `deliverydb` 5435,
`profiledb` 5436, `notificationdb` 5437, `balancedb` 5438, `accountingdb` 5439);
`storage` owns a
Garage S3 bucket instead, and `gateway`/`greetings` own no state.

Services never call each other on the critical path — they exchange facts over
Kafka. `shop`, `payment` and `delivery` publish through a transactional outbox;
`auth-server` publishes fire-and-forget, no outbox, by design.

```
orders.events     shop        ──>  payment, delivery, accounting
payments.events   payment     ──>  shop, delivery, balance, accounting
delivery.events   delivery    ──>  shop, accounting
identity.events   auth-server ──>  profile (provisions), notification (emails)
```

| Service | Port | Stack | Role |
|---------|------|-------|------|
| `gateway` | 8080 | Spring Cloud Gateway (WebFlux) | Path routing; proxies `/auth/**` to auth-server |
| `auth-server` | 9090 | Spring Authorization Server (JPA) | OIDC provider, form + Google login |
| `greetings` | 8060 | WebFlux | Reference resource server (demo) |
| `shop` | 8061 | WebFlux + R2DBC + Kafka | Catalog and orders |
| `payment` | 8062 | WebFlux + R2DBC + Kafka + Stripe/PayPal | Payment intents, webhooks, refunds |
| `delivery` | 8063 | WebFlux + R2DBC + Kafka | Delivery tracking |
| `profile` | 8064 | WebFlux + R2DBC + Kafka | Profiles & delivery addresses |
| `storage` | 8065 | WebFlux + S3 (Garage) | Presigned uploads: avatars, files, product media |
| `notification` | 8066 | WebFlux + R2DBC + Kafka | Transactional email (Resend); owns all copy |
| `balance` | 8067 | WebFlux + R2DBC + Kafka | CHF ledger; pays orders as a payment provider |
| `accounting` | 8068 | WebFlux + R2DBC + Kafka | The books: journal entries derived from events. Read-only, admin-only, never moves money |
| `ui-shop` | 5173 | React + Vite + oidc-client-ts | SPA storefront with Stripe Elements |
| `ui-demo` | — | Static (nginx) | Alternate storefront, same backend |

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
| `orders.events` | Shop (outbox) | Payment, Delivery, Accounting | 7 days (broker default) |
| `payments.events` | Payment (outbox) | Shop, Balance, Accounting | 7 days (broker default) |
| `delivery.events` | Delivery (outbox) | Shop, Accounting | 7 days (broker default) |
| `identity.events` | auth-server (fire-and-forget) | Notification, Profile | **1 hour** — it carries live password-reset tokens |

> `identity.events` is declared explicitly with `segment.ms=600000` alongside `retention.ms=3600000`. Kafka only deletes *closed* segments and the default roll is 7 days, so on a low-volume topic `retention.ms` on its own deletes nothing.

## API routes

The gateway itself permits every exchange — the **Auth** column is what the
receiving service enforces on the `Bearer` token the caller sends.

| Path | Auth | Proxied to |
|------|------|------------|
| `/api/greetings/**` | Public | Greetings service |
| `/api/secured/**` | JWT required | Greetings service |
| `/api/shop/products` | GET public, POST/DELETE ADMIN | Shop service |
| `/api/shop/categories` | GET public, POST/DELETE ADMIN | Shop service |
| `/api/shop/orders` | JWT required | Shop service |
| `/api/shop/orders/{id}/refund` | JWT required (admin: any paid order; user: own order, failed delivery only) | Shop service |
| `/api/payments/intent/**` | Public (clientSecret fetch) | Payment service |
| `/api/payments/webhook/{provider}` | Public (provider signature) | Payment service |
| `/api/payments/providers` | Public | Payment service |
| `/api/delivery/**` | JWT required | Delivery service |
| `/api/profiles/**` | JWT required | Profile service |
| `/api/balance/**` | JWT required | Balance service |
| `/api/accounting/**` | JWT required, **ROLE_ADMIN** | Accounting service |
| `/api/storage/**` | JWT required | Storage service (route retries on failure) |
| `/auth/**` | Public | Auth server (login, token, JWKS, discovery) |
| `/v3/api-docs/**`, `/swagger-ui/**` | Public | Shop service |

## Stripe setup (payment service)

The payment service requires two env vars for Stripe integration:

```bash
export STRIPE_SECRET_KEY=sk_test_...   # from Stripe dashboard (API keys)
export STRIPE_WEBHOOK_SECRET=whsec_... # from Stripe CLI (see below)
```

To get `STRIPE_WEBHOOK_SECRET`, run the Stripe CLI in a terminal:

```bash
stripe listen --forward-to localhost:8080/api/payments/webhook/stripe
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