package org.granitesecurity.profile.notification;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

final class EmailTemplates {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("MMMM d, yyyy 'at' HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private EmailTemplates() {}

    static String passwordChangedSubject() {
        return "Your password was changed";
    }

    static String passwordChangedHtml(String displayName, Instant when) {
        String name = displayName == null || displayName.isBlank() ? "there" : displayName;
        return """
                <html>
                  <body style="font-family: sans-serif; color: #1a1a1a;">
                    <p>Hi %s,</p>
                    <p>This is a confirmation that the password for your Granite Security account was
                    changed on %s.</p>
                    <p>If you made this change, no further action is needed.</p>
                    <p>If you did <strong>not</strong> make this change, please secure your account
                    immediately.</p>
                  </body>
                </html>
                """.formatted(escapeHtml(name), TIMESTAMP_FORMAT.format(when));
    }

    static String passwordChangedText(String displayName, Instant when) {
        String name = displayName == null || displayName.isBlank() ? "there" : displayName;
        return """
                Hi %s,

                This is a confirmation that the password for your Granite Security account was changed on %s.

                If you made this change, no further action is needed.

                If you did NOT make this change, please secure your account immediately.
                """.formatted(name, TIMESTAMP_FORMAT.format(when));
    }

    static String passwordResetRequestedSubject() {
        return "Reset your password";
    }

    static String passwordResetRequestedHtml(String displayName, String resetLink) {
        String name = displayName == null || displayName.isBlank() ? "there" : displayName;
        return """
                <html>
                  <body style="font-family: sans-serif; color: #1a1a1a;">
                    <p>Hi %s,</p>
                    <p>We received a request to reset the password for your Granite Security account.
                    Click the link below to choose a new password:</p>
                    <p><a href="%s">%s</a></p>
                    <p>This link expires in 30 minutes and can only be used once.</p>
                    <p>If you did not request this, you can safely ignore this email — your password
                    will not be changed.</p>
                  </body>
                </html>
                """.formatted(escapeHtml(name), escapeHtml(resetLink), escapeHtml(resetLink));
    }

    static String passwordResetRequestedText(String displayName, String resetLink) {
        String name = displayName == null || displayName.isBlank() ? "there" : displayName;
        return """
                Hi %s,

                We received a request to reset the password for your Granite Security account.
                Open the link below to choose a new password:

                %s

                This link expires in 30 minutes and can only be used once.

                If you did not request this, you can safely ignore this email — your password will
                not be changed.
                """.formatted(name, resetLink);
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
