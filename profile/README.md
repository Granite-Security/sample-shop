# profile microservice

Port **8064**. User profiles, delivery addresses, files and in-app messages —
WebFlux + R2DBC over `profiledb`. Also the **orchestrator** for blocking and
deleting users: it decides, auth-server only executes.

## API

`ProfileRoute` (functional routing); rules in `ProfileSec`.

| Endpoint | Auth |
|----------|------|
| `GET`/`PUT /api/profiles/me` | authenticated — always the token's own subject |
| `PUT`/`DELETE /api/profiles/me/avatar`, `/avatar/source` | authenticated |
| `GET`/`POST`/`PUT`/`DELETE /api/profiles/me/addresses[/{id}]` | authenticated |
| `GET`/`POST`/`DELETE /api/profiles/me/files[/{id}]` | authenticated |
| `GET`/`POST`/`DELETE /api/profiles/me/messages/**` | authenticated |
| `GET /api/profiles`, `/api/profiles/{username}` | ADMIN |
| `GET /api/profiles/admin/users`, `/admin/orphans` | ADMIN |
| `POST /api/profiles/admin/users/{u}/block`, `/unblock` | ADMIN |
| `DELETE /api/profiles/admin/users/{u}` | ADMIN — the delete saga below |
| `/api/profiles/internal/**` | `SCOPE_internal` — service-to-service, no user |

Everything under `/me` derives the username from the JWT, never from the body.
Avatars store a `source` (`UPLOAD`, `GOOGLE`, `NONE`) plus an object key — the
bytes live in `storage`, not here. Messages are plain rows: no Kafka, no outbox.

## Events

| Topic | Direction | Fields that matter |
|-------|-----------|--------------------|
| `identity.events` | in | `type` — only `UserRegistered` is acted on; `username` — the upsert key |

Provisioning is an upsert keyed by username, so no dedupe table is needed: a
replayed event rewrites the same row. Profile **produces nothing**.

## Outbound calls

Unusually, profile is an OAuth2 **client** as much as a resource server. All
calls are `client_credentials` straight to auth-server's token endpoint.

| Registration | Scope | Calls | When |
|--------------|-------|-------|------|
| `identity-admin-client` | `identity.admin` | auth-server `/api/internal/users/**` | list, block, unblock, delete users |
| `shop-client` | `internal` | shop, payment, delivery `/internal/**` | purge eligibility + purge; orphan sweep |
| `storage-client` | `internal` | storage `/api/storage/objects` | delete objects of a deleted user |

`identity-admin` is a **separate credential held only by profile** — it is the
one registration that can administer identities, and is deliberately not the
shared `internal-service` secret.

### The delete saga

`DELETE /api/profiles/admin/users/{u}` spans three services' databases over
HTTP. It is a saga, not a transaction, and it has no compensation:

```
guard rails (not self, not the last enabled admin, target exists)
  → block in auth-server            (bounds the race to the token lifetime)
  → shop: purge-eligibility         → paid orders? stop, blocked is the outcome
  → shop: purge orders              (shop re-checks, then emits OrdersPurged)
  → auth-server: delete user
  → profile's own rows              (one local transaction, deliberately last)
  → storage objects                 (best-effort; failures become orphans)
```

Profile's rows go last so a failure upstream leaves the user still described;
storage goes after the commit so an outage orphans objects rather than
destroying files of a user whose deletion then failed. `GET /admin/orphans`
finds what a half-finished cascade left behind — it reports, never deletes.

## Configuration

| Variable | Purpose |
|----------|---------|
| `PROFILE_R2DBC_URL` / `_USERNAME` / `_PASSWORD` | Runtime DB (`PROFILE_JDBC_*` is Liquibase only) |
| `KAFKA_BOOTSTRAP_SERVERS` | Broker |
| `MICROSERVICES_{SHOP,PAYMENT,DELIVERY,STORAGE}_URI` | Call targets |
| `IDENTITY_ADMIN_BASE_URI`, `AUTH_SERVER_TOKEN_URI` | auth-server API and token endpoint — direct, not via the gateway |
| `INTERNAL_CLIENT_ID` / `_SECRET` | Shared internal-scope credential |
| `IDENTITY_ADMIN_CLIENT_ID` / `_SECRET` | Identity-admin credential; must match auth-server's |
| `JWT_JWK_SET_URI`, `TRUSTED_JWT_ISSUERS` | Token validation (see greetings) |

```bash
./gradlew bootRun
./gradlew test          # repository tests need Docker (Testcontainers)
```
