# Granite Security

Microservices demo platform with OAuth2/OIDC authentication, Spring Cloud Gateway, and a reactive e-commerce shop.

## Architecture

```
Browser → Gateway (8080) ──┬── Auth Server (9090) ── PostgreSQL (5432)
                            ├── Greetings (8060)
                            └── Shop (8061) ────────── Shop PostgreSQL (5433)
```

| Service | Port | Stack | Role |
|---------|------|-------|------|
| `gateway` | 8080 | Spring Cloud Gateway (WebFlux) | OAuth2 client, route & token relay |
| `auth-server` | 9090 | Spring Authorization Server | OIDC provider, form + Google login |
| `greetings` | 8060 | Spring WebFlux | Public + secured endpoints (demo) |
| `shop` | 8061 | Spring WebFlux + R2DBC | E-commerce catalog, orders |
| `postgres` | 5432 | PostgreSQL 17 | Auth server database |
| `shop-postgres` | 5433 | PostgreSQL | Shop database |

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
```

Swagger UI is proxied through the gateway at [http://localhost:8080/swagger-ui/index.html](http://localhost:8080/swagger-ui/index.html) or direct at [http://localhost:8061/swagger-ui/index.html](http://localhost:8061/swagger-ui/index.html).

## Testing

```bash
# Run a service's full test suite (requires Docker for DB-backed tests)
cd shop && ./gradlew test

# Repository tests use Testcontainers and require Docker
# Service/core tests use mocks and run without Docker
```

## API routes

| Path | Auth | Proxied to |
|------|------|------------|
| `/api/greetings/**` | Public | Greetings service |
| `/api/secured/**` | JWT required | Greetings service (token relayed) |
| `/api/shop/products` | GET public, POST/DELETE ADMIN | Shop service |
| `/api/shop/categories` | GET public, POST/DELETE ADMIN | Shop service |
| `/api/shop/orders` | JWT required | Shop service (token relayed) |
| `/v3/api-docs/**`, `/swagger-ui/**` | Public | Shop service |

## Key environment variables

| Variable | Default | Service |
|----------|---------|---------|
| `OIDC_ISSUER_URI` | `http://localhost:9090` | gateway |
| `OIDC_CLIENT_SECRET` | `secret` | gateway |
| `GREETINGS_MICROSERVICE` | `http://localhost:8060` | gateway |
| `SHOP_MICROSERVICE` | `http://localhost:8061` | gateway |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | — | auth-server |
| `AUTH_ISSUER_URI` | `http://localhost:9090` | greetings, shop |
| `SHOP_R2DBC_URL` | `r2dbc:postgresql://localhost:5433/shopdb` | shop |

## Project layout

```
granite-security/
├── auth-server/         — Spring Authorization Server
├── gateway/             — Spring Cloud Gateway
├── greetings/           — WebFlux demo microservice
├── shop/                — E-commerce shop (WebFlux + R2DBC)
├── smoke-tests/   — Smoke test scripts
├── compose.yaml         — Docker Compose orchestration
└── Master-Plan.md       — Full development roadmap
```

## OpenAPI documentation

Swagger UI is available at `/swagger-ui/index.html` on both the shop (8061) and proxied through the gateway (8080). The OpenAPI spec is at `/v3/api-docs`.
