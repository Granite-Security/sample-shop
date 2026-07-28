package org.granitesecurity.profile.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** One user as auth-server reports it over the internal API. */
public record AuthUser(
        Long id,
        String username,
        String email,
        String firstName,
        String lastName,
        boolean enabled,
        String provider,
        String providerId,
        List<String> roles,
        OffsetDateTime blockedAt,
        String blockedBy,
        OffsetDateTime createdAt) {

    /**
     * `provider` alone does not mean "signs in with Google" — a user who
     * registered with the form and later signed in with Google keeps
     * provider = LOCAL and gains a providerId, and their password still works.
     * Three states, not a toggle (docs/users/blocking-users.md §2.2).
     */
    public String signInState() {
        if (!"LOCAL".equals(provider)) {
            return "GOOGLE";
        }
        return providerId == null ? "LOCAL" : "LINKED";
    }

    public boolean isAdmin() {
        return roles != null && roles.contains("ROLE_ADMIN");
    }
}
