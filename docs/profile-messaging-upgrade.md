# Linking a message's sender to their public profile

Status: **implemented, pending verification in k8s.** Steps 1-5 are in the code; step 6 is
deliberately not done. Nothing has been exercised against a live cluster yet — see
Verification.

In the inbox, the sender of a message is a name and an avatar and nothing else. If that
user has published a profile (`docs/profile/public-profile.md`), clicking their name should
open `/users/<handle>`. If they have not, the name stays plain text — no dead link, no
"this profile isn't available" page reached by clicking something that looked clickable.

## The one real decision

**D1 — the server tells the client whether there is a profile to link to; the client never
guesses.** `MessageResponse` gains a `counterpartyHandle`, non-null only when that user's
profile is *published*. The UI renders a link when it is present and plain text when it is
not.

The alternative — link to `/users/<counterpartyUsername>` and let the page 404 — does not
work: the public route is keyed by `handle`, not `username` (D1/D3 in
`docs/profile/public-profile.md`), and the two are different strings. Resolving the handle
client-side would mean one extra request per row in a list of 20, and there is no
username→handle endpoint that an ordinary user may call.

**D2 — a published handle is not new information.** `counterpartyHandle` is only ever set
for a profile that already answers to an anonymous `GET /api/profiles/public/<handle>`.
An unpublished profile's handle is *reserved but private* (D2 there), so it must not appear
here — that is why the mapping reads `publicProfile` and not just `handle`.

**D3 — three cases fall out for free, and none needs a special branch:**

| Counterparty | Profile row | Result |
|---|---|---|
| Ordinary user, profile published | yes, `public_profile = true` | link |
| Ordinary user, not published | yes, `public_profile = false` | plain text |
| `system` (order notices), or a contact-form visitor (§11), or a deleted user | none | plain text |

`withCounterparty` already resolves `null` for the last row, and admin unpublish
(`POST /api/profiles/admin/users/{username}/unpublish`) already sets `public_profile = false`,
so a link disappears the moment a profile is pulled — no cache to invalidate.

## No change needed

Database (no new column — `handle` and `public_profile` are already there), gateway,
routes (`/api/profiles/me/messages*` and `/users/:handle` both exist), `ProfileSec`,
nginx, Kafka.

---

## Step 1 — `profile`: carry the handle on the DTO

`profile/src/main/java/org/granitesecurity/profile/dto/MessageResponse.java`

Add one component after `counterpartyAvatarUrl`, and a paragraph saying what null means:

```java
/**
 * ...
 *
 * <p>{@code counterpartyHandle} is the other party's public-profile handle, and is set
 * <em>only</em> when that profile is published. Null means there is nothing to link to:
 * no profile row at all (the {@code system} sender, a contact-form visitor, a deleted
 * user), or a profile whose owner has not published it. A handle that exists but is
 * unpublished is private (docs/profile/public-profile.md D2) and must never appear here.
 */
public record MessageResponse(
        Long id,
        String senderUsername,
        String senderEmail,
        String recipientUsername,
        String counterpartyUsername,
        String counterpartyDisplayName,
        String counterpartyAvatarUrl,
        String counterpartyHandle,      // <-- new
        String subject,
        String body,
        String preview,
        boolean read,
        Instant readAt,
        boolean outgoing,
        Instant createdAt
) {}
```

`MessageService.toResponse` is the only construction site in the service, so the record's
component order can change without hunting for callers.

## Step 2 — `profile`: populate it

`profile/src/main/java/org/granitesecurity/profile/service/MessageService.java`

`withCounterparty` already loads the counterparty's `UserProfile` for the display name and
avatar — the handle rides along on the row that is already being read. No extra query, no
extra round trip per message.

```java
    private MessageResponse toResponse(UserMessage message, String viewer, UserProfile counterparty) {
        ...
        return new MessageResponse(
                message.getId(),
                message.getSenderUsername(),
                message.getSenderEmail(),
                message.getRecipientUsername(),
                counterpartyUsername,
                counterparty != null ? displayNameOf(counterparty) : displayNameOfGuest(message, counterpartyUsername),
                counterparty != null ? ProfileService.effectiveAvatarUrl(counterparty) : null,
                publishedHandleOf(counterparty),
                message.getSubject(),
                ...
    }

    /**
     * The handle only when the profile is published. Reading {@code getHandle()} alone
     * would hand out the handle of an unpublished profile, which is reserved-but-private
     * (docs/profile/public-profile.md D2) — and the link it produced would 404 anyway.
     */
    private static String publishedHandleOf(UserProfile profile) {
        if (profile == null || !profile.isPublicProfile()) {
            return null;
        }
        String handle = profile.getHandle();
        return handle == null || handle.isBlank() ? null : handle;
    }
```

## Step 3 — `ui-shop`: the type

`ui-shop/src/types.ts`, inside `interface MessageResponse`, after `counterpartyAvatarUrl`:

```ts
  // The counterparty's public-profile handle, or null when they have no published
  // profile — which is also the case for the `system` sender and for contact-form
  // visitors. Null means render the name as text, not as a link: there is no
  // /users/<handle> page to send anyone to.
  counterpartyHandle: string | null;
```

