# Blocking and deleting users (admin)

Status: **plan / not started**

## 1. Goal

Let an admin block, unblock and delete users from `/admin/users`. Admin-only, enforced
server-side (not just hidden in the UI).

**Two outcomes only — there is no soft delete.** A user is either removed entirely or
blocked. If their history makes deletion unsafe, they get blocked instead.

## 2. What the production data revealed

Findings that shaped the design. All pre-existing; none caused by this feature.

### 2.1 The admin page lists the wrong thing

`UsersManagement.tsx` calls `api.profile.getProfiles()` → `GET /api/profiles`. That
returns **profiles**, which are not the same set as users:

```
auth users (8):  admin adria davide iaka itiganas manager repro-test-1785224224 user
profiles   (9):  admin adria davide iaka itiganas manager user
                 + 102919241495532217479   ← a Google sub
                 + external-service        ← a client-credentials service account
```

- **One human, two profile rows.** `iaka` has `provider_id = 102919241495532217479`, and
  a *separate* profile row exists under that number with the same email. `profile` keys
  rows on the JWT `sub` — the username for form login, the Google `sub` for federated
  login. Both identities have orders.
- **A service account appears as a user.** `external-service` got a profile row by
  calling a `/me` endpoint with a client-credentials token.
- **A real user is invisible.** `repro-test-1785224224` has no profile row.

So "block this row" is ambiguous today. **The list must be built from auth-server users**
(D3), even though the page is served by profile.

### 2.2 Identity states — `provider` alone does not mean "Google"

`FederatedUserProvisioningService` produces **three** states:

| `provider` | `provider_id` | Meaning | Password? |
|---|---|---|---|
| `LOCAL` | `NULL` | Registered with the form | yes |
| `LOCAL` | `<google sub>` | **Linked** — registered locally, later signed in with Google | yes, still works |
| `GOOGLE` | `<google sub>` | Provisioned by Google sign-in | no (random unguessable value) |

The middle state is deliberate: a Google sign-in matching an existing user by email keeps
`provider = LOCAL` and only adds the subject, *"so the existing password still works"*.
`iaka` is in that state in production.

- The admin list needs **three sign-in badges**, not a LOCAL/GOOGLE toggle.
- **The password-change guard is correct as written.** `PasswordChangeService:31` rejects
  `provider != LOCAL`, so linked accounts can still change their password — right,
  because they have one. Do not "fix" it to test `provider_id`.

### 2.3 Order status cannot tell you whether money moved

- **`CANCELLED` does not mean "no money".** `OrderStatus` allows `PAID → CANCELLED`
  (`OrderStatus.java:21`), so a cancelled order may have been paid and refunded.
- **`PENDING` orders already have Stripe PaymentIntents.** `payment` holds a `CREATED`
  row per pending order (4 in production). No money moved, but a Stripe object exists.

So eligibility keys on **payment** status, never order status (§4.2).

## 3. Architecture — profile orchestrates, auth-server executes

The admin page is served by profile, and auth-server is an OIDC provider rather than a
user-administration API. So **profile owns the operation**; auth-server exposes a narrow
internal API that performs the identity change.

```
Admin UI  ──►  profile  ─────────────►  shop      "is this user purgeable?"
 (JWT,        (ROLE_ADMIN               (SCOPE_internal)
  ROLE_ADMIN)  enforced here)               │
                   │                        └─► payment (already a shop dependency)
                   │
                   ├──►  auth-server   POST /api/internal/users/{id}/block
                   │     (SCOPE_identity.admin)  /unblock, DELETE /{id}
                   │
                   └──►  own DB: delete profile row + addresses on hard delete
```

**Authorization lives in profile.** It is already a resource server with a working
`roles` → `ROLE_*` converter (`ProfileSec:60` uses `hasRole("ADMIN")` today). auth-server
does **not** need to understand roles for this — a welcome simplification, because its JWT
chain installs no `JwtAuthenticationConverter`, so `hasRole('ADMIN')` there would silently
deny everyone.

### 3.1 auth-server's internal API must not accept `SCOPE_internal`

