# User-to-user messaging (inbox)

Status: **Phase 1 shipped** (#77), plus Reply and the unread bell from Phase 2 (#78, #79).
Conversation grouping, pagination and all of Phase 3 not started.

Goal: any signed-in user can send a message to another user by username or email, and read
what they were sent from an inbox. `manager` sends to `net.vrabie`; `net.vrabie` sees it
under *My Account → Messages*.

This document is the high-level design and the rationale. It answers the question that
prompted it — **database or Kafka?** — in §1, and records the decisions in §8.

## 1. The short answer: database, and it isn't close

**The message store is a table in `profiledb`. Messaging uses no Kafka at all** — not on the
send path, not for notifications, nowhere. A message is a row one user writes and another
user queries.

Why the database wins here, when the order→payment→delivery flow rightly uses Kafka:

| | Order lifecycle (Kafka is right) | Sending a message (DB is right) |
|---|---|---|
| Who acts | One service's write must cause work in **three other services** | One user writes a row another user reads |
| Timing | Payment can settle seconds or minutes later | Sender must see "sent" **now**, recipient on next refresh |
| Consistency need | Eventual, across service boundaries | **Read-your-writes** within one service |
| Failure meaning | Retry later is correct | "Send failed" must reach the sender as a 4xx/5xx, immediately |
| Ownership | Crosses `shop`/`payment`/`delivery` | Entirely inside `profile` |

Routing a message through Kafka would mean: the `POST` returns 202 with no message id, the
sender's own Sent folder is eventually consistent with their own click, "recipient does not
exist" becomes a dead-letter instead of a `404`, and we still need the same table at the end
of the pipeline — plus an outbox table and a relay to write it transactionally, because
`insert into user_message` and `send to Kafka` are not one atomic operation. It is strictly
more machinery for a strictly worse user experience. The event bus is for facts other
services care about; the inbox is a query over rows.

Keep it that way. If a future phase wants an email nudge on a new message, that is a decision
to reopen deliberately with the outbox cost priced in — not a small addition to slip into a
service call.

## 2. Where it lives: `profile`

`profile` already owns usernames, display names, avatars and the admin block list, and it is
already the service behind every *My Account* page. Messaging is a `/api/profiles/me/**`
feature.

This has a concrete payoff: **no gateway change and no security change.**

- `RouterConfig` already routes `/api/profiles/**` to profile with token relay.
- `ProfileSec` already has `.pathMatchers("/api/profiles/me", "/api/profiles/me/**").authenticated()`,
  ordered ahead of the `{username}` admin wildcard.

New endpoints hung under `/api/profiles/me/messages` are authenticated the day they are
written, with no chance of the ordering mistakes that rule already documents.

### Why not `notification`

`notification` is the tempting alternative — the archived plan
(`docs/archive/notification/notification-microservice.md` §Phase 6) reserves an in-app inbox
there, and it is already a Kafka consumer with a `notification_log`.

Keep them separate. They are different products that happen to share the word "inbox":

- `notification` sends **system→user** copy the system authored, from templates it owns, and
  its whole design premise is *"producers publish domain facts, never rendered text."*
  A user-authored message body **is** rendered text. Putting it on `identity.events` or into
  `notification_log` inverts the one rule that service is built around.
- `notification` deliberately has no inbound API and no `SecurityWebFilterChain`. Messaging
  needs a `POST` from a browser on day one.
- The recipient is identified by **username**, and `profile` is the only service that can
  resolve one (§3).

When Phase 6 of the notification plan lands, the two inboxes can share a UI shell — a
"system" tab and a "people" tab — while remaining separate stores. That is a frontend
concern, not a reason to merge the backends.

## 3. Addressing: username or email

`ProfileHandler` keys every profile row on `jwt.getSubject()`, which is the login username
for form users and the **Google `sub`** for federated ones. `docs/users/blocking-users.md`
§2.1 shows the result in production:

```
profiles: admin adria davide iaka itiganas manager user
          + 102919241495532217479   ← a Google sub, in the username column
          + external-service        ← a client-credentials service account
```

A recipient may be given as **either a username or an email address** — `net.vrabie` and
`net.vrabie@example.com` both resolve to the same person. One input field, one resolver: if
the string contains an `@` it is resolved as an email, otherwise as a username. Four
consequences the design must face, not discover later:

1. **Google-provisioned users are not addressable by a human handle.** Nobody will type
   `102919241495532217479`. In Phase 1 they can *send* and *receive* — the sender addresses
   them by picking them from search results (§5), which returns display name and resolves to
   the stored username behind the scenes. They are simply not typeable.
2. **One human can be two rows** (`iaka` has both a `LOCAL` row and a Google-sub row). A
   message to one is invisible from the other. This is the pre-existing duplicate-identity
   bug; messaging surfaces it, and does not fix it. Flagged as a known gap, not a blocker.
3. **Service accounts are in the list.** `external-service` must never appear in recipient
   search. Filter it the same way the admin user list has to (§5).
4. **`email` is not unique, and email resolution is therefore ambiguous.** `user_profile.email`
   carries no `UNIQUE` constraint, and §2.1 records `iaka` as *two rows with the same email* —
   the `LOCAL` row and the Google-sub row. It is also **nullable**, and was null for every row
   created before `UserRegisteredConsumer` existed. So an email lookup can return zero, one, or
   two rows. See §3.1.

**No `handle` column is needed.** `net.vrabie` is a real username, stored as-is, so the dotted
form you want to type already works. A separate user-chosen handle decoupled from the JWT
subject would only buy typeability for the Google-sub rows, and email addressing now covers
that case for anyone whose email we hold. Phase 1 addresses by the `username` column exactly
as stored, plus email as an alias.

### 3.1 Resolving an email to one user

Ambiguity here is not hypothetical — it is `iaka` today. The resolver:

1. Matches `LOWER(email) = LOWER(?)` — emails are case-insensitive in practice, and the column
   stores whatever the user typed at registration.
2. **One row → send.**
3. **Zero rows → 404**, same as an unknown username. Note this includes users whose email we
   simply never captured; they remain reachable by username.
4. **Two or more rows → prefer the row whose `username` is not a bare numeric Google `sub`.**
   That is the human-typeable identity, the one with a password, and the one whose inbox the
   person will actually open. If every candidate is a sub-shaped row, take the oldest
   `created_at` and log a warning naming both usernames.

Rule 4 is a heuristic covering a bug, and it should be written as such — one comment pointing
at `blocking-users.md` §2.1, so whoever finally merges duplicate identities knows to delete it
rather than preserve it. It is not a reason to block this feature: the alternative, a 409
asking a user to choose between two identities that are visibly the same person, is worse.

Recipient **search** (§5) sidesteps all of this — it returns a concrete `username`, and the
compose form sends that. Typed email input is the path that needs the resolver.

## 4. Data model

One table, one changeset: `profile/src/main/resources/db/changelog/007-user-messaging.sql`,
added to `db.changelog-master.yaml` after `006-user-avatar.sql`.

```sql
--changeset moldo:007-create-user-message
CREATE TABLE user_message (
    id                  BIGSERIAL    PRIMARY KEY,
    sender_username     VARCHAR(64)  NOT NULL,
    recipient_username  VARCHAR(64)  NOT NULL,
    subject             VARCHAR(200),                -- optional; NULL, never ''

    body                TEXT         NOT NULL,
    read_at             TIMESTAMPTZ,
    sender_deleted      BOOLEAN      NOT NULL DEFAULT FALSE,
    recipient_deleted   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_user_message_inbox ON user_message(recipient_username, created_at DESC);
CREATE INDEX idx_user_message_sent  ON user_message(sender_username, created_at DESC);
```

Notes on the shape:

- **Usernames, not foreign keys to `user_profile.id`.** Every other table in this schema
  (`delivery_address`, `admin_action`, `user_file`) keys on the username string. Matching that
  keeps the deletion story consistent: when a user is deleted, their messages are purged by
  username sweep like everything else, and a message from a since-deleted sender still
  renders their name instead of exploding on a broken join.
- **No `is_read` boolean — a nullable `read_at`.** "When" is free and answers support
  questions a boolean cannot.
- **Per-side delete flags, no row deletion.** Deleting from your inbox must not delete it
  from the other person's Sent folder. A row is physically removed only when both sides have
  deleted it, or by the user-deletion sweep.
- **No `thread_id` in Phase 1.** A conversation is derived: the unordered pair
  `{sender, recipient}`. Phase 2 groups on `LEAST(a,b), GREATEST(a,b)` for a threaded view;
  adding a real thread table later is a migration over data that already has the pair.
- **`body` is `TEXT`, stored raw.** Escape at render, never at write (§7.1).

Follow the existing conventions: `@Table("user_message")` domain class with Lombok
`@Getter/@Setter` and `@Column` for snake_case, a `UserMessageRepository extends
ReactiveCrudRepository`, R2DBC only, no blocking calls.

## 5. API

All under the already-authenticated `/api/profiles/me/**` prefix. Functional routing in
`ProfileRoute`, handler + service, matching `AddressHandler`/`AddressService`.

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/profiles/me/messages` | Send. Body `{ "to": "net.vrabie", "subject": "...", "body": "..." }` — `to` is a username **or** an email (§3.1); `subject` is optional |
| `GET` | `/api/profiles/me/messages?box=inbox\|sent&page=0&size=20` | List, newest first, paged |
| `GET` | `/api/profiles/me/messages/unread-count` | `{ "count": 3 }` for the header badge |
| `GET` | `/api/profiles/me/messages/{id}` | Read one; marks read if you are the recipient |
| `POST` | `/api/profiles/me/messages/{id}/read` | Explicit mark-read (list-view checkbox) |
| `DELETE` | `/api/profiles/me/messages/{id}` | Sets your side's delete flag |
| `GET` | `/api/profiles/me/messages/recipients?q=net` | Recipient search over username, display name and email — returns username + display name + avatar |

Rules that are the whole security model:

- **The sender is `jwt.getSubject()`, always.** There is no `from` field in any request body.
  Same reason `updateMe` takes the username from the token and not the payload.
- **Every read is scoped to the caller.** `GET /messages/{id}` must be
  `WHERE id = ? AND (sender_username = ? OR recipient_username = ?)`, in the query, not
  fetched-then-checked. A bare `findById` here is an IDOR that walks the whole table.
- **Only the recipient can mark read.** A sender hitting `/read` is a no-op, not a 403 —
  they legitimately own the row.
- `POST` returns **404** for an unknown recipient, **400** for an empty body or one over the
  limit, **403** for a blocked participant (§6), and **201** with the created message
  otherwise. A missing or blank `subject` is **not** an error (§5.1).

`recipients?q=` prefix-matches `username`, `display_name` **and `email`**, caps at ~10 rows,
excludes the caller, excludes admin-blocked users, and excludes service accounts (§3).

**It searches email but never returns it.** Matching on a column and disclosing it are
different things: someone who already knows a colleague's address can find them, but nobody
can type `a` and harvest the address book. The response carries only what a user may already
see of another user — display name, username, avatar. This is the single rule most likely to
be broken by "just add email to the DTO so the UI can show it"; the UI does not need it.

For the same reason `q` is prefix-matched with a **minimum length of 2**, so a one-character
query cannot enumerate the user table.

### 5.1 Optional subject

`subject` is nullable in the schema and optional in the API. Consequences to get right rather
than discover in the UI:

- A blank string is stored as `NULL`, not `''` — one representation of "absent", so list
  rendering has one case to handle.
- The inbox list falls back to a truncated first line of the body (~60 chars) shown in a muted
  style, the way a mail client does. It must not print "(no subject)" as literal text, and it
  must not leave the row blank and unclickable.
- Reply prefills `Re: <subject>` only when there is one; replying to a subjectless message
  stays subjectless rather than becoming `Re: `.
- The truncated-body preview is escaped like any other body text (§7.1) — it is the same
  hostile string, just shorter.

## 6. Interaction with blocking

`docs/users/blocking-users.md` gives admins a block that disables the account in auth-server.
A blocked user cannot obtain a token, so they cannot send. But they can still be *addressed*,
and messages to them pile up in an inbox nobody will read.

Phase 1: **filter admin-blocked users out of recipient search, and reject a `POST` addressed
to one with 403.** Profile learns block state from `IdentityAdminClient`, which the admin
list already uses. If that call fails, fail open on search (blocked users may appear) and
closed on send — a stale search result is cosmetic, a delivered message to a disabled account
is not.

**User-to-user blocking ("I don't want to hear from `manager`") is not in this design.** It
is a separate `user_message_block` table and a separate document. Noting it here so the
recipient-resolution code path is written with one obvious place to add a second check.

## 7. Things that will bite

### 7.1 The body is hostile text

It is user-authored and arrives at two renderers:

- **React** escapes by default. Never `dangerouslySetInnerHTML` on a message body. If
  markdown is ever wanted, it is a sanitizer decision, not a rendering shortcut.
- **Logs.** Do not log message bodies. This is private correspondence, and the operator
  reading pod logs is not a party to it. Log ids and usernames, never content.

Validate at the edge: subject ≤ 200 chars, body ≤ 4000, both trimmed, body non-empty after
trimming.

### 7.2 Self-messaging and duplicate sends

Reject `to == self` with 400 — it is always a mistake and it doubles every conversation
query. There is no idempotency key: a double-click sends twice, which is what email does and
what users expect. Disable the button while the request is in flight.

### 7.3 Abuse

Nothing here rate-limits anything. One user can `POST` in a loop and fill another's inbox.
Phase 3 adds a per-sender cap (e.g. 50 messages/hour, counted with a query over `created_at`
— no new infrastructure). Acceptable to ship without, given a closed user set; not
acceptable if registration is ever opened.

## 8. Decisions

| # | Question | Decision |
|---|---|---|
| D1 | Kafka or DB? | **DB.** Synchronous write to `profiledb`, sender gets 201 with the row. Kafka is not on the send path (§1). |
| D2 | Which service? | **`profile`.** It owns usernames and *My Account*, and `/api/profiles/me/**` is already routed and already authenticated (§2). |
| D3 | Merge with `notification`'s planned inbox? | **No.** System notifications are templated facts; messages are user-authored text. Share the UI shell later, never the store (§2). |
| D4 | Address by what? | **Username or email**, one input field, `@` decides which. `net.vrabie` is a real username so no `handle` column is needed (§3). |
| D4a | Email matches two rows? | **Prefer the non-Google-`sub` username**, oldest `created_at` as tiebreak, log a warning. A heuristic covering the duplicate-identity bug, commented as such and deleted when that is fixed (§3.1). |
| D4b | Optional subject | **Yes, nullable.** Blank stored as `NULL`; the list falls back to a truncated body preview, not the literal string "(no subject)" (§5.1). |
| D5 | One table or thread + message? | **One table.** Conversations are derived from the participant pair; a thread table can come later over data that already has it (§4). |
| D6 | Delete semantics | **Per-side flags.** Deleting from your inbox never touches the other side's Sent folder (§4). |
| D7 | Real-time delivery? | **No websockets, no SSE.** Poll `unread-count` on an interval, as `ui-shop` already polls for the payment `clientSecret` (§9 Phase 2). |
| D8 | Blocked users | Excluded from search, 403 on send. **User-to-user blocking is out of scope** (§6). |
| D9 | Email on new message | **No.** Out of scope entirely. It is the only thing that would pull Kafka in, and it would need an outbox to be correct (§1). |

## 9. Phases

**Phase 1 — send and read.** Liquibase `007`, `UserMessage` domain + repository,
`MessageService`, `MessageHandler`, seven routes in `ProfileRoute`. No gateway change, no
`ProfileSec` change. `Messages.tsx` under `AccountLayout` with an Inbox/Sent toggle, a
compose form with recipient search, and a detail view. Ship it here — this alone is the
feature as asked.

**Phase 2 — usability.** *Reply and the unread badge are shipped; grouping and pagination are
not.* Reply prefills the recipient and an `Re:` subject. The badge is a bell in `Header.tsx`
fed by `MessagesContext`, which polls `unread-count` every 30s — only while signed in, and
only while the tab is visible, refreshing immediately on `visibilitychange`. Opening a message
decrements it locally rather than waiting out the interval, which is the difference between
the bell feeling live and feeling broken. Still to do: conversation grouping by participant
pair, and pagination in the list.

**Phase 3 — abuse and hygiene.** Rate limiting (§7.3), user-to-user blocking (§6), inclusion
in the user-deletion purge sweep alongside addresses and files.

There is no email-notification phase. See D9.

## 10. How we verify

Manual, against the real cluster — the repository tests here need Testcontainers, and none of
what matters below is covered by mocks.

```bash
kubectl config current-context                          # always, before anything
kubectl -n granite rollout restart deployment profile   # :latest does not auto-pull
kubectl -n granite logs -f deploy/profile | grep -i liquibase   # 007 applied?

# as manager, with a real token from the browser session:
curl -s -X POST https://<host>/api/profiles/me/messages \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"to":"net.vrabie","subject":"hi","body":"first message"}' -i
```

What to actually check, in order:

1. `manager` → `net.vrabie` returns **201**; the row exists with `read_at` null.
2. `net.vrabie`'s `GET ?box=inbox` shows it; `manager`'s `?box=sent` shows it; **`manager`'s
   inbox does not.**
3. `GET /messages/{id}` **as a third user returns 404**, not the message. This is the one
   test that matters most — run it before believing anything else.
4. Opening it as the recipient sets `read_at`; `unread-count` drops.
5. `POST` to a nonexistent username returns 404, not 500.
6. Recipient search for `q=ext` does not return `external-service`.
7. A body containing `<script>alert(1)</script>` renders as visible text in the UI, not as a
   script.
8. **`"to"` given as `net.vrabie`'s email reaches the same inbox as `"to":"net.vrabie"`** —
   the two sends land in one conversation, not two.
9. **Send to `iaka`'s email.** This is the ambiguous case (§3.1). Confirm it resolves to the
   `LOCAL` row and not the Google-`sub` row, and that the warning is logged. Check with
   `kubectl -n granite exec deploy/postgres-profile -- psql -U profile -d profiledb -c
   "SELECT username, email FROM user_profile WHERE email IS NOT NULL"` first, to see which
   duplicates still exist.
10. A recipient-search response body contains **no `email` field** for any result, even when
    the query that matched was an email.
11. A message sent with no subject appears in the inbox list with a body preview, is
    clickable, and replying to it does not produce a subject of `Re: `.
