# Blocking and deleting users (admin)

Status: **plan / not started** — D1 revised 2026-07-28 from soft delete to hard delete with an order cascade
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
| D1 | What does "delete" do? | **Hard delete, cascading to the user's orders.** The `users` row is physically removed, and every order that user placed — plus its items, payments, refunds and deliveries — is deleted across all four services. Nothing is retained. Consequences in §5.3. |
| D2 | Does blocking kill live sessions? | **No — accept the token window.** `enabled=false` refuses new logins. An already-issued access token stays valid until it expires (~5 min, Spring Authorization Server default — no `TokenSettings` are configured). No session-revocation infrastructure. |
| D3 | What does the admin page list? | **auth-server users**, via a new admin API, enriched with profile data (§2.1). |
| D4 | Is a deleted username reusable? | **Yes** — the row is gone, so the unique constraint releases it. Safe here precisely *because* the cascade is total: a re-registered `manager` inherits no orders, since none survive. |
| D5 | Is a deleted email reusable? | **Yes**, same reason. The person can sign up again and gets a genuinely fresh account. |
| D6 | Audit trail? | **Yes — and now it is the only surviving record.** `admin_action` records who deleted whom, when, and **how many orders went with them**. With soft delete the row itself was the evidence; with hard delete this table is all that remains. |
| D7 | Email the affected user? | **No.** A "you have been blocked" email is hostile and useful to nobody; a "your account was deleted" mail would go to an address we just scrubbed. `notification` stays uninvolved. |

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
| `UserDeleted` | `username`, `deletedBy`, `occurredAt` | `profile` (delete row + addresses), `shop` (resolve orders, then fan out — §5.1) |
| `OrdersPurged` | `orderIds[]`, `username`, `occurredAt` — on **`orders.events`** | `payment` (delete payment + refund), `delivery` (delete delivery + tracking) |

`notification` will consume these and find no template, log
`No EMAIL template for UserDeleted — nothing sent`, and move on. That is the intended
behaviour (D7), and it is already how `TemplateRegistry` handles unknown types.

**Retention and reliability are materially more serious under hard delete** — a lost
`UserDeleted` now orphans orders, payments and deliveries rather than merely leaving
stale PII. See §5.2.

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

Under hard delete these mostly take care of themselves — removing the row releases
`username`, `email` and the `(provider, provider_id)` slot in one go, so a deleted person
can register again cleanly by either method (D4, D5). Two things still follow from them:

- **The FKs do the local work.** `authorities` and `password_reset_token` both reference
  `users(id)` `ON DELETE CASCADE`, so a single `DELETE` clears them. Nothing beyond
  authdb is covered by FKs — that is what §5.1 is for.
- **Blocking must not be confused with deleting.** `enabled` stays the block mechanism;
  there is no longer any deleted state on the row to collide with it.

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
-- Blocking only. There is no deleted_at: deletion removes the row (D1).
ALTER TABLE users ADD COLUMN blocked_at TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN blocked_by VARCHAR(64);

