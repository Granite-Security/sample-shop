package org.granitesecurity.profile.dto;

/**
 * Which picture wins for a user (docs/users/user-pic.md D3).
 *
 * <p>Stored as a plain string column so nothing needs an R2DBC converter; this
 * enum is the validation boundary for anything arriving from a client.
 */
public enum AvatarSource {
    UPLOAD,
    GOOGLE,
    NONE;

    public static AvatarSource parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return NONE;
        }
        try {
            return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return NONE;
        }
    }
}
