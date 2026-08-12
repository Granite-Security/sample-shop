# Public profiles — `/users/<handle>`

Status: **implemented, pending verification in k8s.** All ten steps below are in the
code; nothing has been exercised against a live cluster yet — see Verification.

A user can publish their profile. Published profiles are readable with no token at
`https://<domain>/users/<handle>`. Unpublished profiles stay invisible.

## Decisions

**D1 — `handle` is a new column, not `displayName`.** `displayName` is free text
(`^[\p{L}\p{N} ._'-]+$` — spaces and apostrophes legal), so it makes a bad path segment and
renaming it breaks saved links.

**D2 — `handle` is unique always, not only while published.** A partial unique index
(`WHERE public_profile = true`) frees the handle on unpublish, so a URL you handed out later
resolves to a different person. Reserving at set-time also makes publishing a boolean flip
that cannot 409.

**D3 — `username` IS published.** It is already visible to any signed-in user through the
recipient picker. Publishing it means `POST /api/profiles/me/messages` (`to`) and
`POST /api/balance/me/transfers` (`to`) work off the public page unchanged — no handle
resolution, no new lookup path, no new write surface.

**D4 — a private/unknown handle answers 404, not 403.** Otherwise the endpoint is a
membership oracle over a namespace people pick to be memorable.

**D5 — separate endpoints for `handle` and `visibility`; `bio` rides on `PUT /me`.**
`PUT /me` overwrites every field it is given (same reason the avatar has its own routes,
docs/users/user-pic.md D4). `handle` also needs 409 semantics.

**D6 — blocking sets `public_profile = false` locally.** Block state lives in auth-server;
calling it per anonymous page view would put an unauthenticated path in front of identity.

## Published fields

`handle`, `username`, `displayName`, `avatarUrl`, `bio`, `memberSince` (`created_at`).

Not published: `email`, `firstName`, `lastName`, addresses, files, orders, messages,
balances.

Avatars need no work — the upload path stores `presigned.publicUrl`, and the Google
`picture` claim is already a public URL. No `StorageSec` change.

## No change needed

Gateway (`/api/profiles/**` already routed), nginx (`try_files ... /index.html` already
serves `/users/*`), storage, Kafka.

---

## Step 1 — `010-public-profile.sql`

```sql
--liquibase formatted sql

--changeset moldo:010-public-profile
ALTER TABLE user_profile
    ADD COLUMN handle         VARCHAR(32),
    ADD COLUMN bio            VARCHAR(500),
    ADD COLUMN public_profile BOOLEAN NOT NULL DEFAULT FALSE;

CREATE UNIQUE INDEX uq_user_profile_handle ON user_profile(handle) WHERE handle IS NOT NULL;
--rollback DROP INDEX uq_user_profile_handle; ALTER TABLE user_profile DROP COLUMN handle, DROP COLUMN bio, DROP COLUMN public_profile;
```

No backfill. Add the include to `db.changelog-master.yaml`.

## Step 2 — Domain + DTOs

- `UserProfile`: `handle`, `bio`, `publicProfile` (`@Column("public_profile")`).
- New `PublicProfileResponse(String handle, String username, String displayName,
  String avatarUrl, String bio, Instant memberSince)`. A separate record — not
  `ProfileResponse`, which carries `email`.
- `UpdateProfileRequest`: `+ bio`.
- `ProfileResponse`: `+ handle, bio, publicProfile`.
- New `HandleRequest(String handle)`, `VisibilityRequest(Boolean publicProfile)`.

## Step 3 — Repository

```java
@Query("SELECT * FROM user_profile WHERE handle = :handle AND public_profile = true")
Mono<UserProfile> findPublishedByHandle(String handle);
```

Filter in SQL, not in the service — a published-only query can't be reused wrong.

Plus targeted `UPDATE ... SET handle = :handle` and `SET public_profile = :flag`. Targeted,
not `save()`: a full-row write races the `UserRegistered` consumer (see `syncGooglePicture`).

## Step 4 — Service

- `getPublished(handle)` — lowercase, `findPublishedByHandle`, `switchIfEmpty` → 404.
- `setHandle(username, raw)` — lowercase, validate `^[a-z0-9](?:[a-z0-9-]{1,30})[a-z0-9]$`
  (3–32), reject reserved list, update, map `DuplicateKeyException` → 409. Catch the
  constraint, don't pre-`SELECT` — that's what makes a race deterministic.
