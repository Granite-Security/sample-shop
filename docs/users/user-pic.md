# Profile pictures

Status: **implemented** — all eight phases. Verified only by compiling and building; the
cluster walkthrough in §7 has not been run yet.

Let a user have an avatar: either one they upload, or the picture Google already has for
them. Surfaces in both storefronts (`ui-shop`, `ui-demo`) and in the admin user list.

Built as designed, with three things worth recording:

- **Phase 8 closed known gap #1 as well.** `user_file` rows and their storage objects were
  already leaking on user purge (`blocking-users-implementation.md` §6). Deleting the
  avatar without them would have left two orphans of the same shape, so
  `deleteProfileData` now collects every object key first, deletes the rows in one local
  transaction, and deletes the objects afterwards, best-effort.
- **`StorageService` gained a `SELF_SERVICE_SCOPES` set** rather than a second literal
  comparison — the per-user key prefix and the caller check both needed to mean
  "`user-files` or `avatars`", and two independent string checks would drift.
- **No tests were written**, by request. The properties listed in §7 are therefore
  unguarded; they are still the right things to test if that changes.

## 1. Do we have access to the Google picture?

**Yes, and we are already fetching it — we just throw it away.**

`SecurityConfig.clientRegistrationRepository()` registers Google with
`scope(openid, profile, email)`. The `profile` scope is exactly what makes Google return
the `picture` claim, so it is present in the ID token and the userinfo response on every
federated login. `GoogleOidcUserService.loadUser()` receives it on the `OidcUser`
(`oidcUser.getPicture()`), uses `getGivenName()`/`getFamilyName()` for provisioning, and
lets the picture fall on the floor: nothing persists it, nothing forwards it, and no
downstream service ever learns it exists.

So no new Google scope, no consent-screen change, no re-authorization of existing users.
The URL looks like `https://lh3.googleusercontent.com/a/<opaque>=s96-c` — publicly
readable, no credentials needed, and **it rotates**: it changes when the user changes
their Google photo, and the old URL eventually 404s. Anything we store is a cache, never a
permanent identifier.

Two consequences that shape the rest of this document:

- A stored Google URL must be refreshed, and the render path must survive a 404.
- Form-login users have no Google picture at all. `LINKED` users (registered locally,
  later signed in with Google — see [`blocking-users.md`](blocking-users.md) §2.2) do have
  one, but only from the moment they first use the Google button.

## 2. Who owns the avatar

`profile` owns it. It already owns everything user-facing about a person (display name,
addresses, the file cabinet), it already talks to `storage` for presigned uploads and
ownership-checked deletes, and both UIs already read `GET /api/profiles/me` on every
account page. An avatar is one more field on that response.

`auth-server` owns identity, not presentation. Its only job here is to stop discarding the
Google claim.

## 3. Design decisions

### D1 — The Google picture reaches `profile` as a JWT claim, not a new column in `authdb`

`auth-server` adds `picture` to the token it issues, alongside the `roles` claim its
`OAuth2TokenCustomizer` already injects:

```java
if (context.getPrincipal().getPrincipal() instanceof OidcUser oidcUser
        && oidcUser.getPicture() != null) {
    context.getClaims().claim("picture", oidcUser.getPicture());
}
```

`profile` reads that claim on `GET /api/profiles/me` — where it already has the `Jwt` in
hand — and writes it to `google_picture_url` when it differs from what is stored. That is
self-healing: the value refreshes on the next page load after the user changes their
Google photo, with no polling and no staleness window worth naming.

**Rejected: persist `picture_url` on `authdb.users`.** It is the more "correct-looking"
option — `auth-server` learns the fact, so `auth-server` stores it — and it was rejected
because of what it drags in. A Liquibase changeset on `authdb`; the field added to
`AdminUserResponse`, `AdminUserView` and the internal API contract; and then the refresh
problem, which is only solvable by publishing a new `identity.events` event on every
Google login (or on every *change* at login, to avoid a message per sign-in) and adding a
consumer branch in `profile`. That is a schema migration, a new event type and a new
consumer for a string we can read straight off the principal that is already being
serialized into a token we already mint. The claim costs about eighty bytes on the JWT.

Its one genuine advantage: an admin could see the avatar of a user who has not logged in
since this shipped. Not worth the machinery — those users backfill themselves on their
next visit.

**Note for the SPAs:** the claim lands on the ID token too, so `user.claims.picture` is
readable client-side (`ui-demo/src/auth.tsx` already reads `user.claims.roles` this way).
Do **not** render from it. `profile` is the single source of truth for *which* avatar
wins, and a header reading the raw claim would show the Google picture to a user who has
chosen their upload.

### D2 — Uploads go to a new `avatars` storage scope

