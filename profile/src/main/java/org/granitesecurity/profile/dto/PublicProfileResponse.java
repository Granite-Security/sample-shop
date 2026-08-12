package org.granitesecurity.profile.dto;

import java.time.Instant;

/**
 * What an anonymous visitor sees at {@code /users/<handle>}.
 *
 * <p><strong>Deliberately not {@link ProfileResponse}.</strong> That record carries
 * {@code email} — and will carry whatever else gets added to it later. This one is a
 * separate type so the public surface only ever grows when somebody edits this file
 * (docs/profile/public-profile.md step 2).
 *
 * <p>{@code username} <em>is</em> published, on purpose (D3): it is already visible to
 * every signed-in user through the recipient picker, and publishing it lets the page
 * drive {@code POST /api/profiles/me/messages} and {@code POST /api/balance/me/transfers}
 * with no new resolution path.
 *
 * <p>Never add: email, first/last name, addresses, files, orders, messages, balances.
 */
public record PublicProfileResponse(
        String handle,
        String username,
        String displayName,
        String avatarUrl,
        String bio,
        Instant memberSince
) {}