`internal-service` is a **shared** client identity — profile already uses those exact
credentials to call storage. If "delete this user" accepted `SCOPE_internal`, anything
holding that token could delete users, and leaking one service's credentials would put
the identity store in the blast radius.

**Register a separate client for this**, held only by profile:

| | |
|---|---|
| Client | `identity-admin` (new `RegisteredClient` in auth-server) |
| Scope | `identity.admin` |
| Guard | `.requestMatchers("/api/internal/users/**").hasAuthority("SCOPE_identity.admin")` |

### 3.2 The dependency cycle is real but bounded

auth-server → `identity.events` → profile → HTTP → auth-server is a cycle. It is
acceptable because the directions carry different things and neither blocks the other:
the event path is asynchronous facts, the HTTP path is an admin-initiated command that
needs an answer. Stated explicitly so nobody "fixes" it by moving authorization back into
auth-server.

## 4. Decisions

| # | Decision | Resolution |
|---|---|---|
| D1 | Delete semantics | **Hard delete or nothing.** No soft delete, no anonymisation. If the user cannot be hard-deleted they are **blocked** instead, and the admin is told why. |
| D2 | When is hard delete allowed? | When **no order of theirs has a payment in `SUCCEEDED` or `REFUNDED`** (§4.2). Their unpaid orders are deleted with them. |
| D3 | What does the admin page list? | **auth-server users**, fetched by profile and enriched with profile data (§2.1). |
| D4 | Who authorizes? | **profile**, via `hasRole("ADMIN")`. auth-server's internal API is scope-gated only (§3.1). |
| D5 | Blocking and live sessions | `enabled = false` refuses new logins. An already-issued access token works until it expires (~5 min — Spring Authorization Server default, no `TokenSettings` configured). Accepted; no session-revocation machinery. |
| D6 | Audit trail | **Yes** — `admin_action` in profile: actor, action, target, order count, outcome (`DONE` / `BLOCKED_INSTEAD`), timestamp. |
| D7 | Email the affected user? | **No.** `notification` stays uninvolved. |
| D8 | Username / email reuse after delete | **Freed** — the row is gone. Safe, because hard delete only happens when there is no paid history to inherit. |

### 4.1 Accepted consequence: paid users keep their PII forever

With no soft delete, a customer who has ever paid can only be blocked. Their email and
name remain in `authdb` and `profiledb` indefinitely, with no erasure path. That is a
deliberate trade for simplicity and for keeping order history reconcilable against
Stripe. If an erasure request ever has to be honoured it will need a new mechanism —
anonymisation-in-place — designed then.

### 4.2 The eligibility rule

> **Hard-deletable iff no order of theirs has a payment in `SUCCEEDED` or `REFUNDED`.**

| Payment status | Money moved? | Effect |
|---|---|---|
| `CREATED` | no — uncaptured PaymentIntent | delete allowed |
| `SUCCEEDED` | **yes** | block instead |
| `REFUNDED` | **yes** (both directions) | block instead |

Order status is not consulted at all (§2.3).

**Leftover Stripe PaymentIntents.** Deleting a user with `CREATED` payments leaves
uncaptured PaymentIntents on Stripe with no local row. They expire and never charged
anyone — untidy, permanent, harmless. Cancelling them via the Stripe API during the purge
is a reasonable later refinement.

**The race.** A user could place an order between the check and the delete. profile
**blocks first, then checks, then deletes**, shrinking the window to the access-token
lifetime (D5). The residual cost of a miss is one orphaned order row, caught by the
Phase 6 sweep — not lost money.

## 5. APIs

### 5.1 New — profile (the admin surface)

| Endpoint | Auth | Purpose |
|---|---|---|
| `GET /api/profiles/admin/users` | `ROLE_ADMIN` | list auth users + profile data + sign-in state |
| `POST /api/profiles/admin/users/{id}/block` | `ROLE_ADMIN` | block |
| `POST /api/profiles/admin/users/{id}/unblock` | `ROLE_ADMIN` | unblock |
| `DELETE /api/profiles/admin/users/{id}` | `ROLE_ADMIN` | block → check → delete, or report `BLOCKED_INSTEAD` |

`DELETE` returns the outcome explicitly —
`{ "outcome": "BLOCKED_INSTEAD", "paidOrderCount": 9 }` — so the UI can explain rather
than appear to fail.

