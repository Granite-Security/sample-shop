# Blocking and deleting users — as built

Status: **implemented** (phases 1–6; phase 7 not started)
Companion to [`blocking-users.md`](blocking-users.md), which holds the *why* — the
production findings, the decisions, and the rejected alternatives. This document is the
*what*: the shape of the thing that now exists.

## 1. The one-paragraph version

An admin blocks, unblocks or deletes a user from `/admin/users`. **There are two outcomes
and no soft delete:** the user is either removed entirely or blocked. Deletion is only
allowed when no order of theirs ever moved money; otherwise they are blocked and the admin
is told so explicitly. `profile` decides and authorizes; `auth-server` only executes;
`shop` is the only service that can map a username to orders, so it drives the cascade
into `payment` and `delivery` over Kafka.

## 2. High-level design

### 2.1 Who does what

```
Browser (admin, ROLE_ADMIN)
    │
    ▼
gateway :8080  ──────────────►  profile :8064          THE ORCHESTRATOR
                                 (ROLE_ADMIN enforced here)
                                     │
                                     ├──► auth-server :9090   THE EXECUTOR
                                     │    SCOPE_identity.admin
                                     │    block / unblock / delete the identity
                                     │
                                     ├──► shop :8061          THE ORDER AUTHORITY
                                     │    SCOPE_internal
                                     │    "did this user ever move money?"
                                     │    delete their unpaid orders
                                     │        │
                                     │        └──► payment :8062  (payment status)
                                     │
                                     └──► own DB: profile row + delivery addresses
```

Three properties are load-bearing:

**Authorization lives in `profile`, not `auth-server`.** `profile` is already a resource
server with a working `roles` → `ROLE_*` converter. `auth-server`'s JWT chains install no
`JwtAuthenticationConverter` at all, so `hasRole('ADMIN')` there would silently deny
everyone. Its internal API is therefore scope-gated only, and understands no roles.

**`auth-server`'s internal API refuses `SCOPE_internal`.** `internal-service` is a *shared*
service identity — `profile` uses those same credentials to call `storage`, and `shop` now
uses them to call `payment`. If "delete this user" accepted it, leaking any one service's
credentials would put the identity store in the blast radius. A separate `identity-admin`
client with its own `identity.admin` scope, held only by `profile`, is what actually guards
this.

**Only `shop` can resolve a username to orders.** Everything downstream keys on `order_id`.
"Each service deletes rows for this username" is not implementable in `payment` or
`delivery` — they have no username to match on.

### 2.2 The eligibility rule

> **Hard-deletable iff no order of theirs has a payment in `SUCCEEDED` or `REFUNDED`.**

Order status is never consulted. `PAID → CANCELLED` is a legal transition, so a cancelled
order may well have been paid and refunded; and pending orders already hold uncaptured
Stripe PaymentIntents. Only the *payment* status says whether money moved.

`REFUNDED` blocks deletion too: money moved in both directions and Stripe keeps both
records.

### 2.3 The delete sequence

```
1. guard rails                     profile   — reject self-action, protect last admin
2. block                           auth-server — enabled = false, first
3. purge-eligibility               shop      — via payment status
4a. eligible   → purge orders      shop      — delete + publish OrdersPurged
    → delete identity              auth-server
    → delete profile row/addresses profile
    → audit DONE
4b. not eligible → audit BLOCKED_INSTEAD, return the paid-order count
```

**Blocking happens before the check, deliberately.** A user could place an order between
the check and the purge; blocking first shrinks that window to the access-token lifetime
(~5 min). If the check then says they have paid orders, the block is the final state —
that *is* the `BLOCKED_INSTEAD` outcome, not a failed cleanup.

`shop` re-checks eligibility itself before purging, rather than trusting `profile`'s
earlier answer. Deleting a paid order is the one outcome the design explicitly rejects:
Stripe cannot be told to forget, so `shop` and Stripe would diverge permanently.

### 2.4 The cascade

