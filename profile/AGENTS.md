# AGENTS.md

Guidance for AI coding agents working in this repository.

## Project overview

`profile` is a reactive microservice in the `granite-security` system. It manages
user profiles and delivery addresses. It is a Spring Boot application built with
Gradle (Kotlin DSL), running on Java 25.

Key characteristics:

- **Stack**: Spring Boot 4.1.0, Spring WebFlux (functional endpoints, no
  `@RestController`), Spring Data R2DBC, Spring Security OAuth2 Resource Server,
  Liquibase, PostgreSQL, Lombok, Project Reactor.
- **Build**: Gradle 9.5.1 via the wrapper (`./gradlew`). The GraalVM native
  buildtools plugin (`org.graalvm.buildtools.native`) is applied but no native
  build is wired into CI/Docker — the Docker image ships a regular JVM jar.
- **Runtime**: Netty (WebFlux), default port `8064` (`PROFILE_SERVER_PORT`).
- **Database**: PostgreSQL. Runtime access is reactive via R2DBC; Liquibase
  migrations run over JDBC (both drivers are on the classpath).
- **Auth**: JWT bearer tokens validated against a JWKS endpoint from an external
  auth service. Issuers are checked against a comma-separated allow-list
  (`jwt.trusted-issuers`), not a single issuer URI — see the comment in
  `security/ProfileSec.java`, which refers to the sibling `PaymentSec` service
  for rationale.
- **Observability**: the Docker image attaches the OpenTelemetry Java agent
  (`-javaagent`) instead of the OTel Spring Boot starter, because the starter's
  WebFlux module is broken against Spring Framework 7. It is configured via
  `OTEL_*` environment variables. Note: the Dockerfile comment references
  `docs/observability/observability.md`, which does not exist in this repo.

## Repository layout

```
src/main/java/org/granitesecurity/profile/
├── ProfileApplication.java      # @SpringBootApplication entry point
├── route/ProfileRoute.java      # functional RouterFunction bean — all HTTP routes
├── handler/                     # WebFlux handlers (ProfileHandler, AddressHandler)
├── service/                     # business logic (ProfileService, AddressService)
├── repository/                  # Spring Data R2DBC ReactiveCrudRepository interfaces
├── domain/                      # R2DBC entities: UserProfile, DeliveryAddress (Lombok @Getter/@Setter)
├── dto/                         # Java records for request/response bodies
└── security/ProfileSec.java     # SecurityWebFilterChain, JWT decoder, CORS config

src/main/resources/
├── application.yaml             # all config, env-var-overridable
└── db/changelog/                # Liquibase: db.changelog-master.yaml + numbered SQL changesets

src/test/java/.../ProfileApplicationTests.java   # single @SpringBootTest contextLoads smoke test
```

## API surface

Defined in `route/ProfileRoute.java`:

- `GET/PUT /api/profiles/me` — current user's profile (created lazily on first access)
- `GET/POST /api/profiles/me/addresses` — list/create delivery addresses
- `PUT/DELETE /api/profiles/me/addresses/{id}` — update/delete own address
- `GET /api/profiles/internal/{username}/addresses/{id}` — service-to-service
  lookup, requires the `SCOPE_internal` authority
- `GET /api/profiles` and `GET /api/profiles/{username}` — admin-only listings
  (`hasRole("ADMIN")`) for the admin Users page; the second returns 404 when
  the username has no profile

Authorization rules (in `ProfileSec`): `/api/profiles/internal/**` requires
`SCOPE_internal`, GETs on `/api/profiles` and `/api/profiles/{username}`
require `ROLE_ADMIN`, other `/api/profiles/**` require any authenticated JWT,
everything else is permitted. CSRF is disabled (stateless JWT API). The JWT
subject (`sub` claim) is used as the `username` throughout.

## Build and run commands

Always use the Gradle wrapper:

```bash
./gradlew build          # full build incl. tests
./gradlew build -x test  # build without tests (what the Dockerfile does)
./gradlew test           # run tests (JUnit 5 / JUnit Platform)
./gradlew bootRun        # run the app locally
./gradlew bootJar        # produces build/libs/profile-0.0.1-SNAPSHOT.jar
docker build -t profile .  # multi-stage image with OTel javaagent
```