`StorageService` gates by scope; today `products` (admin/manager only) and `user-files`
(any authenticated caller). Add `avatars`: images only (`image/jpeg`, `image/png`,
`image/webp`), per-user key prefix like `user-files`, and allowed for plain users —
`requireScopeAllowedForCaller` currently confines non-elevated callers to `user-files`
alone and must learn the second name.

**Rejected: reuse the `user-files` scope.** The avatar would appear in the user's file
cabinet as a stray image, count against the 50-file limit, and inherit a 5 GB ceiling and
`application/pdf` in its allow-list. A separate scope keeps the cabinet a cabinet.

Same trade-offs as the existing file flow apply and should not be re-litigated: the bucket
is public and the key contains the username (deliberate, documented in `StorageService`),
and the size cap is advisory because a presigned PUT cannot enforce one — the browser
downscales before upload (§5, Phase 4) and `profile` rejects an oversized *declared* size
at register time, with the Garage bucket quota as the real backstop.

### D3 — Three states, one effective URL

`avatar_source` is `UPLOAD`, `GOOGLE` or `NONE`. The server computes the effective avatar
rather than making each UI re-derive it:

| `avatar_source` | Rendered |
|---|---|
| `UPLOAD` | the uploaded object's URL |
| `GOOGLE` | `google_picture_url` (may be null if they have never used Google) |
| `NONE` | nothing — the UI draws an initials monogram |

The user asked for "upload their own **or** use the one from Google", so a user who has
both needs a way to choose: switching to `GOOGLE` must not delete the upload, and
switching back must not require re-uploading. Hence `uploaded_avatar_url` is kept on the
row independently of which source is active.

Default for a new profile: `GOOGLE` if the JWT carried a picture on first sight, else
`NONE`. Never silently `UPLOAD`.

### D4 — Avatar changes do not go through `PUT /api/profiles/me`

`updateProfile` overwrites `email`/`firstName`/`lastName`/`displayName` wholesale from the
request body. Folding the avatar in would mean every UI that saves the details form has to
round-trip the avatar fields correctly or blank them. Dedicated endpoints instead (§4).

### D5 — Inherited: the Google-`sub` keying gap

`profile` keys rows on the JWT `sub`, which for a federated login is the Google subject,
not the username — the duplicate-identity gap in
[`blocking-users-implementation.md`](blocking-users-implementation.md) §8 (Phase 7, not
started). The avatar lands on whichever row that `sub` resolves to, so it is
self-consistent and this feature neither worsens nor fixes the split. Do not attempt the
re-keying here.

## 4. API surface

Added to `ProfileResponse` (and therefore to `GET /api/profiles/me`,
`GET /api/profiles/{username}`, `GET /api/profiles`):

```jsonc
{
  "avatarUrl":         "https://…",   // effective — what to render, null if NONE
  "avatarSource":      "UPLOAD",      // UPLOAD | GOOGLE | NONE
  "uploadedAvatarUrl": "https://…",   // kept while source is GOOGLE, null if never uploaded
  "googlePictureUrl":  "https://…"    // null for form-only accounts
}
```

New routes in `ProfileRoute`, registered before the `{username}` catch-alls:

| Method | Path | Body | Effect |
|---|---|---|---|
| `PUT` | `/api/profiles/me/avatar` | `{ key, url, contentType, sizeBytes }` | registers a freshly uploaded object, sets source `UPLOAD`, deletes the previously uploaded object from storage |
| `PUT` | `/api/profiles/me/avatar/source` | `{ source: "UPLOAD" \| "GOOGLE" \| "NONE" }` | switches which one wins; `UPLOAD` with nothing uploaded → 400, `GOOGLE` with no Google picture → 400 |
| `DELETE` | `/api/profiles/me/avatar` | — | deletes the uploaded object, falls back to `GOOGLE` if available else `NONE` |

Validation on register, mirroring `UserFileService.register`: `key` must start with
`avatars/`, `contentType` must be in the image allow-list, `sizeBytes` must be under the
cap (2 MB). Upload itself is the existing browser → `storage` presigned PUT; `profile`
never sees the bytes.

`AdminUserView` gains `avatarUrl` only (the effective one). `AdminUserService` already
joins auth users against profile rows to compute `hasProfile`, so this is one more field
off a row it has already loaded.

## 5. Schema

`profile/src/main/resources/db/changelog/006-user-avatar.sql`:

```sql
--liquibase formatted sql

--changeset moldo:006-user-avatar
ALTER TABLE user_profile
    ADD COLUMN avatar_object_key   VARCHAR(512),
    ADD COLUMN uploaded_avatar_url VARCHAR(1024),
    ADD COLUMN google_picture_url  VARCHAR(1024),
    ADD COLUMN avatar_source       VARCHAR(16) NOT NULL DEFAULT 'NONE';
--rollback ALTER TABLE user_profile DROP COLUMN avatar_object_key, DROP COLUMN uploaded_avatar_url, DROP COLUMN google_picture_url, DROP COLUMN avatar_source;
```

No backfill. Existing rows are `NONE` and self-populate: a Google user's next `/me` call
writes `google_picture_url` and flips them to `GOOGLE`.

## 6. Phases

Each phase is independently shippable and independently verifiable in the cluster.

1. **`auth-server` emits the claim.** The `picture` line in `jwtTokenCustomizer`. Verify by
   signing in with Google and decoding the token — no UI change yet.
2. **`profile` stores and serves.** Changeset, `UserProfile` fields, effective-URL logic in
   `ProfileService`, the claim captured on `getProfile`, the four new response fields.
   `GET /api/profiles/me` now returns a `GOOGLE` avatar for a Google user, `NONE` for
   everyone else.
3. **`storage` learns `avatars`.** Scope map entry plus the `requireScopeAllowedForCaller`
   change. A plain user can presign an `avatars/` key; a `products/` key still 403s.
4. **Upload path.** The three `profile` endpoints, plus client-side downscale (canvas to
   512×512 JPEG before the presigned PUT) so a 12 MP phone photo does not become a 6 MB
   avatar. Old object deleted on replace.
5. **`ui-shop`.** An `Avatar` component (image with `referrerPolicy="no-referrer"`, an
   `onError` fallback to an initials monogram in a colour derived from the username), the
   picker on the Profile page — upload, switch to Google, remove — and the avatar in the
   header account menu.
6. **`ui-demo`.** The same, in the boutique's cocoa/ivory idiom: circular, thin gold ring
   when active, replacing `AccountIcon` in the header when an avatar exists.
7. **Admin.** `avatarUrl` on `AdminUserView`, thumbnails in the users list and on the
   customer profile view.
8. **Deletion.** Delete the avatar object when a user is purged. This is adjacent to known
   gap #1 in `blocking-users-implementation.md` §6 (`user_file` objects are *also* not
   deleted on purge) — fix both in one pass through `AdminUserService`, or the avatar will
   simply become the second orphan of the same shape.

Phases 1–2 alone deliver "Google users have a picture", which is most of the visible value
for the least code. Phases 3–4 are what make it a feature rather than a passthrough.

## 7. Verification

Manual, against the cluster — this is UI-visible behaviour across two frontends, two
identity states and a storage backend, and a green unit test proves none of it.

1. Google user: sign in with Google → header shows the Google photo, Profile page shows it
   with source **Google**.
2. Upload a picture → header switches to it within one page load; the Google option is
   still offered and switching back restores the Google photo without re-uploading.
3. Remove the upload → falls back to the Google photo. For a form-login user, falls back to
   initials.
4. Change the photo in Google account settings, sign out and in → the new photo appears
   (proves the refresh path in D1).
5. Form-login user (`user`/`user`): no Google option offered, initials until they upload.
6. Point an avatar `<img>` at a deliberately broken URL → the monogram renders, no broken
   image icon.
7. `kubectl -n granite exec` into Garage / check the bucket: replacing an avatar leaves one
   object, not two.

Tests worth writing, because they encode a property rather than a behaviour:

| Test | Property |
|---|---|
| source switch to `UPLOAD` with nothing uploaded → 400 | the effective URL can never be null while source is `UPLOAD` |
| register with a `user-files/` key → 400 | the scope prefix is enforced server-side, not just by the client that asked for the presign |
| replacing an avatar deletes the previous object | no unbounded object growth per user |
| a plain user presigning `products/` → 403 | the `avatars` scope addition did not widen the elevated scope |

## 8. Notes

- **No CSP today.** `ui-demo/nginx.conf` sets no `Content-Security-Policy`, so
  `lh3.googleusercontent.com` images load. If one is ever added, `img-src` must list that
  host and the Garage public base URL, or every avatar silently disappears.
- **Privacy.** The uploaded avatar sits in a public bucket under a key containing the
  username, exactly like the file cabinet. Accepted, same reasoning as `StorageService`
  documents — but it means an avatar is public to anyone with the URL, which is the normal
  expectation for an avatar and should not be quietly assumed to be true of `user-files`
  too.
- **Not proxying Google.** Rendering `lh3.googleusercontent.com` directly leaks a page view
  to Google per avatar render. Proxying it through `storage` or copying the bytes on first
  login would avoid that at the cost of a fetch path, a cache and a staleness policy. Not
  worth it here; revisit if the platform ever grows a privacy posture that says otherwise.
