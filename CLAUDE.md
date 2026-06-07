# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Granite Security is a multi-service OAuth2/OIDC authentication system with three Spring Boot services:

```
Internet → Gateway (8080) → Greetings (8060)
                  ↕
            Auth Server (9090)
                  ↕
             PostgreSQL (5432)
```

- **Auth Server**: OAuth2 Authorization Server — issues JWTs, handles form login + federated Google OAuth2, stores users/roles in PostgreSQL with Liquibase migrations
- **Gateway**: Spring Cloud Gateway (WebFlux) — OAuth2 Client, routes requests, relays tokens to downstream services
- **Greetings**: Spring WebFlux Resource Server — validates JWTs, exposes `/api/greetings` (public) and `/api/secured` (authenticated)

## Build & Run Commands

Each service is an independent Gradle project. Run commands from within each service directory.

### Local development (Docker Compose)
```bash
docker compose -f compose.yaml up --build
```
Requires hosts file entries (see README.md) and a `.env` file with `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET`.

### Per-service Gradle commands
```bash
# Build (skip tests)
./gradlew build -x test

# Run tests
./gradlew test

# Run a single test class
./gradlew test --tests "org.granitesecurity.greetings.SomeTest"

# Run the service locally
./gradlew bootRun
```

## Architecture Notes

### Security flow
1. User hits Gateway → Gateway initiates OAuth2 login with Auth Server
2. Auth Server authenticates (form login or Google federated), issues JWT with custom `roles` claim
3. Gateway stores token and relays it (as `Authorization: Bearer`) to Greetings
4. Greetings validates JWT against Auth Server's JWKS endpoint and extracts both `scopes` (→ `SCOPE_*`) and `roles` (→ `ROLE_*`) as Spring Security authorities

### JWT custom claims
The Auth Server injects a `roles` list into JWT tokens via a custom `OAuth2TokenCustomizer`. The Greetings service has a custom `JwtAuthenticationConverter` that reads both `scope` and `roles` claims and maps them to `GrantedAuthority` instances.

### Reactive stack
Gateway and Greetings use Spring WebFlux (non-blocking). Greetings uses functional routing (`RouterFunctions.route()`) and reactive handlers returning `Mono<ServerResponse>`. Thread-local context propagation is configured explicitly in `ContextPropagationConfiguration`.

### Observability
Greetings is instrumented with OpenTelemetry (logs via Logback appender, traces and metrics via OTLP). Key config classes: `OpenTelemetryConfiguration`, `ContextPropagationConfiguration`, `InstallOpenTelemetryAdapter`.

### Configuration profiles
Services use `application.yaml` for defaults and a `docker` profile for Docker Compose overrides. Environment variables follow 12-factor conventions (e.g., `SPRING_PROFILES_ACTIVE=docker`, `AUTH_SERVER_URL`, `GREETINGS_URI`).

## Tech Stack

- Java 25, Spring Boot 4.0.6, Spring Cloud 2025.1.1
- Spring Security OAuth2 (Authorization Server, Resource Server, Client)
- Spring WebFlux (reactive) for Gateway and Greetings
- Spring Data JPA + PostgreSQL + Liquibase for Auth Server
- Gradle 8.x with Kotlin DSL
- OpenTelemetry for observability
- Docker / Docker Compose for local orchestration

## Known Debt

See `todo/debt.md` for tracked items, including:
- Auth Server user/role storage needs full database integration
- On-behalf-of flow not yet implemented
- Azure provider configuration pending
