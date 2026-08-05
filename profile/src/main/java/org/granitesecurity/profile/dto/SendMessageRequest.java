package org.granitesecurity.profile.dto;

/**
 * @param to      a username or an email address — whichever the user typed. There is
 *                deliberately no `from`: the sender is always the JWT subject
 *                (docs/users/messaging.md §5).
 * @param subject optional; blank is stored as null.
 */
public record SendMessageRequest(String to, String subject, String body) {}