- Reserved: `me`, `admin`, `contact`, `internal`, `public`, `api`, `auth`, `users`, `new`,
  `edit`, `settings`, `login`, `logout`, `register`, `null`, `undefined`. (`me` and `admin`
  already shadow the `{username}` wildcard in `ProfileRoute`/`ProfileSec`.)
- `setVisibility(username, flag)` — 400 if publishing with a null handle.
- `validateBio` — trim, blank → null, ≤500, reject control chars. No HTML: React escapes,
  and the page must not use `dangerouslySetInnerHTML`.

## Step 5 — Routes + security

`ProfileRoute`:

```java
.GET("/api/profiles/public/{handle}", profileHandler::getPublicProfile)  // before {username}
.PUT("/api/profiles/me/handle", profileHandler::setHandle)
.GET("/api/profiles/me/handle/available", profileHandler::checkHandle)
.PUT("/api/profiles/me/visibility", profileHandler::setVisibility)
```

`ProfileSec`, as the **first** rule, above the contact rule:

```java
.pathMatchers(HttpMethod.GET, "/api/profiles/public/**").permitAll()
```

Method-scoped and first because `public` is a legal `{username}` value and the catch-all
below would require a token.

`GET /api/profiles/me/handle/available` stays authenticated — anonymous availability checks
are a free enumeration oracle.

## Step 6 — Public page (ui-shop)

- `src/pages/PublicProfile.tsx`; in `App.tsx`, inside `Layout` but **outside** `RequireAuth`
  and `AccountLayout`: `<Route path="users/:handle" element={<PublicProfile />} />`.
- `src/api/publicProfile.ts` → `GET /api/profiles/public/{handle}`. Verify the shared
  `request` helper tolerates no token and doesn't fire a 401-refresh for an anonymous
  visitor.
- 404 → one message for both unknown and private.

## Step 7 — Actions on the public page

Both use `profile.username` from the response, hitting existing endpoints unchanged:

- **Message** → `POST /api/profiles/me/messages` `{ to: username, subject, body }`.
- **Gift** → `POST /api/balance/me/transfers` `{ to: username, amountChf, memo,
  idempotencyKey }`.

Signed out: replace both with a sign-in button carrying a return path to `/users/<handle>`.

## Step 8 — Owner controls (My Account → Profile)

Handle field with debounced availability check showing the resulting URL; a *Make public*
switch disabled until a handle is saved; a bio textarea with char count. The form must
submit `bio` alongside the existing fields — `PUT /me` overwrites what it's given.

## Step 9 — Admin

- `AdminUserService.blockUser` also sets `public_profile = false` (D6), audited in
  `admin_action`. Unblock does not re-publish.
- `POST /api/profiles/admin/users/{username}/unpublish` — already covered by the existing
  `/api/profiles/admin/**` → `hasRole("ADMIN")` rule. Optionally clears the handle, which is
  the escape hatch for D2's squatting cost.

## Step 10 — Docs

`README.md` profile endpoint table; pointer from `docs/users/user-profile.md`.

## Verification

Unit tests only for the pure bits in `ProfileServiceTest` (handle regex, reserved list, bio
validation, 409 mapping) — no Docker needed. The rest against the cluster:

```bash
kubectl config current-context

curl -s  https://<domain>/api/profiles/public/adrian | jq        # no email/firstName/lastName
curl -i  https://<domain>/api/profiles/public/<private-handle>   # 404, not 403
curl -i  https://<domain>/api/profiles/public                    # 404 from routing, not 401
curl -i  "https://<domain>/api/profiles/me/handle/available?handle=adrian"  # 401
curl -s -H "Authorization: Bearer $TOKEN" https://<domain>/api/profiles/me | jq
```

Browser, private window: `/users/<handle>` renders without bouncing to login; a bio
containing `<script>` and `<b>bold</b>` renders as literal text; Message and Gift buttons
work end to end from the public page while signed in.

## Out of scope

No directory/listing endpoint. No SSR, so no link previews. No opt-out of public messages or
gifts. No handle-change redirects. No rate limit on the public read.

## Open

1. Pre-fill the handle suggestion from `username`? (Now harmless — D3 publishes it anyway.)
2. ~~Port to `ui-demo`?~~ **Done** — same endpoints, that storefront's styling, as messaging
   did in #80. The opt-in panel, `/users/:handle`, the `returnTo` callback and the admin
   Unpublish button are all present in both frontends.
3. On user delete, release the handle immediately or tombstone it?