`shop` writes `OrdersPurged { username, orderIds }` to its **existing outbox**, relayed to
`orders.events`. `payment` and `delivery` consume it and delete by `order_id`.

At-least-once delivery needs no dedupe table here: deleting rows that are already gone is
a no-op.

| Database | Table | Keyed by | On purge |
|---|---|---|---|
| shopdb | `customer_order` | **`username`** | deleted |
| shopdb | `order_item` | `order_id` | deleted |
| paymentdb | `payment` | `order_id` | deleted |
| paymentdb | `refund` | `order_id` | deleted |
| deliverydb | `delivery` | `order_id` | deleted |
| deliverydb | `delivery_tracking` | `delivery_id` | deleted (ids resolved first) |
| profiledb | `user_profile`, `delivery_address` | `username` | deleted |
| paymentdb | `provider_event` | — | **left alone** — webhook dedupe log; clearing it would let processed webhooks replay |
| paymentdb | `payment_attempt` | cascade | no explicit delete — `ON DELETE CASCADE` on `payment_id`, so attempts go with their payment |
| shopdb/paymentdb/deliverydb | `outbox`, `delivery_event` | — | **left alone** — plumbing, not user data |
| profiledb | `user_file` | `username` | **not handled** — see §6 |

## 3. Low-level design

### 3.1 API surface

**profile — the admin surface** (all `ROLE_ADMIN`, via `/api/profiles/admin/**`)

| Endpoint | Purpose |
|---|---|
| `GET /api/profiles/admin/users` | auth users + profile data + sign-in state |
| `POST /api/profiles/admin/users/{username}/block` | block |
| `POST /api/profiles/admin/users/{username}/unblock` | unblock |
| `DELETE /api/profiles/admin/users/{username}` | block → check → delete-or-report |
| `GET /api/profiles/admin/orphans` | read-only reconciliation report |

`DELETE` returns `200` with a body, never `204` — `{ "outcome": "BLOCKED_INSTEAD",
"paidOrderCount": 9 }` is a real outcome the UI has to explain.

**auth-server — internal, executes only** (`SCOPE_identity.admin`)

| Endpoint | Notes |
|---|---|
| `GET /api/internal/users` | |
| `POST /api/internal/users/{username}/block` | body `{ "actor": "admin" }` — auth-server does not authenticate the admin, profile tells it who to record |
| `POST /api/internal/users/{username}/unblock` | |
| `DELETE /api/internal/users/{username}` | plain row delete; FKs cascade |

**shop** — `GET /api/shop/users/{username}/orders` (`ROLE_ADMIN`), plus `SCOPE_internal`:
`GET /api/shop/internal/users/{username}/purge-eligibility`,
`DELETE /api/shop/internal/users/{username}/orders`, and for the sweep
`GET /api/shop/internal/orders/owners` and `POST /api/shop/internal/orders/unknown`.

**payment** (`SCOPE_internal`) — `POST /api/payments/internal/statuses`,
`GET /api/payments/internal/order-ids`.
**delivery** (`SCOPE_internal`) — `GET /api/delivery/internal/order-ids`.

### 3.2 Route-ordering traps

Three places where a literal segment would be swallowed by a path variable, each guarded by
registration order and a comment:

- `/api/shop/users/{username}/orders` is **not** under `/api/shop/orders/`, where `{id}` is
  an *order* id — a `{username}` segment there would shadow it.
- `/api/shop/internal/**` and `/api/delivery/internal/**` security rules precede the
  `{username}`/`{orderId}` rules: `internal` is neither a username nor an order id.
- `/api/profiles/admin/**` precedes `/api/profiles/{username}`, for the same reason `me`
  already had to.

### 3.3 Identity states — three, not two

`provider` alone does not mean "signs in with Google":

| `provider` | `provider_id` | Meaning | Password? | Badge |
|---|---|---|---|---|
| `LOCAL` | `NULL` | registered with the form | yes | Password |
| `LOCAL` | `<google sub>` | **linked** — registered locally, later signed in with Google | yes, still works | Password + Google |
| `GOOGLE` | `<google sub>` | provisioned by Google sign-in | no | Google |

