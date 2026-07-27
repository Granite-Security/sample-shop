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

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
