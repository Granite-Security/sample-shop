# profile microservice

Port **8064**. User profiles, delivery addresses, files and in-app messages —
WebFlux + R2DBC over `profiledb`. Also the **orchestrator** for blocking and
deleting users: it decides, auth-server only executes.

## API

`ProfileRoute` (functional routing); rules in `ProfileSec`.

| Endpoint | What it does | Auth |
|----------|--------------|------|
| `GET`/`PUT /api/profiles/me` | Reads and updates the caller's own email, first/last name and display name. | authenticated — always the token's own subject |
| `PUT`/`DELETE /api/profiles/me/avatar`, `/avatar/source` | Registers the object key of an uploaded avatar, switches the displayed picture between `UPLOAD`, `GOOGLE` and `NONE`, or clears it. | authenticated |
| `GET`/`POST`/`PUT`/`DELETE /api/profiles/me/addresses[/{id}]` | CRUD over the caller's delivery addresses, the ones shop and delivery later resolve by id. | authenticated |
| `GET`/`POST`/`DELETE /api/profiles/me/files[/{id}]` | Lists, registers and deletes the metadata rows describing the caller's objects in `storage`, with a `?contentHash` lookup that lets the browser skip re-uploading a duplicate. | authenticated |
| `GET`/`POST`/`DELETE /api/profiles/me/messages/**` | Sends and reads user-to-user messages, plus recipient search, unread count, mark-as-read and per-side delete. | authenticated |
| `GET /api/profiles`, `/api/profiles/{username}` | Lists every profile, or fetches one by username. | ADMIN |
| `GET /api/profiles/admin/users`, `/admin/orphans` | Lists each identity joined to its profile and block state; reports the shop, payment, delivery and storage rows a half-finished delete left behind (never deletes them). | ADMIN |
| `POST /api/profiles/admin/users/{u}/block`, `/unblock` | Revokes or restores a user's ability to sign in, guarding against blocking yourself or the last enabled admin. | ADMIN |
| `DELETE /api/profiles/admin/users/{u}` | Runs the cross-service erasure, falling back to a permanent block when the user has paid orders. | ADMIN — the delete saga below |
| `/api/profiles/internal/**` | Lets another service confirm a username exists (balance, before a transfer) or fetch one delivery address by id. | `SCOPE_internal` — service-to-service, no user |

Everything under `/me` derives the username from the JWT, never from the body.
Avatars store a `source` (`UPLOAD`, `GOOGLE`, `NONE`) plus an object key — the
bytes live in `storage`, not here. Messages are plain rows: no Kafka, no outbox.

## Events

| Topic | Direction | Fields that matter |
|-------|-----------|--------------------|
| `identity.events` | in | `type` — only `UserRegistered` is acted on; `username` — the upsert key |
| `shop.notifications` | in | `eventType` — only `OrderPlacedNotice`; `username` — who ordered; `orderId` — the dedupe key; `occurredAt` — dropped if older than `profile.order-notices.max-age` |

Provisioning is an upsert keyed by username, so no dedupe table is needed: a
replayed event rewrites the same row. Profile **produces nothing**.

`OrderPlacedNotice` gets the opposite treatment, because writing a message is not
idempotent — a redelivery is a second message in admin's inbox, not the same row
rewritten. `processed_order_notice` is claimed (`INSERT … ON CONFLICT DO NOTHING`)
**before** the message is written, so a crash between the two drops a courtesy
notice rather than duplicating one. The consumer reads from `earliest`, so notices
older than `max-age` are dropped: a group that lost its offsets would otherwise
replay a day of orders into the inbox as though they had just happened.

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
| `ORDER_NOTICE_RECIPIENT` / `_SENDER` / `_MAX_AGE` | Who hears about new orders (default `admin`), the reserved sender (`system`), and how old a notice may be (`PT24H`) |
| `MICROSERVICES_{SHOP,PAYMENT,DELIVERY,STORAGE}_URI` | Call targets |
| `IDENTITY_ADMIN_BASE_URI`, `AUTH_SERVER_TOKEN_URI` | auth-server API and token endpoint — direct, not via the gateway |
| `INTERNAL_CLIENT_ID` / `_SECRET` | Shared internal-scope credential |
| `IDENTITY_ADMIN_CLIENT_ID` / `_SECRET` | Identity-admin credential; must match auth-server's |
| `JWT_JWK_SET_URI`, `TRUSTED_JWT_ISSUERS` | Token validation (see greetings) |

```bash
./gradlew bootRun
./gradlew test          # repository tests need Docker (Testcontainers)
```