`AuthUser.signInState()` derives this; the UI renders all three. The password-change guard
in `PasswordChangeService` tests `provider != LOCAL` and is **correct as written** — linked
accounts have a password and may change it. Do not "fix" it to test `provider_id`.

### 3.4 The list is built from auth users

`GET /api/profiles/admin/users` starts from `auth-server`'s user list and enriches it with
profile rows, never the reverse. In production the two sets genuinely differ: a Google
`sub` and a client-credentials service account both have profile rows without being users,
and at least one real user has no profile row. `AdminUserView.hasProfile` reports which.

### 3.5 Guard rails (enforced server-side in `profile`)

| Rule | Why |
|---|---|
| An admin cannot block or delete themselves | compared against the JWT subject |
| The last **enabled** admin cannot be blocked or deleted | otherwise one click locks everyone out with no recovery short of SQL; a blocked admin does not count as remaining |
| Unblocking a user who is not blocked → `409` | not a silent success — it means the admin acted on a stale list |
| Deleting a user who does not exist → `409` | as specified in the plan §7 |

The UI additionally disables the current admin's own actions with an explanatory tooltip,
but that is convenience — the rules are enforced server-side and tested there.

### 3.6 Audit trail

`profiledb.admin_action`: `actor`, `action` (`BLOCK`/`UNBLOCK`/`DELETE`), `target_user`,
`outcome` (`DONE`/`BLOCKED_INSTEAD`/`FAILED`), `order_count`, `reason`, `created_at`.

It lives with the orchestrator, not the executor: `profile` is where the admin is
authenticated and where the block-instead-of-delete decision is made, so it is the only
place that knows the whole story. **Refused and failed attempts are recorded as `FAILED`**
before the error is re-raised — an attempt is still an action an admin made.

### 3.7 Reactive assembly: `Mono.defer`

The delete chain uses `Mono.defer` at each downstream step rather than
`guardRails(...).then(client.block(...))`. The latter invokes the client during *assembly*,
before the guard rails can reject. Nothing would be sent — WebClient's `Mono` is cold — but
"we never even asked" is the property worth holding, and it is what makes the
`never()` assertions in the tests mean anything.

### 3.8 Failure ordering

`profile` deletes its own rows **last**, after `auth-server` confirms the identity is gone.
If anything upstream fails, the user still exists and their profile should still describe
them. Tested.

A failed call to `payment` during the eligibility check propagates as an error rather than
collapsing to an empty status map — an empty map reads as "nothing was ever paid", which
would turn a payment outage into permission to delete a paying customer's orders.

## 4. Configuration

| Variable | Where | Notes |
|---|---|---|
| `IDENTITY_ADMIN_CLIENT_SECRET_ENCODED` | auth-server | must match profile's below |
| `IDENTITY_ADMIN_CLIENT_ID` / `IDENTITY_ADMIN_CLIENT_SECRET` | profile | **only** profile holds these |
| `IDENTITY_ADMIN_BASE_URI` | profile | its own variable, *not* the shared `MICROSERVICES_AUTH_SERVER_URI`: that one is the bare origin the gateway routes to, this needs the `/auth` context path |
| `MICROSERVICES_SHOP_URI`, `MICROSERVICES_PAYMENT_URI`, `MICROSERVICES_DELIVERY_URI` | profile | payment/delivery are read-only, for the sweep |
| `AUTH_SERVER_TOKEN_URI`, `INTERNAL_CLIENT_ID` | shop | shop is now an OAuth2 *client* too |

Set in `compose.yaml`, `k8s/base/config.yaml`, `k8s/base/profile.yaml` and
`k8s/base/shop.yaml`. Secrets follow the existing convention of defaulting in dev.

## 5. Orphan sweep

