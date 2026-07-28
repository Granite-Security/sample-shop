# Blocking and deleting users (admin)

Status: **plan / not started**
Author: design note, 2026-07-28

## 1. Goal

Let an admin block, unblock and delete users from `/admin/users`. Admin-only, enforced
server-side (not just hidden in the UI).

## 2. What the production data revealed

Two findings that change the design — both pre-existing, neither caused by this feature.

### 2.1 The admin page lists the wrong thing

`UsersManagement.tsx` calls `api.profile.getProfiles()` → `GET /api/profiles` on the
**profile** service. But the flag that blocks a login (`enabled`) lives on the **users**
table in **auth-server**. The two sets do not match:

```
auth users (8):  admin adria davide iaka itiganas manager repro-test-1785224224 user
profiles   (9):  admin adria davide iaka itiganas manager user
                 + 102919241495532217479   ← a Google sub
                 + external-service        ← a client-credentials service account
```

- **One human, two profile rows.** `iaka` has `provider_id = 102919241495532217479`, and
  a *separate* profile row exists under that number with the same email
  (`net.vrabie@gmail.com`). `profile` keys rows on the JWT `sub`, which is the username
  for form login but the Google `sub` for federated login. Both identities have orders.
- **A service account is listed as a user.** `external-service` obtained a profile row
  by calling a `/me` endpoint with a client-credentials token.
- **A real user is invisible.** `repro-test-1785224224` has no profile row, so the admin
  page cannot see — or block — it.

"Block this row" is therefore ambiguous today: blocking `102919241495532217479` should
block `iaka`, but no user row carries that username.

**Decision (D3): the page switches to listing auth-server users**, enriched with profile
data. auth-server is the identity authority; profile is a projection.

### 2.2 Deleting a user is not a local operation

**22 orders across 4 usernames.** Deleting a user with order history is routine, not
hypothetical — and one of those four is the Google-sub identity above.

## 3. Decisions

| # | Decision | Resolution |
|---|---|---|
| D1 | What does "delete" do? | **Soft delete + anonymise.** Set `deleted_at`, scrub PII, refuse login. `customer_order` is left untouched so shop history and reporting stay honest. |
| D2 | Does blocking kill live sessions? | **No — accept the token window.** `enabled=false` refuses new logins. An already-issued access token stays valid until it expires (~5 min, Spring Authorization Server default — no `TokenSettings` are configured). No session-revocation infrastructure. |
| D3 | What does the admin page list? | **auth-server users**, via a new admin API, enriched with profile data (§2.1). |
| D4 | Is a deleted username reusable? | **No.** The row is kept, so the unique constraint reserves the username forever. This prevents someone re-registering as a deleted account and inheriting its apparent history. |
| D5 | Is a deleted email reusable? | **Yes**, as a consequence of scrubbing it — `existsByEmailIgnoreCase` stops matching, so the person can sign up again with a fresh account. This is intended, and worth stating because it follows from D1 rather than being chosen separately. |
| D6 | Audit trail? | **Yes, minimal.** An `admin_action` table recording who did what to whom and when. "Who blocked this customer?" is the first question anyone asks, and logs age out. |
| D7 | Email the affected user? | **No.** A "you have been blocked" email is hostile and useful to nobody; a "your account was deleted" mail would go to an address we just scrubbed. `notification` stays uninvolved. |

### 3.1 Rejected alternative: hard delete with an order cascade

Considered and dropped. Recorded here because it is the obvious first instinct, and the
reasons it fails are not visible until you look at the data.

**What killed it: the payments.** Deleting a user's orders means deleting the payment and
refund rows behind them — but Stripe keeps its side regardless, and we cannot delete it.
Shop and Stripe would diverge permanently for that customer, revenue reports would change
retroactively (figures quoted last month would no longer reproduce), and a chargeback
arriving weeks later would have no local order, payment or address to answer it with.
There is also no undo short of a point-in-time restore across four databases.

**It is also much harder to build than it looks**, because only `shop` knows which orders
belong to a username:

| Database | Table | Keyed by |
|---|---|---|
| shopdb | `customer_order` | **`username`** |
| shopdb | `order_item` | `order_id` |
| paymentdb | `payment` | `order_id` |
| paymentdb | `refund` | `order_id`, `payment_id` |
| deliverydb | `delivery` | `order_id` |
| deliverydb | `delivery_tracking` | `delivery_id` |