### 5.2 New — auth-server (internal, executes only)

| Endpoint | Auth |
|---|---|
| `GET /api/internal/users` | `SCOPE_identity.admin` |
| `POST /api/internal/users/{id}/block` and `/unblock` | `SCOPE_identity.admin` |
| `DELETE /api/internal/users/{id}` | `SCOPE_identity.admin` |

No role logic, no order logic — it does what it is told and reports what happened.
`authorities` and `password_reset_token` clear via the existing `ON DELETE CASCADE` FKs.

### 5.3 New — shop (orders by user)

There is **no way to fetch one user's orders** today: `GET /api/shop/orders` returns the
caller's own, `/orders/all` returns everything, `/orders/{id}` takes an **order** id.
Filtering `/orders/all` in the browser would pull every order in the system to count one
user's, and hand the admin UI every customer's order data to do it.

| Endpoint | Auth | Caller |
|---|---|---|
| `GET /api/shop/users/{username}/orders` | `ROLE_ADMIN` | admin UI — display + confirmation count |
| `GET /api/shop/internal/users/{username}/purge-eligibility` | `SCOPE_internal` | profile — `{ eligible, orderIds[], paidOrderCount }` |
| `DELETE /api/shop/internal/users/{username}/orders` | `SCOPE_internal` | profile — delete unpaid orders, publish `OrdersPurged` |

**Do not root these under `/api/shop/orders/`** — `{id}` there is an order id, so a
`{username}` segment would shadow it. `ShopRoute` already carries a comment about this
trap for `/orders/all`. `shop` has no `/internal/**` convention yet; add one mirroring
`ProfileSec:55`.

## 6. Data model

### profile (Liquibase)

```sql
-- The record of who did what. Lives with the orchestrator, not the executor.
CREATE TABLE admin_action (
    id          BIGSERIAL    PRIMARY KEY,
    actor       VARCHAR(64)  NOT NULL,   -- admin username from the JWT
    action      VARCHAR(32)  NOT NULL,   -- BLOCK | UNBLOCK | DELETE
    target_user VARCHAR(64)  NOT NULL,
    outcome     VARCHAR(32)  NOT NULL,   -- DONE | BLOCKED_INSTEAD | FAILED
    order_count INT,
    reason      TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_admin_action_target ON admin_action(target_user);
```

### auth-server (Liquibase)

```sql
ALTER TABLE users ADD COLUMN blocked_at TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN blocked_by VARCHAR(64);
```

No `deleted_at` — deletion removes the row (D1). `enabled` remains the block mechanism;
`JpaUserDetailsService` already maps it to `.disabled(!user.isEnabled())`.

### The cascade on hard delete

Only `shop` knows which orders belong to a username — everything downstream keys on
`order_id`:

| Database | Table | Keyed by |
|---|---|---|
| shopdb | `customer_order` | **`username`** |
| shopdb | `order_item` | `order_id` |
| paymentdb | `payment` | `order_id` |
| deliverydb | `delivery` | `order_id` |
| deliverydb | `delivery_tracking` | `delivery_id` |

So "each service deletes rows for this username" **cannot work** — payment and delivery
have no username to match on. shop resolves the mapping and publishes
`OrdersPurged { orderIds }` via its **existing outbox**; payment and delivery consume it
and delete by `order_id`.

Left alone deliberately: `paymentdb.stripe_event` (webhook dedupe — deleting it would let
processed webhooks replay) and the `outbox` / `delivery_event` tables (plumbing, not user
data).

## 7. Guard rails — enforced in profile, server-side

- An admin **cannot block or delete themselves** (compare against the JWT subject).
- **The last enabled admin cannot be blocked or deleted.** Without this, one click locks
  everyone out of the admin UI with no recovery short of SQL.
- Unblocking a user who is not blocked, or deleting one who does not exist, is a **409**,
  not a silent success.

## 8. Phases

