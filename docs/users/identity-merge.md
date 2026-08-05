# Merging duplicate identities

Status: **done, 2026-08-05.** Measured against `davide-hetzner-admin`, found one empty
orphan row, deleted it (§4.1). Both databases now return zero sub-shaped usernames. The
follow-ups in §6 and §9 are not done.

One human can hold two identities below auth-server: a `LOCAL` row keyed on their username
and a second one keyed on Google's opaque `sub`. `docs/users/blocking-users.md` §2.1 records
`iaka` in exactly that state, with orders under both.

This document is the plan to merge them. It is deliberately small, because **the bug that
created them is already fixed** (§1) — what remains is data, not behaviour.

## 1. The cause is already fixed

Commit `8dfe3b6` (2026-07-29), *"fix(auth-server): issue the local username as `sub` for
Google logins"*. `GoogleOidcUserService` now points `nameAttributeKey` at
`preferred_username` — the username of the row it just provisioned — so a federated login is
indistinguishable from a form login below auth-server. Its own comment names the damage:

> an opaque number leaked all the way downstream: profile keyed its rows on it, shop stamped
> it on orders, storage used it as a key prefix

**No duplicate has been created since.** Everything below is a one-off cleanup of rows
written before that date.

That is why this plan contains no new abstraction. Three things it explicitly does **not** do:

- **No `handle` column.** Rejected in `docs/users/messaging.md` D4 and still right — it
  would decouple addressing from identity without merging anything.
- **No duplicate-resolution logic in each service.** The opposite: the one heuristic that
  exists gets deleted (§6).
- **No merge engine.** Production evidence points to a single affected human. Building a
  general facility for one row is the expensive mistake here.

## 2. auth-server is already correct, and holds the mapping

`FederatedUserProvisioningService` links by verified email: a Google sign-in matching an
existing user keeps `provider = LOCAL` and only attaches `provider_id`. So auth-server has
**one** row for `iaka`, and it is the authority for the mapping:

```sql
SELECT provider_id AS google_sub, username AS canonical
FROM users WHERE provider_id IS NOT NULL;
```

That result *is* the merge table. Nothing else needs to infer it.

## 3. What is affected

| Service | Columns holding a username | Action |
|---|---|---|
| `profile` | `user_profile.username` (UNIQUE), `delivery_address.username`, `user_file.username`, `admin_action.actor` + `.target_user`, `user_message.sender_username` + `.recipient_username` | rewrite |
| `shop` | `customer_order.username` | rewrite |
| `storage` | object key prefixes (`avatars/<username>/…`, `user-files/<username>/…`) | **leave** |
| `payment`, `delivery`, `notification`, `auth-server` | none | unaffected |

**Why storage is left alone.** The username appears in the key, but `StorageService`
authorises `deleteObject` by *scope*, not by matching the key against the caller. Ownership
lives in `profile.user_file.username`, which this plan rewrites. A stale prefix inside an
opaque key is cosmetic, and rewriting it means an S3 copy-and-delete per object for no
behavioural gain. `user_file.object_key`, `user_file.url`, `user_profile.avatar_object_key`
and `uploaded_avatar_url` all keep pointing at the existing objects and keep working.

`user_message` is listed for completeness. Messaging shipped after the fix, so its rows
should already be canonical — unless someone messaged the sub-named profile while it was
still visible in recipient search.

## 4. Step 0 — measured, 2026-08-05

**Result: one affected row, and it is an empty stub with no children. The cleanup is a
single `DELETE`.** Everything in §5 turned out to be unnecessary for this case and is kept
only as reference should another arise.

`authdb` gives an unambiguous mapping — one sub-shaped profile row, one owner:

| profile row | auth-server user | provider |
|---|---|---|
| `105160438534693816021` | `mr.vrabie` | GOOGLE |

The two rows, side by side:

| field | `105160438534693816021` (id 12) | `mr.vrabie` (id 13) |
|---|---|---|
| created | 2026-07-29 06:35:11 | 2026-07-29 06:35:12 |
| email | *(null)* | `mr.vrabie@gmail.com` |
| first / last name | *(null)* | Adrian / Vrabie |
| display_name | *(null)* | *(null)* |
| uploaded avatar | *(null)* | *(null)* |
| google_picture_url | `…ACg8ocKyWfo6…` | **identical** |

**There is nothing to merge.** Every field on the stub is either null or the same value the
canonical row already holds, and the canonical row is strictly richer. The two were written
one second apart, either side of the `8dfe3b6` deploy — the stub is the last row the old
code wrote.