A "every service deletes its rows for this username" cascade **cannot work** — `payment`
and `delivery` have no username to match on. It needs two hops (`UserDeleted` → shop
resolves username → `OrdersPurged` → payment and delivery delete by `order_id`), and a
lost event orphans financial rows belonging to a username that no longer exists to find
them by. That in turn needs a tombstone, a retry path, and longer topic retention than
`identity.events` can offer while it still carries reset tokens.

Soft delete avoids all of it: order history stays intact and attributable, Stripe stays
reconcilable, and a mistake is a flag flip rather than a restore.

## 4. Target design

```
Admin UI (/admin/users)
      │  GET  /auth/api/admin/users          (ROLE_ADMIN)
      │  POST /auth/api/admin/users/{id}/block
      │  POST /auth/api/admin/users/{id}/unblock
      │  DELETE /auth/api/admin/users/{id}
      ▼
auth-server  ── identity authority: owns users, enabled, deleted_at
      │
      └──► identity.events ──┬──► notification  (no templates → sends nothing)
                             └──► profile       (UserDeleted → scrub profile PII)
```

Blocking and deleting are **identity operations**, so they belong in auth-server, and
they propagate the same way registration already does — as facts on `identity.events`.
`profile` gains one more event type; no new integration, no new HTTP call between
services. This is the fan-out the notification refactor was built for.

### New events

| Event | Payload | Consumed by |
|---|---|---|
| `UserBlocked` | `username`, `email`, `blockedBy`, `occurredAt` | (none yet — audit/analytics later) |
| `UserUnblocked` | `username`, `blockedBy`, `occurredAt` | (none yet) |
| `UserDeleted` | `username`, `deletedBy`, `occurredAt` | `profile` — scrubs profile PII |

`notification` will consume these and find no template, log
`No EMAIL template for UserDeleted — nothing sent`, and move on. That is the intended
behaviour (D7), and it is already how `TemplateRegistry` handles unknown types.

**Retention caveat:** `identity.events` keeps 1 hour. If `profile` is down for longer
than that, a `UserDeleted` is lost and the profile keeps its PII. Mitigation is the
reconciliation job in Phase 5 — do not skip it, because "we deleted the user but their
name is still on the profile" is exactly the failure that matters here.

## 5. Data model

### Existing constraints on `users`

| Constraint | Columns | Notes |
|---|---|---|
| `users_pkey` | `id` | primary key |
| `users_username_key` | `username` | |
| `users_email_key` | `email` | |
| `uk_users_provider_id` | `(provider, provider_id)` | **partial** — `WHERE provider_id IS NOT NULL` |

(Plus a redundant non-unique `idx_users_username`, already covered by the unique
constraint.)

These are what make the anonymisation below work, and one of them works for a
non-obvious reason:

- `username` unique → keeping the row reserves the name forever (D4).
- `email` unique → the scrub value must be **unique per user**, hence
  `deleted-user-{id}@invalid`. A constant placeholder would collide on the second
  deletion.
- `(provider, provider_id)` unique but **partial** → setting `provider_id = NULL`
  removes the row from that index entirely, so the same Google account can link to a
  fresh user later. Were the index not partial, a second deleted Google user would
  collide on `(LOCAL, NULL)`.

### Identity states — `provider` alone does not mean "Google"

`FederatedUserProvisioningService` produces **three** states, not two:

| `provider` | `provider_id` | Meaning | Password? |
|---|---|---|---|
| `LOCAL` | `NULL` | Registered with the form | yes |
| `LOCAL` | `<google sub>` | **Linked** — registered locally, later signed in with Google | yes, still works |
| `GOOGLE` | `<google sub>` | Provisioned by Google sign-in | no (random unguessable value) |

The middle state is deliberate: when a Google sign-in matches an existing user by email,
provisioning keeps `provider = LOCAL` and only adds the subject, *"so the existing
password still works; only link the Google subject so both login methods resolve to this
row."* `iaka` is in exactly this state in production.

Two consequences:

- **The admin list must show three states**, not a `LOCAL`/`GOOGLE` toggle. Keying a
  "Google" badge off `provider` alone would mislabel linked accounts as password-only;
  keying it off `provider_id IS NOT NULL` alone would mislabel them as Google-only. Show
  *Password* / *Password + Google* / *Google*.
- **The password-change guard is correct as written.** `PasswordChangeService:31` and
  `PasswordResetService:46` reject only `provider != LOCAL`, so a linked account can
  still change its password — right, because it genuinely has one. Do not "fix" this to
  test `provider_id` instead; that would lock linked users out of their own password.

