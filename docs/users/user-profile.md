# User profile cabinet — implementation plan

Status: **not started** · Last updated: 2026-07-27

Goal: give a logged-in user a real "cabinet" — a profile page where they can
upload and manage files, set a display name, and change their password, with a
Resend-delivered email notification whenever the password changes.

---

## Decisions taken up front

| Question | Decision | Why |
| --- | --- | --- |
| Change *username*? | **No — a new `display_name` field instead.** The login username stays immutable. | The username is the JWT `sub`. `profile.user_profile.username`, `profile.delivery_address.username` and `shop.customer_order.username` all key off it, and issued JWTs embed it. A real rename is a non-transactional cascade across three databases plus forced re-login; a display name is a one-column change inside `profile` with zero cross-service risk. |
| File visibility | **Public URLs**, same bucket/flow as product media. | Reuses the working `storage` service, the Garage `s3_web` public read path and `media.granite-security.org`. Trade-off accepted: anyone holding the (unguessable UUID) URL can read the file — see [Security notes](#security-notes). |
| Password change owner | **`auth-server` owns the endpoint** (`PUT /auth/api/me/password`); `profile` only sends the email. | `auth-server` is the sole owner of the `users` table and the `PasswordEncoder`. Nothing else may see a raw password or a hash — this is the same reasoning as [`registrations.md`](registrations.md). |

> Deviation from the original ask, stated plainly: you asked for *all* of this
> in the `profile` microservice. Display name and files land there in full. The
> password change cannot — moving it would mean shipping raw passwords across a
> service boundary. `profile` still owns the *notification* half of it, so
> everything email-shaped stays in one service.

### Services touched

- `profile` (8064) — display name, file cabinet, Resend email, internal notify endpoint. **Most of the work.**
- `auth-server` (9090) — password-change endpoint + outbound notify call.
- `storage` (8065) — one new upload scope, one authorization rule.
- `ui-shop` (5173) — the cabinet UI.
- infra — compose env, k8s config/secret, Resend DNS.

---

## Phase 1 — `profile` schema

New changelog `profile/src/main/resources/db/changelog/003-user-cabinet.sql`,
registered in `db.changelog-master.yaml` after `002-seed-profiles.sql`.

```sql
--liquibase formatted sql

--changeset moldo:003-add-display-name
ALTER TABLE user_profile ADD COLUMN display_name VARCHAR(64);
--rollback ALTER TABLE user_profile DROP COLUMN display_name;

--changeset moldo:003-create-user-file
CREATE TABLE user_file (
    id           BIGSERIAL    PRIMARY KEY,
    username     VARCHAR(64)  NOT NULL,
    file_name    VARCHAR(255) NOT NULL,
    object_key   VARCHAR(512) NOT NULL UNIQUE,
    url          VARCHAR(1024) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    size_bytes   BIGINT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_user_file_username ON user_file(username);
--rollback DROP TABLE user_file;
```

Notes:
- `display_name` is **not** unique and must never be used as a lookup or
  authorization key — only `username` (the JWT `sub`) identifies a user.
- `object_key` is `UNIQUE` so the same storage object can't be registered twice
  (see the claim check in Phase 3).
- Column is named `object_key`, not `key` — `key` is a reserved-ish word in
  enough tooling to be worth avoiding.

**Done when:** `./gradlew bootRun` in `profile` applies both changesets cleanly
against a fresh `profiledb`, and `liquibase` rollback statements are present.

---

## Phase 2 — Display name (`profile`)

Files:

| File | Change |
| --- | --- |
| `domain/UserProfile.java` | add `@Column("display_name") private String displayName;` |
| `dto/UpdateProfileRequest.java` | add `displayName` component |
| `dto/ProfileResponse.java` | add `displayName` component |
| `service/ProfileService.java` | set `displayName` in `updateProfile`, map it in `toResponse` |
| `service/ProfileServiceTest.java` | constructor churn on both records — update every construction site |

Validation (in `ProfileService`, before save — `profile` has no
`spring-boot-starter-validation` today; either add it and annotate the record,
or hand-roll in the service. **Recommend hand-rolling** to avoid a new
dependency for one field):

- trim; empty string → store `NULL` (means "fall back to username")
- length 2–64
- pattern `^[\p{L}\p{N} ._'-]+$` — letters (any script), digits, space, `. _ ' -`
- reject on failure with `ResponseStatusException(BAD_REQUEST, …)`, matching the
  existing error style in this service

`ProfileResponse.displayName` may be null; **the UI**, not the service, decides
the fallback (`displayName ?? username`) so admin views can still show the real
username.

**Done when:** `PUT /api/profiles/me` with `{"displayName":"Adrian M"}` round-trips,
and an invalid value returns 400.

---

## Phase 3 — File cabinet (`profile` + `storage`)

### 3a. `storage`: a new scope for user uploads

`storage/src/main/java/org/granitesecurity/storage/service/StorageService.java`:

```java
private static final Set<String> ALLOWED_SCOPES = Set.of("products", "user-files");
```

Content-type allow-list for `user-files` is **narrower** than for products —
`image/jpeg`, `image/png`, `image/webp`, `application/pdf`, `text/plain`. Make
the allow-list per-scope (a `Map<String, Set<String>>`) rather than one global
set, so a user can never upload a scope-inappropriate type.

`security/StorageSec.java` — presign/delete currently require
`hasAnyRole("ADMIN","MANAGER")`. Widen to:

```java
.pathMatchers("/api/storage/presign", "/api/storage/objects")
    .hasAnyAuthority("ROLE_ADMIN", "ROLE_MANAGER", "SCOPE_internal")
```

and enforce in `StorageService` that a caller whose only authority is
`SCOPE_internal` may use **only** the `user-files` scope (and that
ADMIN/MANAGER cannot be tricked into signing a `user-files` key on someone's
behalf — they simply have no reason to, so restrict `user-files` to
`SCOPE_internal` exclusively). Pass the authorities down from the handler; do
not read the security context inside the service (keeps `StorageServiceTest`
plain Mockito).

The generated key stays `user-files/{uuid}/{sanitizedFileName}` — ownership is
recorded in `profile.user_file`, not in the key. The bucket is public, so a
per-user key prefix would buy nothing but would leak usernames into URLs.

### 3b. `profile`: internal client to call `storage`

`profile` needs to mint a client-credentials token with the `internal` scope.

- `build.gradle.kts`: add
  `implementation("org.springframework.boot:spring-boot-starter-oauth2-client")`.
- `config/InternalClientConfig.java`: `ReactiveOAuth2AuthorizedClientManager`
  (client-credentials) + a `WebClient` bean with
  `ServerOAuth2AuthorizedClientExchangeFilterFunction`, base URL =
  `${microservices.storage.uri}`.
- `client/StorageClient.java`: `presign(fileName, contentType)` →
  `POST /api/storage/presign` with `scope=user-files`; `delete(key)` →
  `DELETE /api/storage/objects`. Map 4xx/5xx to a `ResponseStatusException`
  with a message that does not echo storage internals.

`auth-server` side (`SecurityConfig.registeredClientRepository()`): the existing
`external-service` client only has the `openid` scope, so add a dedicated one
and include it in the `InMemoryRegisteredClientRepository(...)` list:

```java
RegisteredClient internalClient = RegisteredClient.withId(UUID.randomUUID().toString())
        .clientId("internal-service")
        .clientSecret(internalClientSecret)          // @Value, env-injected
        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
        .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
        .scope("internal")
        .build();
```

`SCOPE_internal` then falls out of the standard `JwtGrantedAuthoritiesConverter`
already wired in every service — no converter change anywhere.

### 3c. `profile`: cabinet API

New files: `domain/UserFile.java`, `repository/UserFileRepository.java`
(`Flux<UserFile> findByUsernameOrderByCreatedAtDesc(String)`,
`Mono<UserFile> findByIdAndUsername(Long, String)`),
`service/UserFileService.java`, `handler/UserFileHandler.java`, DTOs
`PresignFileRequest`, `PresignFileResponse`, `RegisterFileRequest`,
`UserFileResponse`.

Routes appended in `route/ProfileRoute.java` — **before** the `{username}`
admin routes, same ordering hazard as the existing comment there:

```
GET    /api/profiles/me/files              → list
POST   /api/profiles/me/files/presign      → {fileName, contentType, sizeBytes} → {key, uploadUrl, publicUrl, expiresIn}
POST   /api/profiles/me/files              → register {key, url, fileName, contentType, sizeBytes} after a successful PUT
DELETE /api/profiles/me/files/{id}         → storage delete + row delete
```

No `ProfileSec` change needed — `/api/profiles/me/**` is already
`.authenticated()`.

Rules enforced in `UserFileService`:

- username **always** from the JWT `sub`, never from the body (`ProfileHandler`
  already has the `getUsername(request)` helper — reuse the pattern).
- presign: content-type in the allow-list; declared `sizeBytes` ≤ 10 MB;
  per-user file count < 50 (count query before signing).
- register: `key` must start with `user-files/`, must not already exist in
  `user_file` (the UNIQUE index is the backstop), and the `url` is
  **recomputed server-side** from the key + public base URL rather than trusted
  from the body — otherwise a client could register an arbitrary URL under
  their own name.
- delete: `findByIdAndUsername` first (404 if not theirs — do not distinguish
  "not found" from "not yours"), then `StorageClient.delete`, then delete the
  row. If storage deletion fails, keep the row and return 502 so the user can
  retry; a dangling row is recoverable, a dangling object is not visible.

**Known gap, accept and document:** a presigned PUT cannot enforce a byte
limit, so `sizeBytes` is advisory — a determined client can upload more than
10 MB. Mitigations: the file-count cap above, and a Garage bucket quota
(`garage bucket set-quotas --max-size`) as the real ceiling. Note it in the
runbook rather than pretending the API check is sufficient.

**Done when:** upload → list → delete round-trips against local Garage, and a
second user cannot see or delete the first user's files.

---

## Phase 4 — Resend email (`profile`)

New package `org.granitesecurity.profile.notification`:

| File | Purpose |
| --- | --- |
| `EmailService.java` | `Mono<Boolean> sendPasswordChanged(String to, String displayName, Instant when)` — returns `false` (never throws to the caller) when disabled or when Resend rejects |
| `ResendClient.java` | `WebClient` to `https://api.resend.com` — `POST /emails`, `Authorization: Bearer <key>`, body `{from, to, subject, html, text}` |
| `EmailTemplates.java` | plain-Java HTML + text bodies; no template engine dependency |

Config in `profile/src/main/resources/application.yaml`:

```yaml
resend:
  api-key: ${RESEND_API_KEY:}
  from: ${RESEND_FROM:Granite Security <no-reply@notify.granite-security.org>}
  base-url: ${RESEND_BASE_URL:https://api.resend.com}
```

Behaviour:

- **Blank `api-key` ⇒ disabled.** Log at INFO once at startup and log
  `"[email disabled] would send password-changed to <redacted>"` per attempt.
  Local dev and tests must not need a real key.
- 5 s response timeout; one retry on 5xx/timeout; no retry on 4xx.
- **Never log the API key, the recipient's full address, or the body.** Log the
  Resend message id on success and status + error code on failure.
- Runs off the request path where possible (Phase 5 fires it after the password
  is already persisted), so email latency never delays a user response.

**Prerequisites:**
1. ~~API key~~ — **done.** `RESEND_API_KEY` (an `re_…` key) is in the root
   gitignored `.env` alongside `STRIPE_SECRET_KEY` and `STORAGE_S3_ACCESS_KEY`.
   Local runs pick it up via compose interpolation (Phase 7); a bare
   `./gradlew bootRun` does **not** read `.env`, so export it in the shell for
   that path. The cluster still needs it separately as a k8s secret (Phase 7) —
   `.env` is not consulted there.
2. In Resend, verify `notify.granite-security.org` — add the SPF, DKIM and
   DMARC records it gives you to Cloudflare DNS. Unverified domain ⇒ every send
   returns 403. **Still outstanding, and the long-lead item.**
3. Confirm the key is sending-only rather than full-access; if it's a
   full-access key, rotate to a restricted one before the cluster deploy.

**Done when:** with a real key exported locally,
`EmailService.sendPasswordChanged` delivers to your inbox; with the key unset,
it no-ops and logs.

---

## Phase 5 — Password change (`auth-server`) + notification hop

### 5a. Accept Bearer tokens

`auth-server` is currently only an issuer, not a resource server. Add:

- `build.gradle.kts`:
  `implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")`
  and `spring-boot-starter-validation` (if `registrations.md` Phase 2 already
  added validation, skip it).
- `SecurityConfig`: a new chain **between** the existing two. The
  authorization-server chain is `@Order(1)` with its own `securityMatcher`; the
  form-login/default chain is `@Order(2)` — bump it to `@Order(3)` and insert:

```java
@Bean
@Order(2)
public SecurityFilterChain accountApiSecurityFilterChain(HttpSecurity http) throws Exception {
    return http
            .securityMatcher("/api/me/**")
            .csrf(AbstractHttpConfigurer::disable)          // Bearer-only, no cookies
            .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
            .authorizeHttpRequests(a -> a.anyRequest().authenticated())
            .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()))
            .build();
}
```

JWT validation mirrors `ProfileSec`: decoder built from a fixed internal
`jwk-set-uri` plus a **trusted-issuers allow-list** (multi-domain setup — a
single `issuer-uri` would break the second domain). Point `jwk-set-uri` at
auth-server's own JWKS; because the RSA keypair is regenerated on every restart
the decoder must fetch live (`NimbusJwtDecoder.withJwkSetUri`), never a cached
static key.

Path check: context path is `/auth`, so the public URL is
`/auth/api/me/password`. `gateway/RouterConfig.java` already routes `/auth/**`,
`GateSec` is permit-all, and `ui-shop` proxies `/auth` same-origin in both dev
(`vite.config.ts`) and prod (`nginx.conf`). **No gateway or proxy change.**

### 5b. The endpoint

New files under `auth-server/src/main/java/org/granitesecurity/authserver/user/`:

| File | Purpose |
| --- | --- |
| `ChangePasswordRequest.java` | `@NotBlank currentPassword`, `@NotBlank @Size(8,72) newPassword` |
| `PasswordChangeService.java` | `@Transactional` verify + re-encode |
| `AccountController.java` | `@RestController` `PUT /api/me/password` |

Flow in `PasswordChangeService`:

1. `username = jwt.getSubject()`; load `UserEntity` (404 if missing — shouldn't happen).
2. **Reject non-local accounts**: `provider != LOCAL` ⇒ 409 "This account signs
   in with Google; there is no password to change." (Google users hold a
   generated unguessable password — see `registrations.md` Phase 4.)
3. `passwordEncoder.matches(currentPassword, user.getPassword())` ⇒ else **400**
   with a generic "current password is incorrect". Do not leak timing or
   distinguish from validation errors beyond that.
4. `newPassword` must differ from the current one ⇒ 400.
5. `user.setPassword(passwordEncoder.encode(newPassword))`, save. `@UpdateTimestamp`
   handles `updated_at`.
6. Fire the notification (5c) — **after** commit, failures swallowed.
7. Return `204 No Content`.

Errors go through the existing `UserExceptionHandler` (`@RestControllerAdvice`
→ `ProblemDetail`); add the new exception types there rather than a second advice.

Add `.csrf(csrf -> csrf.ignoringRequestMatchers(...))` is unnecessary — the new
chain disables CSRF wholesale because it is Bearer-only and stateless.

**Accepted limitation:** already-issued access tokens stay valid after a
password change (they're self-contained JWTs, and auth-server regenerates keys
only on restart). Document it; a follow-up could revoke the user's
`OAuth2Authorization` rows, but that is out of scope here.

### 5c. auth-server → profile notify hop

`profile` gets one new internal route (in `ProfileRoute`, alongside the
existing `/api/profiles/internal/...` one, already guarded by
`hasAuthority("SCOPE_internal")` in `ProfileSec`):

```
POST /api/profiles/internal/{username}/notify/password-changed
body: {"email": "<from auth-server users.email, may be null>"}
→ 200 {"sent": true|false}
```

`profile` resolves the recipient as `body.email ?? user_profile.email`; if both
are null it returns `{"sent": false}` — this is the "provided they have an
email" requirement. Then it calls `EmailService.sendPasswordChanged`.

`auth-server` side: `client/ProfileNotificationClient.java` using `RestClient`
(servlet service — **not** WebClient/reactive) with an
`AuthorizedClientServiceOAuth2AuthorizedClientManager` for the
`internal-service` client-credentials registration from Phase 3b. Call it from
an `@Async` method (add `@EnableAsync`) so a slow Resend call never delays the
password response, and wrap the whole thing in try/catch + `log.warn` — **the
password change must not fail because email failed.**

Config: `microservices.profile.uri` (`http://localhost:8064` local,
`http://profile:8064` in compose/k8s) and the internal client id/secret.

**Done when:** changing the password from the UI returns 204, the new password
logs in, the old one doesn't, and an email arrives at the profile's address.

---

## Phase 6 — `ui-shop`

- `src/api/profile.ts` — add `getFiles()`, `presignFile(...)`, `registerFile(...)`,
  `deleteFile(id)`; `updateProfile` gains `displayName` via the shared type.
- `src/api/account.ts` **(new)** — `changePassword({currentPassword, newPassword})`
  → `PUT /auth/api/me/password`. Separate module because it targets `/auth`, not
  `/api`; reuse `request` from `client.ts` so the Bearer token is attached the
  same way.
- `src/types.ts` — `displayName` on `ProfileResponse`/`UpdateProfileRequest`;
  new `UserFile`, `PresignFileResponse`.
- `src/pages/Profile.tsx` (currently 80 lines) — grow into three sections:
  1. **Details** — existing fields + display name.
  2. **Password** — current / new / confirm, client-side match + length check,
     success toast that says an email was sent when the profile has an address.
     Hide the whole section for Google-provisioned accounts (detect via the 409,
     or expose `provider` on `ProfileResponse` — prefer exposing it).
  3. **Files** — upload (reuse the `storageApi.uploadFile` shape but through the
     new profile endpoints: presign → `fetch(uploadUrl, {method:'PUT'})` →
     register), per-file progress, list with name/size/date, copy-link and
     delete. Client-side type + 10 MB guard before presigning.
- Header/nav: show `displayName ?? username`.
- Copy on the file section must state plainly that anyone with the link can open
  the file — that's the trade-off of the public-bucket decision.

---

## Phase 7 — Infra & config

- `compose.yaml`, `profile` service block (currently `compose.yaml:243`) — add,
  using the same `${VAR}` interpolation the `storage` block already uses for its
  S3 keys:

  ```yaml
      - RESEND_API_KEY=${RESEND_API_KEY}
      - RESEND_FROM=Granite Security <no-reply@notify.granite-security.org>
      - MICROSERVICES_STORAGE_URI=http://storage:8065
      - INTERNAL_CLIENT_ID=internal-service
      - INTERNAL_CLIENT_SECRET=${INTERNAL_CLIENT_SECRET}
  ```

  and add `storage: {condition: service_started}` to its `depends_on`.
  `auth-server` gains `MICROSERVICES_PROFILE_URI=http://profile:8064`,
  `INTERNAL_CLIENT_ID/SECRET` and `APP_OAUTH2_INTERNAL_CLIENT_SECRET`.
- Root `.env`: `RESEND_API_KEY` is **already set**. Still to add:
  `INTERNAL_CLIENT_SECRET` (Phase 3b). The file is gitignored
  (`.gitignore:3`) — keep it that way, and never echo either value into a
  committed file, a log line, or a commit message.
- `k8s/base/config.yaml` — non-secret values (`RESEND_FROM`, service URIs).
- `k8s/base/secrets.yaml.example` + the real gitignored `secrets.yaml` /
  `k8s/hetzner/.../secrets-patch.yaml` — add `profile-resend-api-key` and
  `internal-client-secret`, following the `storage-s3-access-key` naming already
  established.
- `storage` bucket: extend the CORS rule to allow `PUT` from the app origins (it
  already does for product uploads — verify, don't assume) and set a bucket
  quota as the real upload ceiling.
- CI needs no change: `.github/workflows/ci.yml` builds by changed directory, and
  all four touched services are already in the matrix.

---

## Phase 8 — Tests

| Level | What |
| --- | --- |
| `profile` unit | `ProfileServiceTest` — display-name validation (valid, too long, bad chars, blank→null). `UserFileServiceTest` — Mockito `StorageClient` + StepVerifier: type/size/count rejection, key-prefix guard, URL recomputation, cross-user delete → 404, storage-delete failure keeps the row. |
| `profile` unit | `EmailServiceTest` — WebClient stubbed via `ExchangeFunction`: disabled-key no-op, 200 → true, 422 → false without retry, 500 → one retry. |
| `profile` repo | Testcontainers test for `UserFileRepository` (Docker required, matching existing repo tests). |
| `auth-server` | `PasswordChangeServiceTest` (Mockito, `PasswordEncoder` real `NoOp`/bcrypt): wrong current password, Google account, same-as-old, happy path. MockMvc slice for `AccountController` with a mock JWT. |
| Manual | Full round-trip on the kind cluster before Hetzner: upload, change password, receive email. |

---

## Security notes

- **Public files.** Per the chosen option, uploaded files live in the public
  media bucket: the URL is unguessable but unauthenticated. Do not present this
  cabinet as a place for sensitive documents, and say so in the UI. Switching to
  a private bucket + presigned GET later is a contained change (new bucket, and
  `UserFileService` returns signed URLs instead of stored ones).
- **Ownership never comes from the client** — username is always the JWT `sub`,
  the registered URL is always recomputed server-side.
- **Secrets** — the Resend key and internal client secret exist only in `.env`
  and gitignored k8s secret patches. Nothing lands in a committed file, and
  neither is ever logged.
- **`display_name` is cosmetic** — never accept it as a lookup, filter, or
  authorization input, or it becomes an impersonation vector.
- **Email enumeration** — the notify endpoint is `SCOPE_internal` only, so it
  can't be used to probe which usernames have addresses.

## Suggested order of work

1. Phase 1 + 2 (schema + display name) — small, ships independently.
2. Phase 3a/3b (storage scope + internal client wiring) — unblocks everything else.
3. Phase 3c (cabinet API) + the UI files section.
4. Phase 4 (Resend) — key is already in `.env`; **verify the domain in Resend
   first**, that's now the only blocker left on this phase.
5. Phase 5 (password change + notify hop) + the UI password section.
6. Phase 7 infra, Phase 8 tests folded in as each phase lands.
