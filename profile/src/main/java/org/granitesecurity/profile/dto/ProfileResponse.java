package org.granitesecurity.profile.dto;

/**
 * {@code avatarUrl} is the <em>effective</em> picture — what a client should
 * render, already resolved from {@code avatarSource}, so no UI has to re-derive
 * it. The other two are exposed so the profile page can offer a choice between
 * an upload and the Google picture without a second round trip
 * (docs/users/user-pic.md §4).
 */
public record ProfileResponse(
        Long id,
        String username,
        String email,
        String firstName,
        String lastName,
        String displayName,
        String handle,
        String bio,
        boolean publicProfile,
        String avatarUrl,
        String avatarSource,
        String uploadedAvatarUrl,
        String googlePictureUrl,
        java.time.Instant createdAt,
        java.time.Instant updatedAt
) {}
