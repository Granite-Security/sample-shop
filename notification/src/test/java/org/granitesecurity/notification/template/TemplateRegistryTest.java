package org.granitesecurity.notification.template;

import org.granitesecurity.notification.channel.Channel;
import org.granitesecurity.notification.channel.RenderedMessage;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The escaping cases here are the one property manual verification cannot catch: a
 * rendered email looks identical whether or not escaping works, because the failure
 * only shows with a hostile value that no manual run produces by accident. This is
 * what replaced the hand-rolled escapeHtml helper in profile's EmailTemplates.
 */
class TemplateRegistryTest {

    private final TemplateRegistry registry = new TemplateRegistry();

    @Test
    void escapesHostileValuesInHtmlBody() {
        RenderedMessage message = render("PasswordResetRequested", Map.of(
                "name", "Alice",
                "resetLink", "https://x.test/reset?token=\"><b>pwned</b>"));

        assertFalse(message.html().contains("<b>pwned</b>"), "hostile markup must not survive into the HTML body");
        assertTrue(message.html().contains("&quot;&gt;&lt;b&gt;pwned"), "it should appear escaped instead");
    }

    @Test
    void doesNotEscapeThePlainTextBody() {
        // Escaping plain text would show the reader a literal "&amp;" in their link.
        RenderedMessage message = render("PasswordResetRequested", Map.of(
                "name", "Alice",
                "resetLink", "https://x.test/reset?token=abc&source=email"));

        assertTrue(message.text().contains("token=abc&source=email"));
        assertFalse(message.text().contains("&amp;"));
    }

    @Test
    void resolvesTemplatesByKebabCasedEventType() {
        assertEquals("Your password was changed",
                render("PasswordChanged", Map.of("name", "Alice", "when", "today")).subject());
        assertEquals("Welcome to Granite Security",
                render("UserRegistered", Map.of("name", "Alice", "username", "alice")).subject());
    }

    @Test
    void returnsEmptyForAnUntemplatedEventType() {
        Optional<RenderedMessage> result =
                registry.render("SomethingNobodyTemplated", Channel.EMAIL, "a@b.test", Map.of());

        assertTrue(result.isEmpty());
    }

    private RenderedMessage render(String eventType, Map<String, Object> model) {
        return registry.render(eventType, Channel.EMAIL, "alice@example.com", model).orElseThrow();
    }
}