### auth-server (Liquibase changeset)

```sql
ALTER TABLE users ADD COLUMN deleted_at TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN blocked_at TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN blocked_by VARCHAR(64);

CREATE TABLE admin_action (
    id           BIGSERIAL    PRIMARY KEY,
    actor        VARCHAR(64)  NOT NULL,   -- admin username
    action       VARCHAR(32)  NOT NULL,   -- BLOCK | UNBLOCK | DELETE
    target_user  VARCHAR(64)  NOT NULL,
    target_id    BIGINT,
    reason       TEXT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_admin_action_target ON admin_action(target_user);
```

`enabled` already exists and already blocks login — `JpaUserDetailsService` maps it to
`.disabled(!user.isEnabled())`. Blocking sets `enabled = false` and stamps
`blocked_at`/`blocked_by`; the boolean stays the mechanism, the timestamps are for the
UI and audit.

`deleted_at` is separate from `enabled` on purpose: a deleted user is also disabled, but
"blocked" and "deleted" must be distinguishable in the list, and unblocking a deleted
user must not resurrect it.

### Anonymisation on delete

| Field | After delete |
|---|---|
| `username` | **kept** — reserves the name (D4) |
| `email` | `deleted-user-{id}@invalid` — frees the real address (D5) |
| `first_name`, `last_name` | `NULL` |
| `password` | random unguessable value (never a login path again) |
| `provider_id` | `NULL` — unlinks any Google account (see below) |
| `enabled` | `false` |
| `deleted_at` | `now()` |

**Deletion must not be reversible by signing in again.** `FederatedUserProvisioningService`
resolves a returning Google user by `(provider, provider_id)` first, then falls back to
matching on **email**. Scrubbing the email and nulling `provider_id` makes *both* lookups
miss, so a returning user gets a fresh account rather than resurrecting the deleted one.
That property depends on doing both — scrubbing the email alone would leave the
provider-id path able to revive a deleted account on the next Google sign-in. Cover it
with a test.

`profile` mirrors this for its own row on `UserDeleted`: `email`, `first_name`,
`last_name`, `display_name` → `NULL`. Delivery addresses are deleted outright (pure PII,
no reporting value). `customer_order` is untouched.

## 6. Security — three things that will not work by default

**6.1 The `roles` claim is not mapped to authorities in auth-server.** This is the one
that will silently fail. `accountApiSecurityFilterChain` (`/api/me/**`) authenticates
JWTs but installs **no** `JwtAuthenticationConverter`, so Spring's default applies and
maps only `scope`/`scp` → `SCOPE_*`. The custom `roles` claim is ignored, and
`hasRole("ADMIN")` would deny everyone. `profile` gets this right
(`ProfileSec.jwtAuthenticationConverter`); auth-server has no equivalent because nothing
has needed roles there until now.

The new admin chain must install a converter mirroring `ProfileSec`'s: map `roles` →
`ROLE_*` and merge with the scope authorities. **Write a test that a non-admin token
gets 403** — a wrong converter fails open-looking (everyone denied) in dev but is easy
to "fix" by loosening the matcher.

**6.2 The admin chain needs its own `securityMatcher`.** `/api/me/**` is `@Order(2)`;
add `/api/admin/**` as a sibling rather than widening the existing matcher, so account
and admin endpoints keep separate rules.

**6.3 Guard rails, enforced server-side:**

- An admin **cannot block or delete themselves** — compare the JWT subject to the target.
- **The last enabled admin cannot be blocked or deleted.** Count remaining enabled users
  holding `ROLE_ADMIN` and refuse if it would reach zero. Without this, one click can
  lock everyone out of the admin UI with no recovery path short of SQL.
- Deleting an already-deleted user, or unblocking one, is a **409**, not a silent no-op.

## 7. Phases

Each phase is independently shippable.

### Phase 1 — auth-server: data model + admin API

1. Liquibase changeset per §5.
2. `AdminUserController` (`/api/admin/users`), `@PreAuthorize("hasRole('ADMIN')")`:
   - `GET /api/admin/users` → id, username, email, provider, enabled, blockedAt,
     blockedBy, deletedAt, createdAt, roles
   - `POST /api/admin/users/{id}/block` (optional `reason`)
   - `POST /api/admin/users/{id}/unblock`
   - `DELETE /api/admin/users/{id}` — soft delete + anonymise