## Step 4 — `ui-shop`: link the name in the message detail

`ui-shop/src/pages/Messages.tsx`

Import `Link` from `react-router` (the same import the rest of the app uses) and change the
`MessageDetail` header block. `guest` stays exactly as it is — it answers a different
question (can I reply in-app?) and a signed-in sender with no published profile is not a
guest.

```tsx
function MessageDetail({ message, onClose, onDelete, onReply }: { ... }) {
  const guest = !message.counterpartyUsername;
  // Separate from `guest`: a real user with an unpublished profile has an inbox to
  // reply into but no page to visit.
  const profileUrl = message.counterpartyHandle
    ? `/users/${encodeURIComponent(message.counterpartyHandle)}`
    : null;

  return (
    <div className="message-panel">
      <div className="message-detail-head">
        {profileUrl
          ? <Link to={profileUrl}><Avatar src={message.counterpartyAvatarUrl} name={message.counterpartyDisplayName} size={40} /></Link>
          : <Avatar src={message.counterpartyAvatarUrl} name={message.counterpartyDisplayName} size={40} />}
        <div className="message-detail-who">
          <strong>
            {message.outgoing ? 'To: ' : 'From: '}
            {profileUrl
              ? <Link to={profileUrl} className="message-profile-link">{message.counterpartyDisplayName}</Link>
              : message.counterpartyDisplayName}
          </strong>
          <span className="message-time">
            {guest
              ? `${message.senderEmail ?? 'no address given'} · via the contact form`
              : profileUrl
                ? <Link to={profileUrl} className="message-profile-link">@{message.counterpartyUsername}</Link>
                : `@${message.counterpartyUsername}`}
            {' · '}{new Date(message.createdAt).toLocaleString()}
          </span>
        </div>
        ...
```

`counterpartyDisplayName` is still rendered as a JSX child, so React still escapes it —
putting it inside a `<Link>` changes nothing about that. The handle goes through
`encodeURIComponent` even though `validateHandle` restricts the character set server-side;
the UI does not get to assume the server's regex.

## Step 5 — `ui-shop`: the list rows (deliberately *not* links) — option 1 taken

Each row in the inbox list is a `<button>` that opens the message. Do **not** nest an
anchor inside it — nested interactive elements are invalid HTML, and the click target
becomes ambiguous: clicking a sender's avatar would sometimes open the message and
sometimes navigate away.

Two acceptable options, in order of preference:

1. **Leave the list alone** (recommended). The row opens the message; the message header
   links to the profile. One click target per row, one extra click to reach a profile.
2. If the profile must be reachable from the list, convert the row from `<button>` to
   `<div className="message-row" role="button" tabIndex={0}>` with `onClick` and an
   `onKeyDown` for Enter/Space, and put the `<Link>` inside with
   `onClick={e => e.stopPropagation()}`. This is more markup and more ways to get keyboard
   behaviour wrong; it buys one saved click.

## Step 6 (optional) — the same link elsewhere

The same `publishedHandleOf` mapping makes two other places clickable, and neither is
required for this feature:

- `RecipientResponse` (the compose picker) — a handle there would let you check who you are
  about to write to. Note it widens the *search* result surface, which §5 of
  `docs/users/messaging.md` deliberately keeps narrow; the handle is public, but think
  before adding it.
- `UserProfileView` / `UsersManagement` (admin) — admins already see everything about a
  user through `/api/profiles/admin/users`, so a link there is convenience only.

---

## How we verify

Per the usual rule for this repo: manually, against the deployed cluster — there is no
Testcontainers and no live-DB test to add here.

```bash
# 1. Two accounts. As `user`, publish a profile:
curl -sS -X PUT https://granite-security.org/api/profiles/me/handle \
  -H "Authorization: Bearer $USER_TOKEN" -H 'Content-Type: application/json' \
  -d '{"handle":"ada"}'
curl -sS -X PUT https://granite-security.org/api/profiles/me/visibility \
  -H "Authorization: Bearer $USER_TOKEN" -H 'Content-Type: application/json' \
  -d '{"publicProfile":true}'

# 2. As `user`, send a message to `manager`.
# 3. As `manager`, read the inbox — expect counterpartyHandle "ada":
curl -sS 'https://granite-security.org/api/profiles/me/messages?box=inbox' \
  -H "Authorization: Bearer $MANAGER_TOKEN" | jq '.[] | {counterpartyUsername, counterpartyHandle}'
```

Then in the browser as `manager`:

- The message from `user` shows a linked name; clicking it lands on `/users/ada`.
- Unpublish `user` (`PUT /api/profiles/me/visibility {"publicProfile":false}`), reload the
  inbox: the same message now shows plain text, and `counterpartyHandle` is null — the
  handle must **not** appear in the JSON.
- The order-notice message from `system` shows plain text (no profile row at all).
- A contact-form message from an anonymous visitor still shows
  "Name · via the contact form" with the *Reply by email* button and no link.
- Sign out and open `/users/ada` directly — still readable, unchanged by any of this.
