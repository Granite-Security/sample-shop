# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository layout

This repo is a multi-service project. The working directory is `gateway/`, but all services live as siblings:

```
granite-security/
├── auth-server/   (port 9090) — Spring Authorization Server
├── gateway/       (port 8080) — Spring Cloud Gateway (this module)
├── greetings/     (port 8060) — backend microservice
├── compose.yaml   — orchestrates all services + postgres
└── README.md
```

## Commands

All commands run from a service's own directory with the Gradle wrapper.


Google OAuth2 requires `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET` env vars for `docker compose up`.

## Architecture

### Request flow

```
Browser → gateway:8080 (OAuth2 client)
             ↓ authorization code flow
         auth-server:9090 (Spring Authorization Server)
             ↓ JWT issued
         gateway relays JWT → greetings:8060 (OAuth2 resource server)
```

### auth-server

- Acts as the OIDC provider for the whole system.
- Supports two login methods: form login (local DB users) and Google OAuth2 federated login.
- RSA key pair is **generated fresh on each startup** — existing JWTs become invalid after a restart.
- Injects a custom `roles` claim into every issued JWT (`OAuth2TokenCustomizer` in `SecurityConfig`).
- User store: PostgreSQL (`authdb`), schema managed by Liquibase. Seed users: `user` (ROLE_USER), `admin` (ROLE_ADMIN), `manager` (ROLE_USER + ROLE_ADMIN). Default passwords match usernames.
- `RegisteredClientRepository` is in-memory; the gateway client (`oidc-client`) is configured here.

### gateway

- Spring Cloud Gateway (WebFlux-based, reactive).
- Two routes in `RouterConfig`:
  - `/api/greetings/**` — proxies to greetings service, **no token relay**, public access.
  - `/api/secured/**` — proxies to greetings service with `TokenRelayGatewayFilterFactory` (forwards the JWT as a Bearer token).
- Security (`GateSec`): `/api/greetings/**` is permit-all; everything else requires OAuth2 login.
- OIDC provider URI defaults to `http://localhost:9090`; overridden via `OIDC_ISSUER_URI` env var in Docker.

### greetings

- Functional routing style (`RouterFunction` / handler classes), not `@RestController`.
- Acts as an OAuth2 resource server; validates JWTs from auth-server.
- `GreetingsSec` maps both `scope` claims (→ `SCOPE_*`) and `roles` claims (→ `ROLE_*`) from the JWT to Spring Security authorities.
- `/api/catalog/**` requires `ROLE_USER` or `ROLE_ADMIN`.

## Key environment variables

| Variable | Default | Used by |
|---|---|---|
| `OIDC_ISSUER_URI` | `http://localhost:9090` | gateway |
| `OIDC_CLIENT_SECRET` | `iaka` (docker) / `secret` (local) | gateway |
| `GREETINGS_MICROSERVICE` | `http://localhost:8060` | gateway |
| `AUTH_SERVER_ISSUER` | `http://localhost:9090` | auth-server |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | (required for Google login) | auth-server |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/authdb` | auth-server |
