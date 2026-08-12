package org.granitesecurity.profile.dto;

import java.time.Instant;

/**
 * A file its owner published to their public profile
 * (docs/profile/public-profile.md §11).
 *
 * <p>Deliberately not {@link UserFileResponse}: that one is the owner's view and
 * would keep whatever gets added to it later. Never add {@code objectKey} or
 * {@code contentHash} here — the hash identifies the bytes and the key is the
 * storage address, and neither is anyone else's business.
 *
 * <p>{@code url} is the same public media URL the owner already has. Publishing
 * changes where the file is <em>listed</em>, not who can fetch it: the object was
 * always anonymously readable (docs/users/user-profile.md, Security notes).
 */
public record PublicFileResponse(
        Long id,
        String fileName,
        String url,
        String contentType,
        Long sizeBytes,
        Instant createdAt
) {}
