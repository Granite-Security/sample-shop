package org.granitesecurity.profile.dto;

/**
 * Registers an object the browser has already PUT to storage under a presigned
 * URL — profile never sees the bytes, same as {@link RegisterFileRequest}.
 */
public record RegisterAvatarRequest(
        String key,
        String url,
        String contentType,
        Long sizeBytes
) {
}