Child references to the sub, across both databases:

| table | rows |
|---|---|
| `delivery_address`, `user_file`, `user_message`, `admin_action` (profiledb) | **0** |
| `customer_order` (shopdb) | **0** |

So no `UPDATE` is needed anywhere, and §5.1's `COALESCE` merge and §5.2's collision would
both be no-ops.

**Everyone else is clean.** `itiganas` is in the linked state (`provider = LOCAL` with a
`provider_id`) but has no sub-shaped profile row. `steti.teslari` and `net.vrabie` are
`GOOGLE` accounts whose profile rows were created under proper usernames after the fix.

### 4.1 The whole cleanup

```sql
-- profiledb. Verified: no delivery_address, user_file, user_message or
-- admin_action row references this username, and no customer_order in shopdb.
DELETE FROM user_profile WHERE username = '105160438534693816021';
```

Nothing to back out of, because nothing else points at it. The Google picture URL it holds
is byte-identical to the one on `mr.vrabie`, so no avatar changes.

**Executed 2026-08-05** against `davide-hetzner-admin` — `DELETE 1`, after re-running the
reference checks immediately beforehand (all still zero). `user_profile` now holds 8 rows,
one per human plus `external-service`, and
`SELECT count(*) … WHERE username ~ '^[0-9]{10,}$'` returns 0 in both `profiledb` and
`shopdb`.

### 4.2 Note on the snapshot in `blocking-users.md`

That document's §2.1 lists `iaka`, `adria`, `davide` and sub `102919241495532217479`. **That
snapshot is stale** — none of those usernames exist in the current database, and
`102919241495532217479` is now `net.vrabie`'s `provider_id` with a properly-named profile
row. Treat §2.1 as the historical evidence that motivated this work, not as current state.

### 4.3 The query, for re-running

Worth re-running after any auth change, and it is what §6's orphans check should encode.

```sql
-- profiledb: sub-shaped owners, per table
SELECT 'user_profile' t, username, count(*) FROM user_profile
  WHERE username ~ '^[0-9]{10,}$' GROUP BY username
UNION ALL SELECT 'delivery_address', username, count(*) FROM delivery_address
  WHERE username ~ '^[0-9]{10,}$' GROUP BY username
UNION ALL SELECT 'user_file', username, count(*) FROM user_file
  WHERE username ~ '^[0-9]{10,}$' GROUP BY username
UNION ALL SELECT 'user_message', sender_username, count(*) FROM user_message
  WHERE sender_username ~ '^[0-9]{10,}$' GROUP BY sender_username;

-- shopdb
SELECT username, count(*), sum(total) FROM customer_order
  WHERE username ~ '^[0-9]{10,}$' GROUP BY username;
```

Cross-check every value found against §2's mapping. **A sub with no matching `provider_id`
is a stop-the-line finding**, not a row to guess at: it means an identity whose owner cannot
be established, and merging it into the wrong account is worse than leaving it.

## 5. Step 1 — the merge (not needed today; reference only)

**§4 measured zero child rows, so none of this applies to the current cleanup.** It is kept
because the procedure is the non-obvious part, and a future duplicate with real data behind
it would need exactly this.

Per database, inside one transaction, with `:sub` and `:canonical` from §2. Order matters:
children first, `user_profile` last.

```sql
-- profiledb
UPDATE delivery_address SET username = :canonical WHERE username = :sub;
UPDATE user_message SET sender_username = :canonical WHERE sender_username = :sub;
UPDATE user_message SET recipient_username = :canonical WHERE recipient_username = :sub;
UPDATE admin_action SET actor = :canonical WHERE actor = :sub;
UPDATE admin_action SET target_user = :canonical WHERE target_user = :sub;
UPDATE user_file SET username = :canonical WHERE username = :sub;   -- see §5.2
-- shopdb
UPDATE customer_order SET username = :canonical WHERE username = :sub;
```

Each is idempotent: re-running matches nothing.

### 5.1 `user_profile` — merge, then delete

A rename collides with `UNIQUE(username)` whenever the canonical row already exists, which is
the common case. So fill only the fields the canonical row is missing — the same
*never overwrite what is already there* rule `ProfileService.provisionFromRegistration`
already uses — then delete the sub row.