`GET /api/profiles/admin/orphans`, read-only, deletes nothing. The cascade spans four
databases and rides an at-least-once event, so a half-completed purge is possible and
otherwise leaves no trace at all.

No single service can see both halves, so the report is a join:

- `shop` reports order **owners**; `profile` diffs them against auth users → orders
  outliving their user.
- `payment` and `delivery` report the **order ids** they hold; `shop` answers which of
  those no longer exist → rows outliving their order.

## 6. Known gaps

1. **`user_file` is not deleted on hard delete.** A deleted user's uploaded files stay in
   `profiledb.user_file`, and their objects stay in storage, with the owning user gone.
   The plan's §8 Phase 4 named only the profile row and addresses; unlike `provider_event`
   there is no reasoning given, so this reads as an oversight. Closing it means also
   calling `StorageClient.delete` per file. **The most worth fixing.**
2. **`PROCESSING` payments count as deletable.** `PaymentStatus` has six values, not the
   three in the plan's table. `FAILED` and `CANCELED` are clearly fine to delete;
   `PROCESSING` means money may be in flight.
3. **`refund` rows are deleted** although the plan's §6 table omits them. A purgeable user
   cannot have a `REFUNDED` payment, but a refund row in a non-terminal state can exist and
   would otherwise be orphaned.
4. **`409` rather than `404`** for "no such user", per the plan §7. `404` is the more
   conventional choice.
5. **Paid users keep their PII indefinitely** — accepted deliberately (plan §4.1). There is
   no erasure path for anyone who has ever paid.
6. **A blocked user's existing access token keeps working** until it expires (~5 min).
   Accepted; there is no session-revocation machinery.

## 7. Test coverage

68 tests across the six phases. The ones that encode a security or correctness property
rather than a behaviour, and should not be allowed to regress quietly:

| Test | Property |
|---|---|
| `InternalUserControllerTest.rejectsATokenCarryingOnlyScopeInternal` | a `SCOPE_internal` token gets **403** on every identity endpoint — §3.1's whole point |
| `UserOrderRouteTest.anAdminUserTokenCannotReachTheInternalSurface` | `ROLE_ADMIN` is a user role; the internal surface is service-to-service |
| `UserOrderRouteTest.aPlainUserCannotListAnotherUsersOrders` | otherwise any customer reads any other customer's order history |
| `UserOrderServiceTest.aCancelledOrderWhosePaymentSucceededStillBlocksDeletion` | order status must not be consulted |
| `UserOrderServiceTest.aPaymentOutageIsAnErrorNotAnEmptyResult` | an outage is not permission to delete |
| `UserOrderServiceTest.purgeRefusesWhenTheUserHasAPaidOrder` | defence in depth against the check/delete race |
| `AdminUserServiceTest.aDisabledAdminDoesNotCountAsARemainingAdmin` | the lockout guard |
| `AdminUserServiceTest.profileDataSurvivesAFailedIdentityDelete` | failure ordering |
| `OrdersPurgedConsumerTest.isNotMistakenForAMalformedOrderPlaced` | the event branch must precede the `orderId` lookup, in both consumers |

**Note on the suites.** `auth-server`'s `AbstractTestcontainers` was changed to a singleton
container: `@Testcontainers` + `@Container` stops the container after *each* test class,
while Spring caches one context across every subclass, so adding a second test class broke
an existing one. `shop`'s base class still has the same latent shape.

Pre-existing failures unrelated to this work: every service's `*ApplicationTests` needs a
local Postgres, and `shop`'s `ShopRouteTest` (25) and `ShopIntegrationTest` (2) were already
red before this change.

## 8. Not done

**Phase 7 — duplicate-identity cleanup.** One human currently has two profile rows (one
keyed on username, one on their Google `sub`) with split order history. After this change
the admin page no longer *shows* the duplicate, but the rows and the split history remain.
Fixing it means deciding whether `profile` should key on username rather than the JWT
`sub`, and migrating rows including order reassignment. Sized separately.
