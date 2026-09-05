package org.granitesecurity.profile.dto;

import java.time.Instant;

/**
 * One message as a client sees it.
 *
 * <p>{@code counterpartyDisplayName} and {@code counterpartyAvatarUrl} describe the
 * <em>other</em> party — the sender in the inbox, the recipient in Sent — already
 * resolved, so a list of 20 messages does not become 20 profile lookups in the UI.
 *
 * <p>{@code preview} is the truncated body the list renders when there is no subject
 * (docs/users/messaging.md §5.1). It is the same untrusted text as {@code body}, just
 * shorter, and is escaped by React like anything else.
 *
 * <p>{@code counterpartyHandle} is the other party's public-profile handle, and is set
 * <em>only</em> when that profile is published. Null means there is nothing to link to:
 * no profile row at all (the {@code system} sender, a contact-form visitor, a counterparty
 * deleted since), or a profile whose owner has not published it. A handle that exists but
 * is unpublished is private (docs/profile/public-profile.md D2) and must never appear here.
 *
 * <p>{@code senderUsername} and {@code counterpartyUsername} are null for a contact-form
 * message sent by someone who was not signed in (§11). Clients must treat a null
 * counterparty as "there is no profile to link to and no inbox to reply into" — that is
 * what {@code senderEmail} is for, and it is the whole reason the form asks for one.
 */
public record MessageResponse(
        Long id,
        String senderUsername,
        String senderEmail,
        String recipientUsername,
        String counterpartyUsername,
        String counterpartyDisplayName,
        String counterpartyAvatarUrl,
        String counterpartyHandle,
        String subject,
        String body,
        String preview,
        boolean read,
        Instant readAt,
        boolean outgoing,
        Instant createdAt
) {}