3. `AdminUserService` with the §6.3 guard rails and the `admin_action` writes.
4. New security filter chain for `/api/admin/**` **with the roles converter** (§6.1).
5. Tests: guard rails, anonymisation, a non-admin token getting 403, and — importantly —
   that a deleted Google user signing in again gets a **new** account rather than
   resurrecting the old one (§5, both lookup paths must miss).

**Done when:** an admin token can block/unblock/delete via curl; a user token gets 403.

### Phase 2 — publish the events

1. `NotificationEventPublisher` gains `publishUserBlocked` / `publishUserUnblocked` /
   `publishUserDeleted`. Same discipline as the existing methods: `@Async`,
   catch-and-log, fired from an `afterCommit` synchronization.
2. Rename it to `IdentityEventPublisher` — it stopped being notification-specific the
   moment `profile` became a consumer, and these events have no email at all.

**Done when:** blocking a user puts `UserBlocked` on `identity.events`; `notification`
logs "no template, nothing sent" rather than erroring.

### Phase 3 — profile reacts to deletion

1. `UserRegisteredConsumer` becomes `IdentityEventConsumer` and also handles
   `UserDeleted`: scrub profile PII, delete `delivery_address` rows.
2. Idempotent — scrubbing twice is a no-op, so no dedupe table (same reasoning as
   provisioning).

**Done when:** deleting a user scrubs the profile row within seconds.

### Phase 4 — the admin UI

1. `api.admin.listUsers()` etc. against `/auth/api/admin/**` (the gateway already routes
   `/auth/**`).
2. `UsersManagement.tsx` lists **auth users** (§2.1) with status badges: Active /
   Blocked / Deleted, plus a sign-in badge with the three states from §5:
   *Password* / *Password + Google* / *Google*.
3. Block / Unblock / Delete buttons. **Delete asks for typed confirmation of the
   username** — it is irreversible in practice, and a misclick in a user list is easy.
4. Deleted users render greyed with actions disabled; the current user's own row has
   actions disabled with a tooltip explaining why (§6.3).
5. Errors from guard rails surface as messages, not silent failures.

### Phase 5 — reconciliation job

A scheduled job in `profile` that finds profile rows whose auth user is deleted and
scrubs them. Covers the 1-hour retention gap in §4. Small, and the thing that makes the
event-driven path safe to rely on.

### Phase 6 (follow-up, separate) — the duplicate-identity cleanup

§2.1's Google-sub duplication is a **pre-existing data bug**, not caused by this work.
After D3 the admin page stops showing it, but the duplicate rows and their split order
history remain. Fixing it properly means deciding whether `profile` should key on
username rather than JWT `sub`, and migrating existing rows — including reassigning
orders. Size it on its own; do not bolt it onto this feature.

## 8. Risks

| Risk | Mitigation |
|---|---|
| **Admin locks everyone out** by blocking the last admin | §6.3 last-admin guard, enforced server-side and tested |
| `hasRole('ADMIN')` silently denies everyone because the `roles` claim is unmapped | §6.1 — install the converter, and test the 403 path explicitly |
| Blocked user keeps working for a few minutes | Accepted (D2). Note refresh-grant behaviour needs verifying — see below |
| `UserDeleted` lost to 1h retention → PII survives in profile | Phase 5 reconciliation job |
| Soft-deleted users clutter the admin list | Filter deleted out by default, behind a "show deleted" toggle |
| Orders point at an anonymised user | Intended (D1) — history stays intact and attributable to an id, just not to a person |
| A deleted Google user is resurrected by signing in again | Scrub the email **and** null `provider_id` — provisioning falls back from `(provider, provider_id)` to email, so only doing one leaves a revival path (§5). Tested in Phase 1 |
| Linked accounts (`LOCAL` + `provider_id`) mislabelled in the UI, or the password guard "fixed" to test `provider_id` | §5 — three states, and the existing guard is correct as written |

**One thing to verify in Phase 1, not assume:** whether Spring Authorization Server
re-checks `enabled` on the **refresh token** grant. If it does not, a blocked user could
mint fresh access tokens until their refresh token expires (~60 min default), which is
materially longer than the ~5 min window D2 accepts. If that turns out to be the case,
the cheap fix is revoking the user's stored `OAuth2Authorization` rows on block — a much
smaller change than full session revocation, and worth doing then rather than
re-litigating D2.