```sql
UPDATE user_profile c SET
    email          = COALESCE(c.email, s.email),
    first_name     = COALESCE(c.first_name, s.first_name),
    last_name      = COALESCE(c.last_name, s.last_name),
    display_name   = COALESCE(c.display_name, s.display_name),
    google_picture_url = COALESCE(c.google_picture_url, s.google_picture_url),
    avatar_object_key  = COALESCE(c.avatar_object_key, s.avatar_object_key),
    uploaded_avatar_url = COALESCE(c.uploaded_avatar_url, s.uploaded_avatar_url),
    updated_at     = now()
FROM user_profile s
WHERE c.username = :canonical AND s.username = :sub;

-- Only if the canonical row's own source is NONE: the sub row's avatar is
-- better than nothing, but must not override a choice already made.
UPDATE user_profile SET avatar_source = 'GOOGLE'
WHERE username = :canonical AND avatar_source = 'NONE' AND google_picture_url IS NOT NULL;

DELETE FROM user_profile WHERE username = :sub;
```

**If no canonical row exists** — a Google user provisioned before the fix who never
form-registered — none of the above applies and a plain
`UPDATE user_profile SET username = :canonical WHERE username = :sub` is the whole job.

### 5.2 The one collision to expect

`uk_user_file_username_content_hash` is `UNIQUE(username, content_hash)`. If both identities
uploaded the same file after `004` added the column, the `user_file` update violates it.
Resolve before the update by dropping the newer duplicate, keeping the older row:

```sql
DELETE FROM user_file dup USING user_file keep
WHERE dup.username = :sub AND keep.username = :canonical
  AND dup.content_hash IS NOT NULL AND dup.content_hash = keep.content_hash
  AND dup.created_at >= keep.created_at;
```

The S3 object behind the dropped row is then unreferenced. `GET /api/profiles/admin/orphans`
already reports exactly that, so it will surface there rather than vanishing silently.

## 6. Step 2 — after

- **Delete `MessageService.resolveByEmail`'s tie-break.** It exists only to paper over this
  bug, and its comment already says to remove rather than preserve it when the bug is fixed.
  Its `log.warn` should be gone from the logs after the merge; if it still fires, the merge
  missed something.
- **Add a sub-shaped-username check to `GET /api/profiles/admin/orphans`.** That endpoint
  already exists for this genre of problem. A few lines make recurrence visible instead of
  silent — cheap insurance against a future login path reintroducing it.
- **`external-service` is a separate cleanup.** It is a client-credentials account that got a
  profile row by calling a `/me` endpoint (`blocking-users.md` §2.1). Not a duplicate
  identity and out of scope here, but worth deleting in the same maintenance window.

## 7. Risks

Most of the risk this document was written to manage turned out not to exist — §4 found no
data to move. What remains:

- **Re-check immediately before deleting.** The row is empty *now*; if `mr.vrabie` signs in
  with Google between measurement and execution, nothing changes (the fixed code writes
  `mr.vrabie`), but re-running the §4 queries costs seconds and removes the doubt.
- **Do it before balances exist.** `docs/finance/finance.md` §6.2: once a user holds money
  this stops being a rename and becomes a money migration. Right now it is one `DELETE`.
- **No downtime, no dump strictly needed** for a row with no references — but take one
  anyway if it costs nothing, since a `DELETE` has no inverse.

Had §5 been needed, a dump of both databases would have been mandatory, and order-ownership
`UPDATE`s would have wanted an `admin_action` record since they are money-adjacent.

## 8. Verification

1. The §4 queries return nothing in either database.
2. `mr.vrabie` signs in with Google → lands on the `mr.vrabie` profile, avatar unchanged.
3. Their one order (CHF 24.00, 2026-08-02) is still theirs.
4. `GET /api/profiles` shows one row per human and no numeric usernames.
5. `MessageService`'s duplicate-email warning does not appear in profile's logs — it should
   never have fired here, since the stub had no email to collide on.

## 9. What this leaves behind

Two items surfaced by the measurement, neither a duplicate identity:

- **`external-service` still holds a profile row**, created 2026-07-22 by a
  client-credentials token hitting a `/me` endpoint. Harmless, but it appears in admin user
  lists as though it were a person. Delete it in the same window.
- **`itiganas` has no email in its profile row** while auth-server has
  `inna.tiganas@gmail.com`. Not in scope here — it is the pre-`UserRegisteredConsumer` gap
  described in `docs/users/user-profile.md` — but it means that user cannot be addressed by
  email in messaging (`docs/users/messaging.md` §3.1 rule 3).
