# Granite Security

Microservices demo platform with OAuth2/OIDC authentication, Spring Cloud Gateway, and a reactive e-commerce shop.

## Architecture

```
                            Stripe
                              │
                webhook ──────┤
                              ▼
Browser (5173) ── Gateway (8080) ──┬── Auth Server (9090) ── PostgreSQL (5432)
     ui-shop      (Spring Cloud    │                          authdb
     (React +      Gateway)        ├── Greetings (8060)
     Stripe                        │
     Elements)                     ├── Shop (8061) ────────── PostgreSQL (5433)
                                    │    (WebFlux + R2DBC)     shopdb
                                    │       │       ▲
                                    │       │       │
                                    │   ┌───▼───────┴────┐
                                    │   │     Kafka        │
                                    │   │ (orders.events,   │
                                    │   │  payments.events) │
                                    │   └─▲──┬──────┬────┘
                                    │     │  │      │
                                    │     │  ▼      ▼
                                    ├── Payment (8062)   Delivery (8063)
                                    │    (WebFlux + R2DBC  (WebFlux + R2DBC
                                    │     + Stripe API)     + Kafka consumer)
                                    │     │                 │
                                    │     ▼                 ▼
                                    │  PostgreSQL (5434)  PostgreSQL (5435)
                                    │  paymentdb          deliverydb
                                    │
                                    └── Profile (8064) ──── PostgreSQL (5436)
                                         (WebFlux + R2DBC)    profiledb
```

| Service | Port | Stack | Role |
|---------|------|-------|------|
| `gateway` | 8080 | Spring Cloud Gateway (WebFlux) | OAuth2 client, route & token relay |
| `auth-server` | 9090 | Spring Authorization Server | OIDC provider, form + Google login |
| `greetings` | 8060 | Spring WebFlux | Public + secured endpoints (demo) |
| `shop` | 8061 | Spring WebFlux + R2DBC | E-commerce catalog, orders |
| `payment` | 8062 | Spring WebFlux + R2DBC + Stripe API | Payment intent creation, Stripe webhooks |
| `delivery` | 8063 | Spring WebFlux + R2DBC + Kafka | Delivery tracking, consumes `orders.events` |
| `profile` | 8064 | Spring WebFlux + R2DBC | User profile & delivery address management |
| `ui-shop` | 5173 | React + Vite + oidc-client-ts | SPA storefront with Stripe Elements |
| `postgres` | 5432 | PostgreSQL 17 | Auth server database |
| `shop-postgres` | 5433 | PostgreSQL | Shop database |
| `payment-postgres` | 5434 | PostgreSQL | Payment database |
| `delivery-postgres` | 5435 | PostgreSQL | Delivery database |
| `profile-postgres` | 5436 | PostgreSQL | Profile database |

## Prerequisites

- **Java 25** (each service builds with its own Gradle wrapper)
- **Docker** (for PostgreSQL databases and full-stack compose)
- **Google OAuth2 credentials** (optional — for Google login)

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

| Kafka topic | Producer | Consumers |
|-------------|----------|-----------|
| `orders.events` | Shop (outbox) | Payment, Delivery |
| `payments.events` | Payment (outbox) | Shop |

## API routes

| Path | Auth | Proxied to |
|------|------|------------|
| `/api/greetings/**` | Public | Greetings service |
| `/api/secured/**` | JWT required | Greetings service (token relayed) |
| `/api/shop/products` | GET public, POST/DELETE ADMIN | Shop service |
| `/api/shop/categories` | GET public, POST/DELETE ADMIN | Shop service |
| `/api/shop/orders` | JWT required | Shop service (token relayed) |
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
| `STRIPE_CURRENCY` | `usd` | payment |
| `DELIVERY_R2DBC_URL` | `r2dbc:postgresql://localhost:5435/deliverydb` | delivery |
| `PROFILE_R2DBC_URL` | `r2dbc:postgresql://localhost:5436/profiledb` | profile |

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
├── smoke-tests/         — Smoke test scripts
├── plans/               — Planning documents and change logs
├── compose.yaml         — Docker Compose orchestration (all services + Kafka)
├── events.md            — Kafka event schema documentation
└── Master-Plan.md       — Full development roadmap
```

## OpenAPI documentation

Swagger UI is available at `/swagger-ui/index.html` on both the shop (8061) and proxied through the gateway (8080). The OpenAPI spec is at `/v3/api-docs`.