### Phase 1 — auth-server: the internal execution API
1. Liquibase: `blocked_at`, `blocked_by`.
2. `identity-admin` `RegisteredClient` + scope `identity.admin` (§3.1).
3. Filter chain for `/api/internal/users/**` gated on `SCOPE_identity.admin`.
4. List / block / unblock / delete. Delete is a plain `DELETE` — FKs cascade.
5. Tests: a token carrying only `SCOPE_internal` gets **403** — this is the security
   property of §3.1 and must not regress. Delete removes authorities and reset tokens.

### Phase 2 — shop: orders by user
1. `GET /api/shop/users/{username}/orders` (`ROLE_ADMIN`).
2. `GET /api/shop/internal/users/{username}/purge-eligibility` (`SCOPE_internal`) —
   classifies via **payment** status (§4.2), never order status.
3. `DELETE /api/shop/internal/users/{username}/orders` → delete + publish `OrdersPurged`.
4. `/api/shop/internal/**` security rule mirroring `ProfileSec:55`.

### Phase 3 — payment + delivery: consume `OrdersPurged`
Delete by `order_id`. Idempotent by key, so no dedupe tables.

### Phase 4 — profile: the orchestrator
1. `identity-admin` client registration; `AdminUserService` doing
   block → check eligibility → delete-or-report.
2. `admin_action` writes, including outcome and order count.
3. Guard rails (§7).
4. Hard delete also removes profile's own row and `delivery_address` entries.
5. Tests: guard rails, and that a paid user comes back `BLOCKED_INSTEAD` rather than
   being deleted.

### Phase 5 — the admin UI
1. List from `GET /api/profiles/admin/users`, with Active / Blocked badges and the three
   sign-in states (§2.2).
2. Block / Unblock / Delete. **Delete asks for typed confirmation of the username** and
   shows the order count first.
3. When the result is `BLOCKED_INSTEAD`, say so plainly — "this user has 9 paid orders,
   so they were blocked rather than deleted" — never a silent partial success.
4. The current admin's own row has its actions disabled, with a tooltip explaining why.

### Phase 6 — orphan sweep
A read-only report listing `customer_order` rows whose username has no user, and
`payment` / `delivery` rows whose `order_id` has no order. Catches a half-completed
cascade, which otherwise leaves no trace.

### Phase 7 (separate) — duplicate-identity cleanup
§2.1's Google-sub duplication is a pre-existing data bug. After D3 the admin page stops
showing it, but the duplicate rows and their split order history remain. Fixing it means
deciding whether `profile` should key on username rather than JWT `sub`, and migrating
rows including order reassignment. Size it on its own.

## 9. Rejected alternative: soft delete + anonymise

Considered, and dropped in favour of "hard delete or block". It kept a scrubbed row so
that any user — including paying customers — could be "deleted" while order history
stayed intact.

Dropped because it doubles the state space (active / blocked / deleted, each needing UI
treatment, list filtering, and rules like "unblocking a deleted user must not resurrect
it") to serve a case that block already covers adequately. The cost is §4.1: paid users
can never have their PII removed. Accepted deliberately.

The earlier variant of *unconditional* hard delete — cascading away paid orders too — was
rejected for a different reason: Stripe keeps its records and cannot be told to forget,
so shop and Stripe would diverge permanently, revenue reports would change retroactively,
and a later chargeback would have no local order to answer with. §4.2 is what remains of
that idea, narrowed to the cases where no money ever moved.

## 10. Risks

| Risk | Mitigation |
|---|---|
| **Admin locks everyone out** by blocking the last admin | §7 guard, server-side, tested |
| **`SCOPE_internal` leak becomes user deletion** | §3.1 — dedicated `identity-admin` client and scope; Phase 1 tests the 403 |
| Half-completed cascade orphans payments/deliveries | `OrdersPurged` rides shop's existing outbox (at-least-once); Phase 6 sweep |
| A naive cascade misses payment/delivery entirely | They key on `order_id` — only shop resolves the mapping (§6) |
| Paid users accumulate un-erasable PII | Accepted (§4.1) |
| Leftover Stripe PaymentIntents after a purge | Accepted (§4.2); cancel via the Stripe API later if it matters |
| Blocked user still works for a few minutes | Accepted (D5) |
| Someone "fixes" the profile → auth-server cycle by moving authorization into auth-server | §3.2 — and auth-server has no roles converter, so `hasRole` there would deny everyone |