-- The only surviving record of a deletion (D6). order_count/order_ids are
-- filled in by shop's cascade result; see §5.2.
CREATE TABLE admin_action (
    id           BIGSERIAL    PRIMARY KEY,
    actor        VARCHAR(64)  NOT NULL,   -- admin username
    action       VARCHAR(32)  NOT NULL,   -- BLOCK | UNBLOCK | DELETE
    target_user  VARCHAR(64)  NOT NULL,
    target_id    BIGINT,
    order_count  INT,
    reason       TEXT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_admin_action_target ON admin_action(target_user);

-- Makes a failed cascade recoverable rather than invisible (§5.2). Holds no
-- PII beyond the username; rows may be pruned once confirmed.
CREATE TABLE deleted_user (
    username             VARCHAR(64)  PRIMARY KEY,
    deleted_by           VARCHAR(64)  NOT NULL,
    deleted_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    cascade_confirmed_at TIMESTAMPTZ
);
```

`enabled` already exists and already blocks login — `JpaUserDetailsService` maps it to
`.disabled(!user.isEnabled())`. Blocking sets `enabled = false` and stamps
`blocked_at`/`blocked_by`; the boolean stays the mechanism, the timestamps are for the
UI and audit.

There is no `deleted_at` on `users`: with hard delete the row is gone, so "blocked" and
"deleted" can never be confused in the list — a deleted user simply is not there.

### 5.1 The cascade — and why it cannot be one step

**Only `shop` knows which orders belong to a username.** Everything downstream keys on
`order_id`:

| Database | Table | Keyed by |
|---|---|---|
| shopdb | `customer_order` | **`username`** |
| shopdb | `order_item` | `order_id` |
| paymentdb | `payment` | `order_id` |
| paymentdb | `refund` | `order_id`, `payment_id` |
| deliverydb | `delivery` | `order_id` |
| deliverydb | `delivery_tracking` | `delivery_id` |

So a design where each service "deletes its rows for this username" **cannot work** —
`payment` and `delivery` have no username to match on and would silently leave every row
orphaned. The cascade has to run in two hops, with shop resolving the mapping:

```
auth-server   DELETE user (authorities + password_reset_token cascade via FK)
     │
     └──► identity.events: UserDeleted { username }
                │
      ┌─────────┼──────────────────────────────┐
      ▼         ▼                              ▼
   profile   notification                    shop
  delete    (no template,                resolve username → orderIds
  row +      sends nothing)              delete order_item, customer_order
  addresses                              publish OrdersPurged { orderIds }
                                                  │
                                        ┌─────────┴─────────┐
                                        ▼                   ▼
                                     payment             delivery
                              delete refund,        delete delivery_tracking,
                              payment by order_id   delivery by order_id
```

`OrdersPurged` is a new event on `orders.events` — shop already produces there via its
outbox, so this reuses existing machinery rather than adding any.

**Left alone deliberately:**

- `paymentdb.stripe_event` — a webhook dedupe/audit log keyed by Stripe's event id, not
  by user. Deleting rows here would let already-processed webhooks be reprocessed.
- `deliverydb.delivery_event` and the `outbox` tables — these are outbox/relay
  plumbing (`aggregate_id`, `payload`, `status`), not user data. They drain on their own.

### 5.2 Reliability — a lost event now means orphaned financial rows

This is what changes most with hard delete. When `UserDeleted` only scrubbed profile PII,
losing it was untidy. Now, losing it leaves orders, payments and deliveries for a user
who no longer exists — permanently, with no way to find them, because the username that
identified them is gone.

`identity.events` is fire-and-forget with 1-hour retention (both deliberate, for email).
Neither is good enough here. Three changes:

1. **A `deleted_user` tombstone in auth-server**, written in the same transaction as the
   `DELETE`: `username`, `deleted_by`, `deleted_at`, `cascade_confirmed_at`. It holds no
   PII beyond the username, and exists so a failed cascade is *recoverable* rather than
   invisible.
2. **The delete API blocks on the Kafka ack** (`.get()` with the existing 2s
   `max.block.ms`) and returns **502** if the publish fails, with the user already
   deleted and the tombstone unconfirmed. This is an admin action, not a user request
   path, so blocking briefly is fine — and the operator gets told, rather than the
   failure vanishing into a log.
3. **`POST /api/admin/users/purge/{username}`** re-publishes from the tombstone. Retry is
   a button, not a database session. No scheduler is added to auth-server, keeping the
   §2 "no relay in auth-server" property intact.

Consumers delete by key, so redelivery is naturally idempotent — no dedupe table needed
on the cascade path.

**Retention.** A 1-hour window means a consumer down overnight misses the cascade
entirely. Either bump `identity.events` retention (it carries reset tokens, so this
weakens D2 of the notification design) **or** publish `UserDeleted` to a separate
longer-retention topic. Recommendation: **separate topic**, so the reset-token retention
argument stays intact. Decide in Phase 1.

### 5.3 What is permanently lost — accept before building

- **Revenue history changes retroactively.** Deleting a user's orders removes their
  value from every shop total and report. Figures quoted last month will not reproduce.
- **Stripe keeps its records.** We cannot delete charges on Stripe's side, so shop and
  Stripe diverge permanently for that customer. Reconciling "Stripe says we took €X,
  shop has no record" becomes impossible.
- **Chargebacks and refunds become unanswerable.** A dispute arriving weeks later has no
  local order, no payment row, and no address to check against.
- **No undo.** With soft delete a mistake was a flag flip. Here it is a restore from
  backup, across four databases, at a consistent point in time.

Two mitigations that cost little and are strongly recommended:

- **Type-to-confirm in the UI**, showing the exact order count that will be destroyed
  ("this will permanently delete 16 orders") — not a yes/no dialog.
- **`admin_action` records the order count and ids**, so at minimum there is a record
  that *something* existed, even though its contents are gone.

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
     blockedBy, createdAt, roles, and the §5 sign-in state
   - `POST /api/admin/users/{id}/block` (optional `reason`)
   - `POST /api/admin/users/{id}/unblock`
   - `DELETE /api/admin/users/{id}` — hard delete + tombstone + publish (§5.2)
   - `POST /api/admin/users/purge/{username}` — re-publish a failed cascade
3. `AdminUserService` with the §6.3 guard rails and the `admin_action` writes.
4. New security filter chain for `/api/admin/**` **with the roles converter** (§6.1).
6. Decide the retention question in §5.2 (separate topic recommended).
7. Tests: guard rails, a non-admin token getting 403, tombstone written in the same
   transaction as the delete, and a 502 + unconfirmed tombstone when the publish fails.

**Done when:** an admin token can block/unblock/delete via curl; a user token gets 403.

### Phase 2 — publish the events

1. `NotificationEventPublisher` gains `publishUserBlocked` / `publishUserUnblocked` /
   `publishUserDeleted`. Same discipline as the existing methods: `@Async`,
   catch-and-log, fired from an `afterCommit` synchronization.
2. Rename it to `IdentityEventPublisher` — it stopped being notification-specific the
   moment `profile` became a consumer, and these events have no email at all.

**Done when:** blocking a user puts `UserBlocked` on `identity.events`; `notification`
logs "no template, nothing sent" rather than erroring.

### Phase 3 — the cascade

1. **profile**: `UserRegisteredConsumer` becomes `IdentityEventConsumer` and also handles
   `UserDeleted` — delete `delivery_address` rows, then the `user_profile` row.
2. **shop**: consume `UserDeleted`, resolve `username → orderIds`, delete `order_item`
   then `customer_order`, and publish `OrdersPurged` **via the existing outbox** (so this
   hop is at-least-once for free).
3. **payment**: consume `OrdersPurged`, delete `refund` then `payment` by `order_id`.
   Leave `stripe_event` (§5.1).
4. **delivery**: consume `OrdersPurged`, delete `delivery_tracking` then `delivery` by
   `order_id`.
5. All deletes are by key and therefore idempotent — no dedupe tables.

**Done when:** deleting a user with orders leaves zero rows for them in all four
databases. Verify with a direct count per table, not by trusting logs.

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

### Phase 5 — orphan sweep

A read-only report (admin endpoint or a query in the runbook) listing `customer_order`
rows whose `username` has no `users` row, and `payment`/`delivery` rows whose `order_id`
has no `customer_order`. This is how a half-completed cascade gets noticed at all — under
hard delete there is no other trace. Pair it with the `purge/{username}` retry from §5.2.

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
| `UserDeleted` lost to 1h retention | §5.2 — longer-retention topic, tombstone, and retry |

| **Revenue history changes retroactively** | Accepted (D1, §5.3). Reports run before and after a deletion will not agree |
| **shop and Stripe diverge permanently** | Accepted — Stripe's records cannot be deleted. A later chargeback has nothing local to reconcile against (§5.3) |
| **Half-completed cascade orphans payments/deliveries** | Tombstone + blocking ack + `purge/{username}` retry (§5.2), plus the Phase 5 orphan sweep |
| **A naive cascade misses payment/delivery entirely** | They key on `order_id`, not `username` — only shop can resolve the mapping, hence the two-hop design (§5.1) |
| **Accidental deletion is unrecoverable** | Type-to-confirm showing the exact order count; `admin_action` retains the count and ids (§5.3) |
| A deleted user signing in again | Not a risk under hard delete — both provisioning lookups miss a row that no longer exists, so Google sign-in creates a genuinely fresh account |
| Linked accounts (`LOCAL` + `provider_id`) mislabelled in the UI, or the password guard "fixed" to test `provider_id` | §5 — three states, and the existing guard is correct as written |

**One thing to verify in Phase 1, not assume:** whether Spring Authorization Server
re-checks `enabled` on the **refresh token** grant. If it does not, a blocked user could
mint fresh access tokens until their refresh token expires (~60 min default), which is
materially longer than the ~5 min window D2 accepts. If that turns out to be the case,
the cheap fix is revoking the user's stored `OAuth2Authorization` rows on block — a much
smaller change than full session revocation, and worth doing then rather than
re-litigating D2.
