package org.granitesecurity.profile.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * A row of the admin users page: an auth-server user, enriched with whatever
 * profile knows about them.
 *
 * <p>The list is built from auth users, not profiles — they are not the same
 * set. A profile row can exist for a Google subject or a service account, and a
 * real user can have no profile row at all (docs/users/blocking-users.md §2.1,
 * D3). {@code hasProfile} says which.
 */
public record AdminUserView(
        String username,
        String email,
        String firstName,
        String lastName,
        String displayName,
        boolean enabled,
        String signInState,
        List<String> roles,
        boolean hasProfile,
        // The effective picture only — an admin list has no business offering
        // the alternatives a user is choosing between.
        String avatarUrl,
        OffsetDateTime blockedAt,
        String blockedBy,
        Instant profileCreatedAt) {
}
