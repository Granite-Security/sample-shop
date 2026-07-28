package org.granitesecurity.authserver.user;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * One identity as auth-server knows it. `provider` and `providerId` are reported
 * raw rather than collapsed into a "signs in with" flag: LOCAL+null,
 * LOCAL+providerId (linked, password still works) and GOOGLE+providerId are
 * three distinct states, and it is profile's job to render them
 * (docs/users/blocking-users.md §2.2).
 */
public record AdminUserResponse(
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

    static AdminUserResponse from(UserEntity user) {
        return new AdminUserResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                user.isEnabled(),
                user.getProvider(),
                user.getProviderId(),
                user.getAuthorities().stream().map(AuthorityEntity::getAuthority).sorted().toList(),
                user.getBlockedAt(),
                user.getBlockedBy(),
                user.getCreatedAt());
    }
}