### Local dependencies

`compose.yaml` provides a PostgreSQL container (`profiledb` / `myuser` /
`secret`, exposed on host port **5436** — not the default 5432). Start it with
`docker compose up -d`. Note that Spring Boot's docker-compose integration is
explicitly disabled (`spring.docker.compose.enabled: false`), so the container
must be started manually before `bootRun`.

### Configuration

Everything in `application.yaml` is overridable via environment variables:
`PROFILE_SERVER_PORT`, `PROFILE_R2DBC_URL`, `PROFILE_R2DBC_USERNAME`,
`PROFILE_R2DBC_PASSWORD`, `PROFILE_JDBC_URL`, `PROFILE_JDBC_USERNAME`,
`PROFILE_JDBC_PASSWORD`, `JWT_JWK_SET_URI`, `TRUSTED_JWT_ISSUERS`, plus
`cors.allowed-origins` (read via `@Value` in `ProfileSec`). Local defaults
point at `localhost:5436` for Postgres and `localhost:8080`/`9090` for the
auth service.

## Code conventions

- **Reactive everywhere**: services and handlers return `Mono`/`Flux`; never
  block. Repositories extend `ReactiveCrudRepository`.
- **Functional WebFlux**: add endpoints by editing the `RouterFunction` bean in
  `ProfileRoute` and adding a method to a handler — do not introduce
  annotation-based controllers.
- **Layering**: route → handler (HTTP/ServerResponse concerns, principal
  extraction) → service (business logic, entity↔DTO mapping) → repository.
- **DTOs are Java records**; entities are Lombok `@Getter @Setter` classes
  annotated with `@Table`/`@Column`. Package is `org.granitesecurity.profile`.
- **Data ownership**: every query for user data is scoped by `username` (e.g.
  `findByIdAndUsername`, `deleteByIdAndUsername`) — preserve this when adding
  repository methods so users can only touch their own rows. The one exception
  is the admin Users page: `GET /api/profiles` (list all) and
  `GET /api/profiles/{username}` deliberately bypass username scoping; both are
  restricted in `ProfileSec` to `hasRole("ADMIN")`.
- **"Default address" invariant**: `AddressService.unsetDefaultIfNeeded` clears
  other default addresses before saving a new default; keep this behavior when
  modifying address create/update logic.
- **Constructor injection** is used throughout; no field `@Autowired`.

## Database migrations

Liquibase formatted SQL changesets live in `src/main/resources/db/changelog/`,
numbered sequentially (`001-...`, `002-...`) and included from
`db.changelog-master.yaml`. Each changeset has an id, an author (`adrian`), and
a `--rollback` statement. New schema changes must follow the same pattern: add
a new numbered file and register it in the master changelog. Do not edit
already-applied changesets.

## Testing

- Tests run on JUnit Platform (`useJUnitPlatform()`). Spring Boot 4 test
  starters for WebFlux, R2DBC, Liquibase, and the OAuth2 resource server are on
  the test classpath.
- Currently the only test is a `contextLoads` smoke test
  (`ProfileApplicationTests`). Note that `@SpringBootTest` boots the full
  context, which requires a reachable PostgreSQL and runs Liquibase — start
  `docker compose up -d` before running `./gradlew test`.
- There is no CI configuration in this repository (no `.github/` workflows);
  the README only notes "testing ci cd".

## Security considerations

- This service never issues tokens; it only validates JWTs via JWKS. Issuer
  validation is an explicit allow-list (`jwt.trusted-issuers`) — when changing
  `ProfileSec`, keep both the timestamp validator and the issuer validator in
  the `DelegatingOAuth2TokenValidator`.
- Authorities are derived from both the `scope`/`scp` claim (as `SCOPE_*`) and
  a `roles` claim (as `ROLE_*`); endpoint rules rely on `SCOPE_internal` for
  internal routes.
- CORS is permissive within a configurable origin list and allows credentials;
  do not widen it to `*` origins.
- Default credentials in `application.yaml`/`compose.yaml` are local-dev only;
  production values come from environment variables.
