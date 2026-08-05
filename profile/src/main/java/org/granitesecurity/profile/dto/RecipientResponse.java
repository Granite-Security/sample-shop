package org.granitesecurity.profile.dto;

/**
 * A candidate recipient.
 *
 * <p><strong>There is no email field, and there must not be one.</strong> The search
 * behind this matches on email so you can find someone by an address you already know,
 * but returning it would turn the picker into an address-book harvester
 * (docs/users/messaging.md §5). The UI does not need it.
 */
public record RecipientResponse(
        String username,
        String displayName,
        String avatarUrl
) {}
