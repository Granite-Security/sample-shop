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
        String subject,
        String body,
        String preview,
        boolean read,
        Instant readAt,
        boolean outgoing,
        Instant createdAt
) {}
