package org.granitesecurity.profile.dto;

/**
 * A submission from the public contact form (docs/users/messaging.md §11).
 *
 * <p>There is deliberately no recipient field: every submission goes to the configured
 * manager, and letting the browser name a recipient would turn an unauthenticated
 * endpoint into a way to write into any user's inbox.
 *
 * <p>{@code name} and {@code email} describe the sender and are only read when nobody
 * is signed in — for an authenticated caller the sender is the JWT subject and these
 * are ignored, exactly as {@link SendMessageRequest} has no {@code from}.
 *
 * @param website the honeypot. A real form leaves it empty because the field is hidden;
 *                anything that fills it in is a bot, and the submission is dropped (§11.1).
 */
public record ContactRequest(
        String name,
        String email,
        String subject,
        String body,
        String website
) {}
